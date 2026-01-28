package xdm.core.downloaders

import xdm.core.network.http.HeaderMap

enum class DownloadError {
    NetworkError,
    InvalidResponse,
    SessionExpired,
    DiskSpaceError,
    InternalError,
    ResumeNotSupported,
    MuxError,
}

enum class PauseEvent {
    PausedByUser,
    PausedBySystem,
}

interface DownloaderTask {
    fun start()
    fun stop()
    fun resume()
}

data class HttpDownloadTaskInfo(
    var id: Long,
    var url: String,
    var fileName: String,
    var respectFileName: Boolean,
    var cookie: String?,
    var headers: HeaderMap?,
    var origin: String?,
    var autoCategorize: Boolean,
    var defaultDownloadFolder: String,
    var userSelectedDownloadFolder: String?,
    var maxPiece: Int,
    var authInfo: AuthInfo?,
)

abstract class StreamingDownloadTaskInfo(
    open val id: Long,
    open var fileName: String,
    open var tempDir: String,
    open var respectFileName: Boolean,
    open var cookie: String?,
    open var headers: HeaderMap?,
    open var origin: String?,
    open var autoCategorize: Boolean,
    open var defaultDownloadFolder: String,
    open var userSelectedDownloadFolder: String?,
    open var maxPiece: Int,
    open var authInfo: AuthInfo?,
)

data class HlsDownloadTaskInfo(
    override val id: Long,
    override var fileName: String,
    override var tempDir: String,
    override var respectFileName: Boolean,
    override var cookie: String?,
    override var headers: HeaderMap?,
    override var origin: String?,
    override var autoCategorize: Boolean,
    override var defaultDownloadFolder: String,
    override var userSelectedDownloadFolder: String?,
    override var maxPiece: Int,
    override var authInfo: AuthInfo?,
    var url: String,
    var audioUrl: String?,
    var audioOnly: Boolean = false,
) : StreamingDownloadTaskInfo(
    id,
    fileName,
    tempDir,
    respectFileName,
    cookie,
    headers,
    origin,
    autoCategorize,
    defaultDownloadFolder,
    userSelectedDownloadFolder,
    maxPiece,
    authInfo
)

sealed interface DownloadStatusInfo {
    data class InitInfo(
        val id: Long,
        val url: String,
        val isRedirect: Boolean,
        val fileSize: Long?,
        val contentDisposition: String?,
        val contentType: String?,
        val videoExt: String? = null
    )

    data class ProgressInfo(
        var id: Long = 0,
        var progress: Int = 0,
        var speed: Float = 0.0f,
        var eta: Long = 0,
        var downloaded: Long = 0,
    )

    data class AssembleInfo(
        val id: Long,
        val progress: Int,
    )

    data class FinalInfo(
        val id: Long,
        val fileSize: Long,
        val finalFileName: String,
        val finalOutputFolder: String,
    )
}

sealed interface CommitResult {
    data class Success(val fileName: String, val outputDir: String) : CommitResult
    data object Failed : CommitResult
}