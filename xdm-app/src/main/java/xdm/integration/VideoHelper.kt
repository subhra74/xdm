package xdm.integration

import xdm.app.AppContext
import xdm.core.*
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.streaming.manifest.dash.Representation
import xdm.core.downloaders.web.streaming.manifest.dash.XlinkResolver
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest
import xdm.core.downloaders.web.streaming.manifest.hls.HlsMasterPlaylist
import xdm.core.downloaders.web.streaming.manifest.hls.HlsParser
import xdm.core.downloaders.web.streaming.manifest.hls.getInfoString
import xdm.core.downloaders.web.streaming.manifest.hls.singleFileHttpUrl
import xdm.core.network.http.*
import xdm.core.network.http.impl.*
import xdm.core.util.*
import java.io.FileInputStream
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
                proxyUser = key.proxyUser, proxyPassword = key.proxyPass
            ).also {
                cachedClient = it
                cachedClientKey = key
            }
        }
    private val hslExt = listOf("mpegurl", ".m3u8", "m3u8")

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

    /** Reads the downloaded manifest and always removes the temp file afterwards. */
    private fun <T> useManifestFile(path: String, block: (String) -> T): T {
        try {
            return block(path)
        } finally {
            runCatching { Files.deleteIfExists(Paths.get(path)) }
                .onFailure { Logger.error("XDM", "Could not delete manifest temp file $path", it) }
        }
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

    private fun isHLSUrl(url: String?): Boolean = StringUtils.containsIgnoreCase(url, "m3u8")

    private fun isDash(contentType: String?): Boolean = StringUtils.containsIgnoreCase(contentType, "dash")

    private fun isDashUrl(url: String?): Boolean = StringUtils.containsIgnoreCase(url, ".mpd")

    fun processMediaMessage(msg: ExtensionMessage) {
        val responseHeaders =
            msg.responseHeaders?.map { entry -> entry.key to entry.value.map { it.value } }?.associate { it }
        val contentType = getHeader(CONTENT_TYPE, responseHeaders) ?: return
        // A malformed Content-Length must not abort the whole message.
        val contentLength = getHeader(CONTENT_LENGTH, responseHeaders)?.toLongOrNull()
        msg.url ?: return
        when {
            isDash(contentType) || isDashUrl(msg.url) -> mediaExecutor.execute { processDashVideo(msg) }
            isHLS(contentType) || isHLSUrl(msg.url) -> mediaExecutor.execute { processHLSVideo(msg) }
            isHttpVideo(msg.url, contentType, contentLength, msg.tabId, msg.tabUrl) -> processHttpVideo(
                msg,
                contentType,
                contentLength ?: -1L
            )
        }
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
        val ext = when {
            StringUtils.containsIgnoreCase(type, "video/mp4") -> "mp4"
            StringUtils.containsIgnoreCase(type, "video/x-flv") -> "flv"
            StringUtils.containsIgnoreCase(type, "video/webm") -> "mkv"
            StringUtils.containsIgnoreCase(type, "matroska") || StringUtils.containsIgnoreCase(type, "mkv") -> "mkv"
            type == "audio/mpeg" || StringUtils.containsIgnoreCase(type, "audio/mp3") -> "mp3"
            StringUtils.containsIgnoreCase(type, "audio/aac") -> "aac"
            StringUtils.containsIgnoreCase(type, "audio/mp4") -> "m4a"
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
        val http = HttpDownloadTaskInfo(
            id = CoreUtils.uniqueId(),
            url = url,
            fileName = fileNameWithExt(msg, ext),
            respectFileName = true,
            cookie = msg.cookie,
            headers = msg.requestHeaders,
            origin = getHeader("Referer", msg.requestHeaders),
            autoCategorize = true,
            defaultDownloadFolder = AppContext.defaultDownloadFolder,
            maxPiece = 8,
            userSelectedDownloadFolder = null,
            authInfo = null,
            knownFileSize = msg.fileSize ?: getContentLength(respHeaders)
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
        val file = ManifestUtils.downloadManifestAsFile(
            httpClient, msg.url, headers, msg.cookie, AtomicBoolean(false)
        ) ?: return
        useManifestFile(file) { path -> parseDashManifest(msg, msg.url, path, headers) }
    }

    private fun parseDashManifest(
        msg: ExtensionMessage,
        url: String,
        path: String,
        headers: MutableMap<String, List<String>>,
    ) {
        FileInputStream(path).use { f ->
            // Resolve xlink remote elements (e.g. ad-insertion Periods) over HTTP, reusing the same
            // headers/cookie as the manifest fetch.
            val xlinkResolver = XlinkResolver { xlinkUrl ->
                ManifestUtils.downloadManifestBytes(httpClient, xlinkUrl, headers, msg.cookie, AtomicBoolean(false))
                    ?.toString(Charsets.UTF_8)
            }
            val entries = parseMpdManifest(f, url, xlinkResolver)
            if (entries.isEmpty()) {
                Logger.info("Unable to parse manifest")
                return
            }
            for (plc in entries) {
                val video = plc.video
                val audio = plc.audio
                if (video != null && audio != null) {
                    val fileExt = if (video.mimeType.contains("mp4") && audio.mimeType.contains("mp4")) "mp4" else "mkv"
                    val dashDownloadTaskInfo = DashDownloadTaskInfo(
                        id = CoreUtils.uniqueId(),
                        fileName = fileNameWithExt(msg, fileExt),
                        tempDir = AppContext.config.tempFolder,
                        respectFileName = true,
                        cookie = msg.cookie,
                        headers = msg.requestHeaders,
                        // The page the manifest was found on, so "Refresh link" has somewhere to go.
                        origin = msg.tabUrl ?: getHeader(REFERER, msg.requestHeaders),
                        autoCategorize = false,
                        defaultDownloadFolder = AppContext.defaultDownloadFolder,
                        userSelectedDownloadFolder = null,
                        maxPiece = 8,
                        authInfo = null,
                        url = url,
                        audioSegments = audio.segments,
                        videoSegments = video.segments,
                        audioMime = audio.mimeType,
                        videoMime = video.mimeType,
                    )

                    AppContext.videoTracker.addVideoDash(
                        listOf(
                            Pair(
                                dashDownloadTaskInfo, StreamingVideoDisplayInfo(
                                    quality = getDashDisplayInfo(video, audio),
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
        val file = ManifestUtils.downloadManifestAsFile(
            httpClient, msg.url, headers, msg.cookie, AtomicBoolean(false)
        ) ?: return
        // Read the playlist out of the temp file once. The old `Files.lines` iterators were never
        // closed, which leaked a file handle per parse and, on Windows, would block the delete.
        val lines = useManifestFile(file) { path -> Files.readAllLines(Paths.get(path), Charsets.UTF_8) }
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
                val hlsSource = toHlsSource(msg, secondaryUrl, primaryUrl, audioOnly, playlist.independent)
                AppContext.videoTracker.addVideoHls(
                    listOf(
                        Pair(
                            hlsSource, StreamingVideoDisplayInfo(
                                quality = playlist.getInfoString(),
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
    ): HlsDownloadTaskInfo {
        return HlsDownloadTaskInfo(
            id = CoreUtils.uniqueId(),
            fileName = fileNameWithExt(msg, "mp4"),
            tempDir = AppContext.config.tempFolder,
            respectFileName = true,
            cookie = msg.cookie,
            headers = msg.requestHeaders,
            // The page the manifest was found on, so "Refresh link" has somewhere to go.
            origin = msg.tabUrl ?: getHeader(REFERER, msg.requestHeaders),
            autoCategorize = false,
            defaultDownloadFolder = AppContext.defaultDownloadFolder,
            userSelectedDownloadFolder = null,
            maxPiece = 8,
            authInfo = null,
            url = videoUrl,
            audioUrl = audioUrl,
            audioOnly = audioOnly,
            independent,
        )
    }
}