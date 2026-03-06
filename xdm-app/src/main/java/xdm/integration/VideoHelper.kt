package xdm.integration

import xdm.app.AppContext
import xdm.app.models.StreamingVideoDisplayInfo
import xdm.core.*
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.streaming.manifest.dash.Representation
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest
import xdm.core.downloaders.web.streaming.manifest.hls.HlsMasterPlaylist
import xdm.core.downloaders.web.streaming.manifest.hls.HlsParser
import xdm.core.downloaders.web.streaming.manifest.hls.getInfoString
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


object VideoHelper {
    private val httpClient: PoolingHttpClient = HttpClientImpl(10)
    private val hslExt = listOf("mpegurl", ".m3u8", "m3u8")
    private val m3u8MpdTabs = Collections.synchronizedSet(mutableSetOf<String>())
    private val suspectedMp4Fragments = Collections.synchronizedSet(mutableSetOf<String>())
    private val referersToSkip = Collections.synchronizedSet(mutableSetOf<Long>())

    private fun isHLS(contentType: String?): Boolean = hslExt.any { StringUtils.containsIgnoreCase(contentType, it) }

    private fun isHttpVideo(url: String, contentType: String?, size: Long?, tabId: String?): Boolean {
        size?.let {
            if (size > 0 && size < AppContext.config.minVideoSize * 1024) {
                return false
            }
        }
        tabId?.let {
            if (m3u8MpdTabs.contains(tabId)) {
                return false
            }
        }
        if (StringUtils.containsIgnoreCase(url, "init.mp4")) {
            suspectedMp4Fragments.add(URI.create(url).resolve(".").toString())
            return false
        }
        if (suspectedMp4Fragments.contains(URI.create(url).resolve(".").toString())) {
            return false
        }
        contentType ?: false
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
        val contentLength = getHeader(CONTENT_LENGTH, responseHeaders)?.toLong()
        msg.url ?: return
        when {
            isDash(contentType) || isDashUrl(msg.url) -> thread { processDashVideo(msg) }
            isHLS(contentType) || isHLSUrl(msg.url) -> thread { processHLSVideo(msg) }
            isHttpVideo(msg.url, contentType, contentLength, msg.tabId) -> processHttpVideo(
                msg,
                contentType,
                contentLength ?: -1L
            )
        }
    }

    private fun processHttpVideo(msg: ExtensionMessage, type: String?, len: Long) {
        processHttpVideo(msg, type, len, msg.url!!)
    }

    private fun processHttpVideo(msg: ExtensionMessage, type: String?, len: Long, url: String) {
        if (isFragment(getHeader(REFERER, msg.requestHeaders))) {
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

        val respHeaders =
            msg.responseHeaders?.map { entry -> entry.key to entry.value.map { it.value } }?.associate { it }
        val http = HttpDownloadTaskInfo(
            id = UniqueID.get(),
            url = url,
            fileName = getFileName(msg),
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
                        quality = "[$ext] " + (if (len > 0) FormatUtilities.formatSize(len.toDouble()) else ""),
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
            if (referersToSkip.contains(hash)) {
                return true
            }
        }
        return false
    }

    private fun getFileName(msg: ExtensionMessage): String =
        FileUtils.sanitizeFileName(msg.file ?: msg.tabTile ?: FileUtils.getFileName(msg.url))

    private fun processDashVideo(msg: ExtensionMessage) {
        Logger.info("Processing DASH manifest:  ${msg.url}")
        msg.url ?: return
        msg.tabId?.let { m3u8MpdTabs.add(it) }
        val headers = msg.requestHeaders ?: HashMap<String, List<String>>()
        getHeader(REFERER, headers)?.let { referersToSkip.add(generate64BitHash(it)) }
        val acceptHeaderAdded = headers.keys.any { StringUtils.equalsIgnoreCase(it, "accept") }
        if (!acceptHeaderAdded) {
            headers["ACCEPT"] = mutableListOf("*/*")
        }
        val file = ManifestUtils.downloadManifestAsFile(
            httpClient, msg.url, headers, msg.cookie, AtomicBoolean(false)
        ) ?: return
        FileInputStream(file).use { f ->
            val entries = parseMpdManifest(f, msg.url)
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
                        id = UniqueID.get(),
                        fileName = getFileName(msg) + "." + fileExt,
                        tempDir = AppContext.appConfig.tempDir,
                        respectFileName = true,
                        cookie = msg.cookie,
                        headers = msg.requestHeaders,
                        origin = null,
                        autoCategorize = false,
                        defaultDownloadFolder = AppContext.defaultDownloadFolder,
                        userSelectedDownloadFolder = null,
                        maxPiece = 8,
                        authInfo = null,
                        url = msg.url,
                        audioSegments = audio.segments.map { it.toString() },
                        videoSegments = video.segments.map { it.toString() },
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
                    val mimeType = video?.mimeType ?: audio!!.mimeType
                    val segments = (video?.segments ?: audio!!.segments)
                    if (segments.isNotEmpty()) {
                        processHttpVideo(msg, mimeType, -1, segments[0].toString())
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

    private fun lineIter(file: String): Iterator<String> {
        return Files.lines(Paths.get(file), Charsets.UTF_8).iterator()
    }

    private fun processHLSVideo(msg: ExtensionMessage) {
        Logger.info("Downloading HLS manifest:  ${msg.url}")
        msg.url ?: return
        msg.tabId?.let { m3u8MpdTabs.add(it) }
        val headers = msg.requestHeaders ?: HashMap<String, List<String>>()
        getHeader(REFERER, headers)?.let { referersToSkip.add(generate64BitHash(it)) }
        val acceptHeaderAdded = headers.keys.any { StringUtils.equalsIgnoreCase(it, "accept") }
        if (!acceptHeaderAdded) {
            headers["ACCEPT"] = mutableListOf("*/*")
        }
        val file = ManifestUtils.downloadManifestAsFile(
            httpClient, msg.url, headers, msg.cookie, AtomicBoolean(false)
        ) ?: return
        val lines = Files.readAllLines(Paths.get(file), Charsets.UTF_8)
        if (HlsParser.isMasterPlaylist(lineIter(file))) {
            Logger.info("Master playlist found")
            val playlists: List<HlsMasterPlaylist> =
                HlsParser.parseMasterPlaylist(lineIter(file), msg.url).getOrNull() ?: return
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
            fileName = getFileName(msg) + ".mp4",
            tempDir = AppContext.appConfig.tempDir,
            respectFileName = true,
            cookie = msg.cookie,
            headers = msg.requestHeaders,
            origin = null,
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