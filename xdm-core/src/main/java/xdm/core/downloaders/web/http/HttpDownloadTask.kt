package xdm.core.downloaders.web.http

import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.CoreUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HttpDownloadTask {
    fun start() {
//        val url = "https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-25.0.1-windows-x64-b268.52.zip"
        val url = "http://localhost:8080/Cyberduck-8.6.0.39818.zip"//"http://localhost:8080/dahliaOS-201215-efi.zip"
        val context = HttpTaskContext(
            chunks = ConcurrentHashMap<Long, Chunk>(),
            init = AtomicBoolean(false),
            totalSize = null,
            url = url,
            contentType = null,
            headers = null,
            cookie = null,
            HttpClientImpl(100),
            stopFlag = AtomicBoolean(false),
            completed = AtomicBoolean(false),
            errorCallback = {},
            outputFolder = AtomicReference({ "/Users/subhro/Desktop/tmp" }),
            finalFileName = AtomicReference({ "out.zip" }),
            tempFileCreated = AtomicBoolean(false),
            tempFileName = "${CoreUtils.uniqueId()}.part"
        )
        val controller = HttpChunkController(
            "/Users/subhro/Desktop/tmp",
            context,
            { "download complete" },
            time = System.currentTimeMillis()
        )
        controller.start()
    }
}

//
//import xdm.core.downloaders.AuthInfo
//import xdm.core.downloaders.Proxy
//import xdm.core.downloaders.web.DownloadError
//import xdm.core.downloaders.web.DownloadStatusInfo
//import xdm.core.downloaders.web.DownloadTask
//import xdm.core.downloaders.web.PauseEvent
//import xdm.core.network.http.PoolingHttpClient
//
//data class HeaderData(
//    val url: String,
//    val headers: Map<String, List<String>>?,
//    val cookie: String?,
//    val authInfo: AuthInfo?,
//    val proxy: Proxy
//)
//
//class HttpDownloadTask(
//    override val autoSelectExt: Boolean,
//    override val outputFileName: String,
//    override val outputFolder: String?,
//    override val selectFolderByFileType: Boolean,
//) : DownloadTask, ChunkController {
//    override fun startDownload(
//        onDownloadStart: (Long) -> Unit,
//        onDownloadInit: (DownloadStatusInfo.InitInfo) -> Unit,
//        onDownloadProgress: (DownloadStatusInfo.ProgressInfo) -> Unit,
//        onAssembleStart: (Long) -> Unit,
//        onAssembleProgress: (DownloadStatusInfo.AssembleInfo) -> Unit,
//        onDownloadSuccess: (DownloadStatusInfo.FinalInfo) -> Unit,
//        onDownloadFailed: (Long, DownloadError) -> Unit,
//        onDownloadPaused: (Long, PauseEvent) -> Unit
//    ) {
//        TODO("Not yet implemented")
//    }
//
//    override fun stopDownload() {
//        TODO("Not yet implemented")
//    }
//
//    override fun isFirstRequest(id: Long): Boolean? {
//        TODO("Not yet implemented")
//    }
//
//    override fun getPiece(id: Long): Chunk? {
//        TODO("Not yet implemented")
//    }
//
//    override fun getHeaderData(id: Long): HeaderData? {
//        TODO("Not yet implemented")
//    }
//
//    override fun getHttpClient(id: Long): PoolingHttpClient? {
//        TODO("Not yet implemented")
//    }
//
//    override fun onPieceConnected(id: Long) {
//        TODO("Not yet implemented")
//    }
//
//    override fun getChunkFile(id: Long): String? {
//        TODO("Not yet implemented")
//    }
//
//    override fun updateBytesDownloaded(id: Long, downloaded: Long) {
//        TODO("Not yet implemented")
//    }
//
//    override fun continueNextPiece(id: Long): Boolean? {
//        TODO("Not yet implemented")
//    }
//
//    override fun onPieceFailed(id: Long, error: DownloadError): PoolingHttpClient? {
//        TODO("Not yet implemented")
//    }
//
//    override fun onPieceFinished(id: Long) {
//        TODO("Not yet implemented")
//    }
//
//    override fun throttleIfNeeded(id: Long) {
//        TODO("Not yet implemented")
//    }
//
//}