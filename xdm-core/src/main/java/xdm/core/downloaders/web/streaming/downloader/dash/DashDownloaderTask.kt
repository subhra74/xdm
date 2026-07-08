package xdm.core.downloaders.web.streaming.downloader.dash

import xdm.core.CoreConfig
import xdm.core.downloaders.*
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.loadDashState
import xdm.core.downloaders.web.saveState
import xdm.core.downloaders.web.streaming.downloader.DashTaskContext
import xdm.core.downloaders.web.streaming.downloader.StreamingChunk
import xdm.core.downloaders.web.streaming.downloader.StreamingDownloaderTask
import xdm.core.media.muxer.Muxer
import xdm.core.network.http.PoolingHttpClient
import xdm.core.util.CoreUtils
import java.util.concurrent.atomic.AtomicReference

fun makeContext(
    taskInfo: DashDownloadTaskInfo, http: PoolingHttpClient, host: DownloadHost, configDir: String,
): DashTaskContext {
    loadDashState(id = taskInfo.id, configDir = configDir, http = http, host = host).onSuccess { return it }
    return DashTaskContext(
        id = taskInfo.id,
        chunks = taskInfo.audioSegments.mapIndexed { index, s ->
            StreamingChunk(
                id = CoreUtils.uniqueId(),
                sequence = index.toLong(),
                status = AtomicReference(ChunkStatus.Ready),
                url = s.url.toString(),
                keyUrl = null,
                iv = null,
                byteRange = s.range,
                tag = "AUDIO",
                error = AtomicReference(null),
                fileHandle = AtomicReference(null),
                encrypted = false
            )
        } + taskInfo.videoSegments.mapIndexed { index, s ->
            StreamingChunk(
                id = CoreUtils.uniqueId(),
                sequence = index.toLong(),
                status = AtomicReference(ChunkStatus.Ready),
                url = s.url.toString(),
                keyUrl = null,
                iv = null,
                byteRange = s.range,
                tag = "VIDEO",
                error = AtomicReference(null),
                fileHandle = AtomicReference(null),
                encrypted = false
            )
        },
        httpClient = http,
        downloadHost = host,
        tempFolder = taskInfo.tempDir,
        tempFileName = "${taskInfo.id}.mp4",
        url = taskInfo.url,
        headers = taskInfo.headers,
        cookie = taskInfo.cookie,
        audioMime = taskInfo.audioMime,
        videoMime = taskInfo.videoMime,
        hasSeparateStreams = true,
    )
}

//fun loadContext(
//    id: Long,
//    configDir: String,
//    http: PoolingHttpClient,
//    host: DownloadHost,
//) = loadDashState(id = id, configDir = configDir, http = http, host = host).getOrThrow()

class DashDownloaderTask : StreamingDownloaderTask {
//    constructor(
//        id: Long,
//        configDir: String,
//        http: PoolingHttpClient,
//        host: DownloadHost,
//        muxer: Muxer,
//        config: CoreConfig
//    ) : super(
//        loadContext(id, configDir, http, host),
//        configDir,
//        muxer,
//        config
//    )

    constructor(
        taskInfo: DashDownloadTaskInfo,
        http: PoolingHttpClient,
        muxer: Muxer,
        host: DownloadHost,
        configDir: String,
        config: CoreConfig
    ) : super(makeContext(taskInfo, http, host, configDir), configDir, muxer, config)

    override fun initDownload(): DownloadStatusInfo.InitInfo? {
        return DownloadStatusInfo.InitInfo(
            id = context.id,
            url = (context as DashTaskContext).url,
            isRedirect = false,
            fileSize = null,
            contentDisposition = null,
            contentType = null,
            videoExt = fileExt()
        )
    }

    override fun fileExt(): String =
        if ((context as DashTaskContext).videoMime.contains("mp4") && context.audioMime.contains("mp4")) ".mp4" else ".mkv"

    override fun downloadType(): DownloadType = DownloadType.Dash

    override fun saveContext() {
        synchronized(this) {
            saveState(context as DashTaskContext, configDir)
        }
    }

    override fun isIndependentSegment() = false

    override fun postProcessChunks() {
        //NO op
    }
}