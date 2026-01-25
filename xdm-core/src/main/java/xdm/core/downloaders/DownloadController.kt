//package xdm.core.downloaders
//
//
//import xdm.core.downloaders.web.http.*
//import xdm.core.downloaders.web.http.Chunk
//import xdm.core.network.http.impl.HttpClientImpl
//import xdm.core.util.CoreUtils
//import java.util.concurrent.ConcurrentHashMap
//import java.util.concurrent.atomic.AtomicBoolean
//import java.util.concurrent.atomic.AtomicLong
//
//
//class DownloadController(
//    private val host: DownloadHost
//) {
//    private val httpSessions = ConcurrentHashMap<Long, HttpChunkController>()
//    fun addHttpDownload(task: HttpDownloadTaskInfo) {
//        val context = HttpTaskContext(
//            id = task.id,
//            chunks = ConcurrentHashMap<Long, Chunk>(),
//            init = AtomicBoolean(false),
//            totalSize = null,
//            downloaded = AtomicLong(0),
//            url = task.url,
//            contentType = null,
//            headers = task.headers,
//            cookie = task.cookie,
//            httpClient = HttpClientImpl(100),
//            stopFlag = AtomicBoolean(false),
//            completed = AtomicBoolean(false),
//            tempFileCreated = AtomicBoolean(false),
//            tempFileName = "${CoreUtils.uniqueId()}.tmp",
//            diskError = AtomicBoolean(false),
//            downloadHost = host,
//        ).apply { tempFolder = task.defaultDownloadFolder }
//
//        val controller = HttpChunkController(
//            context,
//        )
//        controller.start()
//    }
//}