package xdm.core.downloaders.web.streaming.downloader.hls

import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.StreamingDownloadTaskInfo
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.streaming.downloader.HlsTaskContext
import xdm.core.downloaders.web.streaming.downloader.StreamingChunk
import xdm.core.downloaders.web.streaming.downloader.StreamingDownloader
import xdm.core.downloaders.web.streaming.manifest.hls.HlsParser
import xdm.core.downloaders.web.streaming.manifest.hls.HlsPlaylist
import xdm.core.media.muxer.Muxer
import xdm.core.network.http.PoolingHttpClient
import xdm.core.util.CoreUtils
import xdm.core.util.Logger
import xdm.core.util.ManifestUtils.downloadManifestBytes
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun makeContext(
    taskInfo: StreamingDownloadTaskInfo, http: PoolingHttpClient, host: DownloadHost
) = HlsTaskContext(
    id = taskInfo.id,
    chunks = ArrayList(),
    httpClient = http,
    downloadHost = host,
    tempFolder = taskInfo.tempDir,
    tempFileName = "${taskInfo.id}.mp4"
)

class HlsDownloader(
    private val taskInfo: HlsDownloadTaskInfo,
    http: PoolingHttpClient,
    muxer: Muxer,
    host: DownloadHost,
) : StreamingDownloader(makeContext(taskInfo, http, host), muxer) {
    private val keyCache = ConcurrentHashMap<String, ByteArray>()

    override fun initDownload(): DownloadStatusInfo.InitInfo? {
        context.hasSeparateStreams = taskInfo.audioUrl != null
        val latch = CountDownLatch(if (context.hasSeparateStreams) 2 else 1)
        val videoManifestContent = AtomicReference<Iterator<String>>()
        val audioManifestContent = AtomicReference<Iterator<String>>()
        val error = AtomicBoolean(false)
        loadManifest(taskInfo.url, videoManifestContent, latch, error)
        if (context.hasSeparateStreams) {
            loadManifest(taskInfo.audioUrl!!, audioManifestContent, latch, error)
        }
        try {
            latch.await()
            val (videoPlaylist, audioPlaylist) = parseManifest(videoManifestContent, audioManifestContent)
            retrieveKeys(videoPlaylist, audioPlaylist)
            context.chunks.ensureCapacity(videoPlaylist.mediaSegments.size + (audioPlaylist?.mediaSegments?.size ?: 0))
            context.chunks.addAll(videoPlaylist.mediaSegments.mapIndexed { index, ms ->
                StreamingChunk(
                    id = CoreUtils.uniqueId(),
                    sequence = index.toLong(),
                    status = AtomicReference(ChunkStatus.Ready),
                    url = ms.url,
                    keyUrl = ms.keyUrl?.toString(),
                    iv = ms.iv,
                    byteRange = ms.byteRange,
                    tag = "VIDEO",
                    error = AtomicReference(null),
                    fileHandle = AtomicReference(null)
                )
            })
            val count = videoPlaylist.mediaSegments.size
            audioPlaylist?.let {
                context.chunks.addAll(it.mediaSegments.mapIndexed { index, ms ->
                    StreamingChunk(
                        id = CoreUtils.uniqueId(),
                        sequence = count + index.toLong(),
                        status = AtomicReference(ChunkStatus.Ready),
                        url = ms.url,
                        keyUrl = ms.keyUrl?.toString(),
                        iv = ms.iv,
                        byteRange = ms.byteRange,
                        tag = "AUDIO",
                        error = AtomicReference(null),
                        fileHandle = AtomicReference(null)
                    )
                })
            }
            return DownloadStatusInfo.InitInfo(
                id = context.id,
                url = taskInfo.url,
                isRedirect = false,
                fileSize = null,
                contentDisposition = null,
                contentType = null,
                videoExt = fileExt()
            )
        } catch (e: Exception) {
            Logger.error("XDM", "Error downloading manifests", e)
            error.set(true)
            return null
        }
    }

    override fun fileExt(): String = ".mp4"

    private fun parseManifest(
        videoManifestContent: AtomicReference<Iterator<String>>, audioManifestContent: AtomicReference<Iterator<String>>
    ): Pair<HlsPlaylist, HlsPlaylist?> {
        val videoPlaylist = HlsParser.parseMediaSegments(videoManifestContent.get(), taskInfo.url).getOrThrow()
        var audioPlaylist: HlsPlaylist? = null
        taskInfo.audioUrl?.let {
            audioPlaylist = HlsParser.parseMediaSegments(audioManifestContent.get(), it).getOrThrow()
        }
        return Pair(videoPlaylist, audioPlaylist)
    }

    private fun retrieveKeys(
        videoPlayList: HlsPlaylist, audioPlayList: HlsPlaylist?
    ) {
        val error = AtomicBoolean(false)
        val keyUrls = HashSet<String>()
        if (videoPlayList.encrypted) {
            keyUrls.addAll(videoPlayList.mediaSegments.map { it.url })
        }
        if (audioPlayList != null && audioPlayList.encrypted) {
            keyUrls.addAll(audioPlayList.mediaSegments.map { it.url })
        }
        if (context.stopFlag.get()) {
            return
        }
        if (keyUrls.isEmpty()) return
        val counter = CountDownLatch(keyUrls.size)
        for (keyUrl in keyUrls) {
            executorService.submit {
                try {
                    if (!error.get() && !context.stopFlag.get()) {
                        downloadManifestBytes(
                            context.httpClient, keyUrl, taskInfo.headers, taskInfo.cookie, context.stopFlag
                        )?.let { keyCache[keyUrl] = it }
                    }
                } catch (e: Exception) {
                    Logger.error("XDM", "Error downloading keys", e)
                    error.set(true)
                } finally {
                    counter.countDown()
                }
            }
        }
        if (error.get()) {
            throw IOException("Unable to get keys")
        }
    }
}