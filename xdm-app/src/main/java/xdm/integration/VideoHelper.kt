package xdm.integration

import xdm.app.AppContext
import xdm.app.utils.captureDestination
import xdm.core.*
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.SubtitleTrack
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.downloaders.web.streaming.manifest.dash.MpdEntry
import xdm.core.downloaders.web.streaming.manifest.dash.Representation
import xdm.core.downloaders.web.streaming.manifest.dash.XlinkResolver
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest
import xdm.core.downloaders.web.streaming.manifest.hls.HlsMasterPlaylist
import xdm.core.downloaders.web.streaming.manifest.hls.HlsParser
import xdm.core.downloaders.web.streaming.manifest.hls.HlsPlaylist
import xdm.core.downloaders.web.streaming.manifest.hls.getInfoString
import xdm.core.downloaders.web.streaming.manifest.hls.singleFileHttpUrl
import xdm.core.media.Mp4InitProbe
import xdm.core.network.http.*
import xdm.core.network.http.impl.*
import xdm.core.util.*
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.*
import java.nio.file.*
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


object VideoHelper {
    /** Everything the manifest client is built from; a change to any part rebuilds it. */
    private data class ClientKey(
        val proxy: java.net.Proxy?,
        val ignoreCertErrors: Boolean,
        val proxyUser: String,
        val proxyPass: String,
    )

    private var cachedClient: PoolingHttpClient? = null
    private var cachedClientKey: ClientKey? = null

    /**
     * Manifest client, rebuilt when the proxy or certificate-check settings change so edits in
     * Settings apply without a restart. A replaced client is not closed (that would cancel in-flight
     * manifest fetches); OkHttp releases its idle threads and connections on its own.
     */
    private val httpClient: PoolingHttpClient
        @Synchronized get() {
            val config = AppContext.config
            val key = ClientKey(config.toProxy(), config.ignoreCertErrors, config.proxyUser, config.proxyPass)
            cachedClient?.takeIf { key == cachedClientKey }?.let { return it }
            return HttpClientImpl(
                10, key.proxy, key.ignoreCertErrors,
                proxyUser = key.proxyUser, proxyPassword = key.proxyPass,
                // Proxy only: a manifest fetched in the background must not pop up a server login.
                auth = AppContext.httpAuth,
            ).also {
                cachedClient = it
                cachedClientKey = key
            }
        }
    private val hslExt = listOf("mpegurl", ".m3u8", "m3u8")

    /** Path extensions taken as a plain media file when the Content-Type does not say what it is. */
    private val plainMediaExts = setOf(
        "mp4", "m4v", "m4a", "mkv", "mov", "3gp", "avi", "divx", "wmv", "mpg", "mpeg", "flv",
        "ogv", "ogg", "oga", "opus", "mp3", "aac", "wav", "flac"
    )

    /**
     * What has been seen recently. These used to be plain sets that only ever grew: a tab that once
     * showed an m3u8 suppressed plain videos in it forever, even after navigating elsewhere, and the
     * fragment and referer sets held every URL of the session. They are now bounded, least-recently-
     * used maps, and the tab map remembers which page the manifest was on, so a tab that navigates
     * starts fresh.
     */
    private const val MAX_TRACKED_TABS = 64
    private const val MAX_TRACKED_URLS = 512

    /** Tab id -> the page URL that was showing an HLS/DASH manifest ("" when the page URL is unknown). */
    private val m3u8MpdTabs = lruMap<String, String>(MAX_TRACKED_TABS)
    private val suspectedMp4Fragments = lruMap<String, Boolean>(MAX_TRACKED_URLS)
    private val referersToSkip = lruMap<Long, Boolean>(MAX_TRACKED_URLS)

    /**
     * A player re-requests the same manifest (retries, live playlist refreshes), and each request
     * makes XDM fetch it again. Against CDNs that rate-limit or use-limit a signed URL, those
     * replays are what earns the 410, so the user can opt in to fetching each one only once per
     * window. Only the key and a timestamp are kept, never the manifest body.
     */
    private const val MANIFEST_REFETCH_WINDOW_MS = 30_000L

    /** (tab id, manifest URL) -> when XDM last fetched it for that tab. */
    private val recentManifests = lruMap<Pair<String?, String>, Long>(MAX_TRACKED_URLS)

    /** Access-ordered map that drops its least recently used entry past [max]. */
    private fun <K, V> lruMap(max: Int): MutableMap<K, V> = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > max
        }
    )

    /**
     * Manifest fetching and parsing. Every media message used to start its own thread, so a page
     * full of streams could start an unbounded number of them; this is a small pool with a bounded
     * queue that drops work rather than piling it up. Idle threads go away on their own.
     */
    private val mediaExecutor = ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(64),
        { r -> Thread(r, "media-detect").apply { isDaemon = true } },
        RejectedExecutionHandler { _, _ -> Logger.info("XDM", "Media detection is busy, ignoring manifest") }
    ).apply { allowCoreThreadTimeOut(true) }

    /** What [loadHls] / [loadDash] found: the choices the stream-download dialog offers. */
    sealed interface LoadedManifest {
        val url: String

        /** An HLS master playlist: every (variant, audio rendition) pair, as the browser path gets them. */
        data class HlsMaster(override val url: String, val entries: List<HlsMasterPlaylist>) : LoadedManifest

        /** An HLS media playlist, downloaded as it is. */
        data class HlsMedia(override val url: String, val playlist: HlsPlaylist) : LoadedManifest

        /** A DASH manifest: every (video, audio) pair, or the single streams. */
        data class Dash(override val url: String, val entries: List<MpdEntry>) : LoadedManifest
    }

    /** Why a playlist the user entered could not be loaded, in words for the dialog's status line. */
    class ManifestLoadException(message: String) : Exception(message)

    /**
     * Fetches and parses an HLS playlist the user entered (Stream download dialog), with the same
     * client and parser as captured streams. Blocking; throws [ManifestLoadException].
     * Headless mode passes its own [client], since it has no app config to build this one from.
     */
    fun loadHls(
        url: String, headers: HeaderMap, cookie: String?, cancelled: AtomicBoolean, client: PoolingHttpClient = httpClient,
    ): LoadedManifest {
        val lines = fetchManifest(client, url, withAccept(headers), cookie, cancelled).toString(Charsets.UTF_8).lines()
        if (HlsParser.isMasterPlaylist(lines.iterator())) {
            val entries = HlsParser.parseMasterPlaylist(lines.iterator(), url).getOrNull()
            if (entries.isNullOrEmpty()) throw ManifestLoadException("No playable variant in the master playlist")
            return LoadedManifest.HlsMaster(url, entries)
        }
        val playlist = HlsParser.parseMediaSegments(lines.iterator(), url).getOrNull()
        if (playlist == null || playlist.mediaSegments.isEmpty()) throw ManifestLoadException("Not an HLS playlist")
        return LoadedManifest.HlsMedia(url, playlist)
    }

    /** Like [loadHls], for a DASH manifest. */
    fun loadDash(
        url: String, headers: HeaderMap, cookie: String?, cancelled: AtomicBoolean, client: PoolingHttpClient = httpClient,
    ): LoadedManifest.Dash {
        val withAccept = withAccept(headers)
        val bytes = fetchManifest(client, url, withAccept, cookie, cancelled)
        val entries = try {
            parseDash(url, bytes, withAccept, cookie, client)
        } catch (e: Exception) {
            throw ManifestLoadException("Not a DASH manifest (${e.message ?: e.javaClass.simpleName})")
        }
        if (entries.none { it.video != null || it.audio != null }) {
            throw ManifestLoadException("No playable stream in the manifest")
        }
        return LoadedManifest.Dash(url, entries)
    }

    private fun fetchManifest(
        client: PoolingHttpClient, url: String, headers: HeaderMap, cookie: String?, cancelled: AtomicBoolean,
    ): ByteArray {
        var failure: String? = null
        return ManifestUtils.downloadManifestBytes(
            client, url, headers, cookie, cancelled,
            onError = { failure = it.message ?: it.javaClass.simpleName },
            onHttpError = { failure = "HTTP $it" },
        ) ?: throw ManifestLoadException(failure ?: "No response")
    }

    /** Playlist requests carry an Accept header, as a browser's do; some CDNs refuse requests without one. */
    private fun withAccept(headers: HeaderMap): HeaderMap =
        if (headers.keys.any { it.equals("accept", ignoreCase = true) }) headers else headers + ("ACCEPT" to listOf("*/*"))

    /**
     * Delays between manifest-probe attempts. The probe races the player: the browser fetches
     * the playlist first and tight CDNs answer our immediate re-fetch with 403/429. Their budget
     * resets within seconds, so two spaced retries before the video is dropped. Bounded (~9s worst
     * case) for the 2-thread media-detect pool.
     */
    private val manifestProbeRetryDelaysMs = longArrayOf(4000, 5000)

    /**
     * Fetches a captured playlist, retrying transient rate-limit walls. Only 429 and the 403 some
     * CDNs answer throttled fetches with are retried; anything else (404, DNS, TLS) is final and
     * fails fast without sleeping.
     */
    private fun downloadManifestForCapture(
        url: String,
        headers: MutableMap<String, List<String>>,
        cookie: String?,
    ): ByteArray? {
        var httpError = 0
        ManifestUtils.downloadManifestBytes(
            httpClient, url, headers, cookie, AtomicBoolean(false),
            onHttpError = { httpError = it },
        )?.let { return it }
        if (httpError != 429 && httpError != 403) {
            return null
        }
        for (waitMs in manifestProbeRetryDelaysMs) {
            Logger.info("Manifest probe got $httpError, retrying in ${waitMs}ms: $url")
            try {
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            httpError = 0
            ManifestUtils.downloadManifestBytes(
                httpClient, url, headers, cookie, AtomicBoolean(false),
                onHttpError = { httpError = it },
            )?.let { return it }
        }
        Logger.info("Manifest probe failed, dropping video: $url")
        return null
    }

    /** Parses an MPD, resolving xlink remote elements (e.g. ad-insertion Periods) with the same headers and cookie. */
    private fun parseDash(
        url: String, bytes: ByteArray, headers: HeaderMap, cookie: String?, client: PoolingHttpClient = httpClient,
    ): List<MpdEntry> {
        val xlinkResolver = XlinkResolver { xlinkUrl ->
            ManifestUtils.downloadManifestBytes(client, xlinkUrl, headers, cookie, AtomicBoolean(false))
                ?.toString(Charsets.UTF_8)
        }
        return ByteArrayInputStream(bytes).use { parseMpdManifest(it, url, xlinkResolver) }
    }

    private fun isHLS(contentType: String?): Boolean = hslExt.any { StringUtils.containsIgnoreCase(contentType, it) }

    private fun isHttpVideo(url: String, contentType: String?, size: Long?, tabId: String?, tabUrl: String?): Boolean {
        size?.let {
            if (size > 0 && size < AppContext.config.minVideoSize * 1024) {
                return false
            }
        }
        tabId?.let {
            // Only while the tab is still on the page the manifest came from.
            if (m3u8MpdTabs[it] == (tabUrl ?: "")) {
                return false
            }
        }
        if (StringUtils.containsIgnoreCase(url, "init.mp4")) {
            suspectedMp4Fragments[URI.create(url).resolve(".").toString()] = true
            return false
        }
        if (suspectedMp4Fragments.containsKey(URI.create(url).resolve(".").toString())) {
            return false
        }
        return !(StringUtils.containsIgnoreCase(url, "http://127.0.0.1:9614") || StringUtils.containsIgnoreCase(
            url,
            "http://127.0.0.1:8597"
        ) || StringUtils.containsIgnoreCase(url, "fbcdn") || StringUtils.containsIgnoreCase(
            url,
            "abst"
        ) || StringUtils.containsIgnoreCase(url, "f4x") || StringUtils.containsIgnoreCase(
            contentType,
            "m4s"
        ) || StringUtils.containsIgnoreCase(contentType, "f4f"))
    }

    /**
     * Forgets what was captured for a tab that has just started loading a new document. Without
     * this the tab stays marked as "a manifest came from here", so plain HTTP videos on the
     * reloaded/next page would keep being suppressed by [isHttpVideo].
     */
    fun onTabNavigated(tabId: String) {
        m3u8MpdTabs.remove(tabId)
        splitTracks.onTabNavigated(tabId)
        // The tab's detected videos were just cleared, so a reload must be allowed to find them again.
        synchronized(recentManifests) { recentManifests.keys.removeIf { it.first == tabId } }
    }

    /**
     * True if this manifest should be fetched now; false if it was fetched for this tab moments ago.
     * Always true unless "Skip repeated playlist requests" is on in Advanced settings.
     */
    private fun claimManifestFetch(url: String, tabId: String?): Boolean {
        if (!AppContext.config.skipDuplicateManifests) {
            return true
        }
        val now = System.currentTimeMillis()
        synchronized(recentManifests) {
            val key = tabId to url
            val last = recentManifests[key]
            if (last != null && now - last < MANIFEST_REFETCH_WINDOW_MS) {
                return false
            }
            recentManifests[key] = now
            return true
        }
    }

    private fun isHLSUrl(url: String?): Boolean = StringUtils.containsIgnoreCase(url, "m3u8")

    private fun isDash(contentType: String?): Boolean = StringUtils.containsIgnoreCase(contentType, "dash")

    private fun isDashUrl(url: String?): Boolean = StringUtils.containsIgnoreCase(url, ".mpd")

    private fun plainMediaExt(url: String): String? =
        runCatching { URI.create(url).path }.getOrNull()
            ?.substringAfterLast('/')
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it in plainMediaExts }

    /**
     * Whether a response with no Content-Type is still worth a look: a manifest by its URL, or a
     * plain media file by its path extension whose Content-Length says it is at least [minBytes].
     * With neither a type nor a size (range probes, segments) there is too little to go on.
     */
    internal fun isUntypedMedia(url: String, contentLength: Long?, minBytes: Long): Boolean =
        isHLSUrl(url) || isDashUrl(url)
            || (plainMediaExt(url) != null && contentLength != null && contentLength >= minBytes)

    fun processMediaMessage(msg: ExtensionMessage) {
        val responseHeaders =
            msg.responseHeaders?.map { entry -> entry.key to entry.value.map { it.value } }?.associate { it }
        val contentType = getHeader(CONTENT_TYPE, responseHeaders)
        // The whole file's size, also for a range request. A malformed value must not abort the message.
        val contentLength = getFileSize(responseHeaders)
        msg.url ?: return
        // Facebook/Instagram/bilibili tracks: paired from their CDN requests (see SplitTrackDetector).
        if (splitTracks.onMediaMessage(msg, CoreUtils::uniqueId)) {
            return
        }
        if (contentType == null && !isUntypedMedia(msg.url, contentLength, AppContext.config.minVideoSize * 1024)) {
            return
        }
        val dash = isDash(contentType) || isDashUrl(msg.url)
        if ((dash || isHLS(contentType) || isHLSUrl(msg.url)) && !claimManifestFetch(msg.url, msg.tabId)) {
            Logger.info("Manifest fetched moments ago, skipping: ${msg.url}")
            return
        }
        when {
            dash -> mediaExecutor.execute { processDashVideo(msg) }
            isHLS(contentType) || isHLSUrl(msg.url) -> mediaExecutor.execute { processHLSVideo(msg) }
            isHttpVideo(msg.url, contentType, contentLength, msg.tabId, msg.tabUrl) -> processHttpVideo(
                msg,
                contentType,
                contentLength ?: -1L
            )
        }
    }

    private val splitTracks = SplitTrackDetector(object : SplitTrackDetector.Env {
        override fun execute(task: () -> Unit) = mediaExecutor.execute(task)

        override fun probe(url: String, msg: ExtensionMessage): SplitTrackDetector.Probe? =
            probeHead(httpClient, url, msg.requestHeaders, msg.cookie)

        override fun offer(offer: SplitTrackDetector.Offer) = submitSplitTracks(offer)
    })

    /** Head bytes read first; a `moov`/`sidx` reaching past them is fetched again in full, up to [MAX_HEAD_BYTES]. */
    private const val HEAD_PROBE_BYTES = 16 * 1024L
    private const val MAX_HEAD_BYTES = 2 * 1024 * 1024L
    private val CONTENT_RANGE_TOTAL = Regex("""/(\d+)\s*$""")

    /** Reads the head of a single-file fMP4 track: what it holds, how long and how big it is, and its sidx. */
    internal fun probeHead(
        client: PoolingHttpClient, url: String, headers: HeaderMap?, cookie: String?,
    ): SplitTrackDetector.Probe? {
        var want = HEAD_PROBE_BYTES
        for (attempt in 0..1) {
            val response = client.getResponse(url, headers, cookie, Range(0, want - 1)).getOrNull() ?: return null
            response.use {
                val total = when (response.statusCode) {
                    206 -> response.getHeader("Content-Range")
                        ?.let { CONTENT_RANGE_TOTAL.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    200 -> response.contentLength
                    else -> {
                        Logger.info("XDM", "Track probe got HTTP ${response.statusCode}: $url")
                        return null
                    }
                }
                val bytes = response.inputStream.use { it.readNBytes(want.toInt()) }
                val info = Mp4InitProbe.parse(bytes) ?: return null
                if (attempt == 0 && info.needBytes > want && info.needBytes <= MAX_HEAD_BYTES) {
                    want = info.needBytes
                } else {
                    return SplitTrackDetector.Probe(info, total ?: info.sidx?.endOffset ?: return null)
                }
            }
        }
        return null
    }

    /** Publishes a split-track pair as a DASH capture; the same [SplitTrackDetector.Offer.id] replaces an earlier one. */
    private fun submitSplitTracks(offer: SplitTrackDetector.Offer) {
        val msg = offer.msg
        val name = fileNameWithExt(msg, "mp4")
        val origin = msg.tabUrl ?: getHeader(REFERER, msg.requestHeaders)
        val dest = captureDestination(name, offer.video.url, origin)
        val info = splitTrackTask(
            offer.id, offer.video, offer.audio, name, origin, msg.requestHeaders, msg.cookie, dest.first, dest.second,
        ) ?: return
        val video = offer.video.probe
        val size = video.totalSize + offer.audio.probe.totalSize
        val quality = listOfNotNull(splitTrackQuality(offer.video), FormatHelper.formatSize(size.toDouble()))
            .joinToString(" ")
        AppContext.videoTracker.addVideoDash(
            listOf(
                Pair(
                    info, StreamingVideoDisplayInfo(
                        quality = quality,
                        size = size,
                        duration = video.info.durationSeconds.toLong(),
                        dateTime = LocalDateTime.now(),
                        tabId = msg.tabId ?: "0",
                        tabUrl = msg.tabUrl,
                    )
                )
            )
        )
    }

    /**
     * A DASH download of a split-track pair, each track cut at its sidx (or whole without one). Null if
     * a URL does not parse. Used for captures and for the batch dialog's one-download-per-link mode.
     */
    fun splitTrackTask(
        id: Long, video: SplitTrackDetector.Track, audio: SplitTrackDetector.Track, fileName: String,
        origin: String?, headers: HeaderMap?, cookie: String?, categoryId: String?, targetFolder: String,
    ): DashDownloadTaskInfo? = DashDownloadTaskInfo(
        id = id,
        fileName = fileName,
        tempDir = AppContext.config.tempFolder,
        respectFileName = true,
        cookie = cookie,
        headers = headers,
        origin = origin,
        categoryId = categoryId,
        targetFolder = targetFolder,
        maxPiece = AppContext.config.maxSegments,
        authInfo = null,
        url = video.url,
        audioSegments = splitTrackSegments(audio) ?: return null,
        videoSegments = splitTrackSegments(video) ?: return null,
        audioMime = "audio/mp4",
        videoMime = "video/mp4",
    )

    /** "1080p VP9" for a video track, or null when it says neither. */
    fun splitTrackQuality(video: SplitTrackDetector.Track): String? = listOfNotNull(
        video.probe.info.height.takeIf { it > 0 }?.let { "${it}p" },
        codecName(video.probe.info.codec),
    ).joinToString(" ").ifEmpty { null }

    /** Reads the head of a split track for the batch dialog, with that batch's headers and cookie. */
    fun probeSplitTrack(url: String, headers: HeaderMap?, cookie: String?): SplitTrackDetector.Probe? =
        runCatching { probeHead(httpClient, url, headers, cookie) }
            .onFailure { Logger.error("XDM", "Track probe failed: $url", it) }.getOrNull()

    /** What the capture side knows of these tracks' videos: see [SplitTrackDetector.lookup]. */
    fun knownSplitTracks(trackKeys: Set<String>): Map<String, List<SplitTrackDetector.KnownTrack>> =
        splitTracks.lookup(trackKeys)

    /** The track as byte-range segments cut at its sidx, or as one whole-file segment without one. */
    private fun splitTrackSegments(track: SplitTrackDetector.Track): List<DashSegment>? {
        val uri = runCatching { URI(track.url) }.getOrNull() ?: return null
        val ranges = Mp4InitProbe.segmentRanges(track.probe.info, track.probe.totalSize)
            ?: return listOf(DashSegment(uri))
        return ranges.map { DashSegment(uri, it) }
    }

    private fun codecName(codec: String?): String? = when (codec) {
        "avc1", "avc3" -> "H.264"
        "hvc1", "hev1" -> "HEVC"
        "vp09" -> "VP9"
        "av01" -> "AV1"
        else -> codec
    }

    private fun processHttpVideo(msg: ExtensionMessage, type: String?, len: Long) {
        processHttpVideo(msg, type, len, msg.url!!, false)
    }

    private fun processHttpVideo(
        msg: ExtensionMessage,
        type: String?,
        len: Long,
        url: String,
        ignoreFragment: Boolean
    ) {
        if (!ignoreFragment && isFragment(getHeader(REFERER, msg.requestHeaders))) {
            Logger.info("$url is fragment, ignoring")
            return
        }
        val pathExt = plainMediaExt(url)
        val ext = when {
            StringUtils.containsIgnoreCase(type, "video/mp4") -> "mp4"
            StringUtils.containsIgnoreCase(type, "video/x-flv") -> "flv"
            StringUtils.containsIgnoreCase(type, "video/webm") -> "mkv"
            StringUtils.containsIgnoreCase(type, "matroska") || StringUtils.containsIgnoreCase(type, "mkv") -> "mkv"
            type == "audio/mpeg" || StringUtils.containsIgnoreCase(type, "audio/mp3") -> "mp3"
            StringUtils.containsIgnoreCase(type, "audio/aac") || StringUtils.containsIgnoreCase(type, "audio/x-aac") -> "aac"
            StringUtils.containsIgnoreCase(type, "audio/mp4") -> "m4a"
            StringUtils.containsIgnoreCase(type, "audio/x-m4a") -> "m4a"
            StringUtils.containsIgnoreCase(type, "video/x-m4v") -> "m4v"
            StringUtils.containsIgnoreCase(type, "video/quicktime") -> "mov"
            StringUtils.containsIgnoreCase(type, "video/3gpp") -> "3gp"
            StringUtils.containsIgnoreCase(type, "video/x-msvideo") || StringUtils.containsIgnoreCase(type, "video/avi") -> "avi"
            StringUtils.containsIgnoreCase(type, "video/x-ms-wmv") -> "wmv"
            StringUtils.containsIgnoreCase(type, "divx") -> "divx"
            StringUtils.containsIgnoreCase(type, "video/mpeg") -> "mpg"
            StringUtils.containsIgnoreCase(type, "video/ogg") -> "ogv"
            StringUtils.containsIgnoreCase(type, "audio/ogg") -> "ogg"
            StringUtils.containsIgnoreCase(type, "audio/opus") -> "opus"
            StringUtils.containsIgnoreCase(type, "audio/webm") -> "webm"
            StringUtils.containsIgnoreCase(type, "flac") -> "flac"
            StringUtils.containsIgnoreCase(type, "audio/") && StringUtils.containsIgnoreCase(type, "wav") -> "wav"
            // Servers that send application/octet-stream (or nothing useful): trust the path's extension.
            pathExt != null -> pathExt
            StringUtils.containsIgnoreCase(url, ".mp4") -> "mp4"
            StringUtils.containsIgnoreCase(url, ".mkv") -> "mkv"
            StringUtils.containsIgnoreCase(url, ".ts") -> "ts"
            else -> {
                return
            }
        }
        submitHttpDownload(msg, url, ext, len)
    }

    /**
     * Registers a plain HTTP video download with the given output [ext]. Used both by the
     * content-type-guessing [processHttpVideo] and by the single-file HLS/DASH fast paths, which
     * pass a native container extension (via [plainDownloadExt]) because nothing is transmuxed.
     */
    private fun submitHttpDownload(msg: ExtensionMessage, url: String, ext: String, len: Long) {
        val respHeaders =
            msg.responseHeaders?.map { entry -> entry.key to entry.value.map { it.value } }?.associate { it }
        val name = fileNameWithExt(msg, ext)
        val dest = captureDestination(name, url, getHeader("Referer", msg.requestHeaders) ?: msg.tabUrl)
        val http = HttpDownloadTaskInfo(
            id = CoreUtils.uniqueId(),
            url = url,
            fileName = name,
            respectFileName = true,
            cookie = msg.cookie,
            headers = msg.requestHeaders,
            origin = getHeader("Referer", msg.requestHeaders),
            categoryId = dest.first,
            targetFolder = dest.second,
            maxPiece = AppContext.config.maxSegments,
            authInfo = null,
            knownFileSize = msg.fileSize ?: getFileSize(respHeaders)
        )

        AppContext.videoTracker.addVideoHttp(
            listOf(
                Pair(
                    http, StreamingVideoDisplayInfo(
                        quality = "[$ext] " + (if (len > 0) FormatHelper.formatSize(len.toDouble()) else ""),
                        size = len,
                        dateTime = LocalDateTime.now(),
                        tabId = msg.tabId,
                        tabUrl = msg.tabUrl,
                    )
                )
            )
        )
    }

    private fun isFragment(referer: String?): Boolean {
        referer?.let {
            val hash = generate64BitHash(referer)
            if (referersToSkip.containsKey(hash)) {
                return true
            }
        }
        return false
    }

    private fun getFileName(msg: ExtensionMessage): String {
        // Ignore blank/whitespace-only candidates so an empty tab title does not
        // collapse into a hidden ".<ext>" file once the extension is appended.
        val candidate = listOf(msg.filename, msg.file, msg.tabTitle)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?: FileUtils.getFileName(msg.url)
        return FileUtils.sanitizeFileName(candidate)!!
    }

    // Build "<name>.<ext>" without duplicating an extension the name already carries
    // (e.g. a tab title of "clip.mp4" would otherwise become "clip.mp4.mp4").
    private fun fileNameWithExt(msg: ExtensionMessage, ext: String): String {
        val name = getFileName(msg)
        return if (name.endsWith(".$ext", ignoreCase = true)) name else "$name.$ext"
    }

    private fun processDashVideo(msg: ExtensionMessage) {
        Logger.info("Processing DASH manifest:  ${msg.url}")
        msg.url ?: return
        msg.tabId?.let { m3u8MpdTabs[it] = msg.tabUrl ?: "" }
        val headers = msg.requestHeaders ?: HashMap<String, List<String>>()
        getHeader(REFERER, headers)?.let { referersToSkip[generate64BitHash(it)] = true }
        val acceptHeaderAdded = headers.keys.any { StringUtils.equalsIgnoreCase(it, "accept") }
        if (!acceptHeaderAdded) {
            headers["ACCEPT"] = mutableListOf("*/*")
        }
        val bytes = downloadManifestForCapture(msg.url, headers, msg.cookie) ?: return
        parseDashManifest(msg, msg.url, bytes, headers)
    }

    private fun parseDashManifest(
        msg: ExtensionMessage,
        url: String,
        bytes: ByteArray,
        headers: MutableMap<String, List<String>>,
    ) {
        val entries = parseDash(url, bytes, headers, msg.cookie)
        if (entries.isEmpty()) {
            Logger.info("Unable to parse manifest")
            return
        }
        for (plc in entries) {
            val video = plc.video
            val audio = plc.audio
            if (video != null && audio != null) {
                val fileExt = if (video.mimeType.contains("mp4") && audio.mimeType.contains("mp4")) "mp4" else "mkv"
                val name = fileNameWithExt(msg, fileExt)
                val dest = captureDestination(name, url, msg.tabUrl ?: getHeader(REFERER, msg.requestHeaders))
                val dashDownloadTaskInfo = DashDownloadTaskInfo(
                    id = CoreUtils.uniqueId(),
                    fileName = name,
                    tempDir = AppContext.config.tempFolder,
                    respectFileName = true,
                    cookie = msg.cookie,
                    headers = msg.requestHeaders,
                    // The page the manifest was found on, so "Refresh link" has somewhere to go.
                    origin = msg.tabUrl ?: getHeader(REFERER, msg.requestHeaders),
                    categoryId = dest.first,
                    targetFolder = dest.second,
                    maxPiece = AppContext.config.maxSegments,
                    authInfo = null,
                    url = url,
                    audioSegments = audio.segments,
                    videoSegments = video.segments,
                    audioMime = audio.mimeType,
                    videoMime = video.mimeType,
                    subtitles = plc.subtitles,
                )

                AppContext.videoTracker.addVideoDash(
                    listOf(
                        Pair(
                            dashDownloadTaskInfo, StreamingVideoDisplayInfo(
                                quality = withSubtitleCount(getDashDisplayInfo(video, audio), plc.subtitles.size),
                                dateTime = LocalDateTime.now(),
                                tabId = msg.tabId ?: "0",
                                tabUrl = msg.tabUrl,
                            )
                        )
                    )
                )
            } else {
                // A period with neither stream used to throw on the !! below.
                val single = video ?: audio ?: continue
                val mimeType = single.mimeType
                val segments = single.segments
                if (segments.isNotEmpty()) {
                    // Single-stream representation, downloaded whole with no mux: keep the native
                    // container from the MIME type (video/webm -> .webm, audio/mp4 -> .m4a, ...).
                    val segUrl = segments[0].toString()
                    submitHttpDownload(msg, segUrl, plainDownloadExt(mimeType, segUrl), -1)
                }
            }
        }
    }

    /** Adds "· CC n" to a quality line when the stream has subtitles; the download window offers them. */
    private fun withSubtitleCount(quality: String?, count: Int): String? {
        if (count == 0) return quality
        val base = quality?.trim().orEmpty()
        return if (base.isEmpty()) "CC $count" else "$base · CC $count"
    }

    private fun getDashDisplayInfo(video: Representation, audio: Representation): String {
        val res = if (video.height > 0) "${video.height}p" else ""
        val lng = if (audio.language != "und") audio.language else ""
        val bw = (video.bandwidth + audio.bandwidth) / 1024
        val bwStr = if (bw > 0) "$bw kbps" else ""
        return "$res $bwStr $lng"
    }

    private fun processHLSVideo(msg: ExtensionMessage) {
        Logger.info("Downloading HLS manifest:  ${msg.url}")
        msg.url ?: return
        msg.tabId?.let { m3u8MpdTabs[it] = msg.tabUrl ?: "" }
        val headers = msg.requestHeaders ?: HashMap<String, List<String>>()
        getHeader(REFERER, headers)?.let { referersToSkip[generate64BitHash(it)] = true }
        val acceptHeaderAdded = headers.keys.any { StringUtils.equalsIgnoreCase(it, "accept") }
        if (!acceptHeaderAdded) {
            headers["ACCEPT"] = mutableListOf("*/*")
        }
        // Read whole, once: the old `Files.lines` iterators over a temp file were never closed, which
        // leaked a file handle per parse and, on Windows, blocked the delete.
        val lines = downloadManifestForCapture(msg.url, headers, msg.cookie)?.toString(Charsets.UTF_8)?.lines() ?: return
        if (HlsParser.isMasterPlaylist(lines.iterator())) {
            Logger.info("Master playlist found")
            val playlists: List<HlsMasterPlaylist> =
                HlsParser.parseMasterPlaylist(lines.iterator(), msg.url).getOrNull() ?: return
            Logger.info("Items in playlist: ${playlists.size}")
            for (playlist in playlists) {
                val videoUrl = playlist.videoPlaylist?.toString()
                val audioUrl = playlist.audioPlaylist?.toString()
                if (videoUrl == null && audioUrl == null) {
                    Logger.info("Both audio video url missing")
                    continue
                }
                val audioOnly = videoUrl == null
                val primaryUrl = videoUrl ?: audioUrl!!
                val secondaryUrl = if (audioOnly) null else audioUrl
                val hlsSource = toHlsSource(
                    msg, secondaryUrl, primaryUrl, audioOnly, playlist.independent, playlist.subtitles
                )
                AppContext.videoTracker.addVideoHls(
                    listOf(
                        Pair(
                            hlsSource, StreamingVideoDisplayInfo(
                                quality = withSubtitleCount(playlist.getInfoString(), playlist.subtitles.size),
                                dateTime = LocalDateTime.now(),
                                tabId = msg.tabId ?: "0",
                                tabUrl = msg.tabUrl,
                            )
                        )
                    )
                )
            }
        } else {
            Logger.info("Processing normal hls playlist")
            val playlist = HlsParser.parseMediaSegments(lines.iterator(), msg.url).getOrNull() ?: return
            if (playlist.mediaSegments.isEmpty()) {
                return
            }
            // A single self-contained file addressed purely by byte ranges is just a plain media
            // file: fetch it whole with the (multi-connection, resumable) HTTP downloader instead of
            // pulling each range and transmuxing.
            playlist.singleFileHttpUrl()?.let { fileUrl ->
                // No mux happens, so keep the native container (MPEG-TS -> .ts, fMP4 -> .mp4, etc.).
                val ext = plainDownloadExt(null, fileUrl)
                Logger.info("Single-file byte-range HLS; downloading as plain HTTP ($ext): $fileUrl")
                submitHttpDownload(msg, fileUrl, ext, -1)
                return
            }
            val hlsSource = toHlsSource(msg, null, msg.url, false, playlist.independent)
            AppContext.videoTracker.addVideoHls(
                listOf(
                    Pair(
                        hlsSource, StreamingVideoDisplayInfo(
                            dateTime = LocalDateTime.now(),
                            tabId = msg.tabId ?: "0",
                            tabUrl = msg.tabUrl,
                        )
                    )
                )
            )
        }
    }


    private fun generate64BitHash(input: String): Long {
        val md = MessageDigest.getInstance("SHA-1")
        val sha1HashBytes = md.digest(input.toByteArray())

        // Take the first 8 bytes (64 bits) of the SHA-1 hash
        // and convert them into a long.
        val buffer: ByteBuffer = ByteBuffer.wrap(sha1HashBytes)
        return buffer.getLong()
    }

    private fun toHlsSource(
        msg: ExtensionMessage,
        audioUrl: String?,
        videoUrl: String,
        audioOnly: Boolean,
        independent: Boolean,
        subtitles: List<SubtitleTrack> = emptyList(),
    ): HlsDownloadTaskInfo {
        val name = fileNameWithExt(msg, "mp4")
        val dest = captureDestination(name, videoUrl, msg.tabUrl ?: getHeader(REFERER, msg.requestHeaders))
        return HlsDownloadTaskInfo(
            id = CoreUtils.uniqueId(),
            fileName = name,
            tempDir = AppContext.config.tempFolder,
            respectFileName = true,
            cookie = msg.cookie,
            headers = msg.requestHeaders,
            // The page the manifest was found on, so "Refresh link" has somewhere to go.
            origin = msg.tabUrl ?: getHeader(REFERER, msg.requestHeaders),
            categoryId = dest.first,
            targetFolder = dest.second,
            maxPiece = AppContext.config.maxSegments,
            authInfo = null,
            url = videoUrl,
            audioUrl = audioUrl,
            audioOnly = audioOnly,
            independent,
            subtitles = subtitles,
        )
    }
}