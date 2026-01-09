package xdm.integration

import xdm.app.AppContext
import xdm.app.models.StreamingVideoDisplayInfo
import xdm.core.*
import xdm.core.downloaders.hls.*
import xdm.core.downloaders.http.*
import xdm.core.media.parser.hls.*
import xdm.core.net.*
import xdm.core.network.http.*
import xdm.core.network.http.impl.*
import xdm.core.util.*
import java.net.URI
import java.nio.*
import java.nio.file.*
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


object VideoHelper {
    private val httpClient: PoolingHttpClientImpl = PoolingHttpClientImpl(10)
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

    fun processVideoDownloadMessage(msg: ExtensionMessage) {
        val vid=msg.vid
        val name:String
        val type: String
        val contentType: String

    }

    fun processMediaMessage(msg: ExtensionMessage) {
        val responseHeaders =
            msg.responseHeaders?.map { entry -> entry.key to entry.value.map { it.value } }?.associate { it }
        val contentType = getHeader(CONTENT_TYPE, responseHeaders) ?: return
        val contentLength = getHeader(CONTENT_LENGTH, responseHeaders)?.toLong()
        msg.url ?: return
        if (isHLS(contentType) || isHLSUrl(msg.url)) {
            thread { processHLSVideo(msg) }
            return
        }
        if (isHttpVideo(
                msg.url, contentType, contentLength, msg.tabId
            )
        ) {
            processHttpVideo(msg, contentType, contentLength ?: -1L)
            return
        }
    }

    private fun processHttpVideo(msg: ExtensionMessage, type: String?, len: Long) {
        if (isFragment(getHeader(REFERER, msg.requestHeaders))) {
            Logger.info("${msg.url} is fragment, ignoring")
            return
        }
        val url = msg.url!!.lowercase(Locale.ENGLISH)
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
        val http = HttpSource().apply {
            this.id = UniqueID.get()
            this.url = msg.url
            this.headers = HeaderCollection(msg.requestHeaders)
            this.fileName = getFileName(msg)
            this.cookies = msg.cookie
            this.fileSize = len
        }
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

    private fun processHLSVideo(msg: ExtensionMessage) {
        Logger.info("Downloading HLS manifest:  ${msg.url}")
        msg.url ?: return
        msg.tabId?.let { m3u8MpdTabs.add(it) }
        getHeader(REFERER, msg.requestHeaders)?.let { referersToSkip.add(generate64BitHash(it)) }
        val headers = HeaderCollection(msg.requestHeaders)
        val acceptHeaderAdded = msg.requestHeaders?.keys?.any { StringUtils.equalsIgnoreCase(it, "accept") } ?: false
        if (!acceptHeaderAdded) {
            headers.addHeader(ACCEPT, "*/*")
        }
        val file = ManifestUtils.downloadManifest(
            httpClient, msg.url, headers, msg.cookie, AtomicBoolean(false)
        ) ?: return
        val lines = Files.readAllLines(Paths.get(file), Charsets.UTF_8)
        if (HlsParser.isMasterPlaylist(lines)) {
            Logger.info("Master playlist found")
            val playlists: List<HlsMasterPlaylist> = HlsParser.parseMasterPlaylist(lines, msg.url) ?: return
            Logger.info("Items in playlist: ${playlists.size}")
            for (playlist in playlists) {
                val hlsSource =
                    HlsSource(playlist.audioPlaylist?.toString(), playlist.videoPlaylist?.toString()).apply {
                        this.id = UniqueID.get()
                        this.headers = HeaderCollection(msg.requestHeaders)
                        this.cookies = msg.cookie
                        this.fileName = getFileName(msg)
                    }
                AppContext.videoTracker.addVideoHls(
                    listOf(
                        Pair(
                            hlsSource, StreamingVideoDisplayInfo(
                                quality = playlist.quality,
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
            val playlist = HlsParser.parseMediaSegments(lines, msg.url) ?: return
            if (playlist.mediaSegments?.isEmpty() == true) {
                return
            }
            val hlsSource = HlsSource(null, msg.url).apply {
                this.id = UniqueID.get()
                this.headers = HeaderCollection(msg.requestHeaders)
                this.cookies = msg.cookie
                this.fileName = getFileName(msg)
            }
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


    fun generate64BitHash(input: String): Long {
        val md = MessageDigest.getInstance("SHA-1")
        val sha1HashBytes = md.digest(input.toByteArray())

        // Take the first 8 bytes (64 bits) of the SHA-1 hash
        // and convert them into a long.
        val buffer: ByteBuffer = ByteBuffer.wrap(sha1HashBytes)
        return buffer.getLong()
    }
}