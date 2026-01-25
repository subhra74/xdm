package xdm.core.downloaders

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

data class HttpDownloadTaskInfo(
    var id: Long,
    var url: String,
    var fileName: String,
    var respectFileName: Boolean,
    var cookie: String?,
    var headers: Map<String, List<String>>?,
    var origin: String?,
    var autoCategorize: Boolean,
    var defaultDownloadFolder: String,
    var userSelectedDownloadFolder: String?,
    var maxPiece: Int,
    var authInfo: AuthInfo?,
)

sealed interface DownloadStatusInfo {
    data class InitInfo(
        val id: Long,
        val url: String,
        val isRedirect: Boolean,
        val fileSize: Long?,
        val contentDisposition: String?,
        val contentType: String?,
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