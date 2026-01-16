package xdm.core.downloaders.web

sealed interface DownloadStatusInfo {
    data class InitInfo(
        val id: Long,
        val url: String,
        val isRedirect: Boolean,
        val fileSize: Long?,
        val newFileName: String?,
        val contentType: String?,
    )

    data class ProgressInfo(
        val id: Long,
        val progress: Float,
        val speed: Float,
        val eta: Long,
        val downloaded: Long,
    )

    data class AssembleInfo(
        val id: Long,
        val progress: Float,
    )

    data class FinalInfo(
        val id: Long,
        val fileSize: Long,
        val finalFileName: String,
        val finalOutputFolder: String,
    )
}

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

interface DownloadHost {
    fun getDefaultDownloadFolder()
    fun getFinalFileName(fileName: String, ext: String?, contentType: String?)
    fun getDownloadFolder(fileName: String, ext: String?, contentType: String?)
}

interface DownloadTask {
    val outputFileName: String
    val outputFolder: String?
    val autoSelectExt: Boolean
    val selectFolderByFileType: Boolean

    fun startDownload(
        onDownloadStart: (Long) -> Unit,
        onDownloadInit: (DownloadStatusInfo.InitInfo) -> Unit,
        onDownloadProgress: (DownloadStatusInfo.ProgressInfo) -> Unit,
        onAssembleStart: (Long) -> Unit,
        onAssembleProgress: (DownloadStatusInfo.AssembleInfo) -> Unit,
        onDownloadSuccess: (DownloadStatusInfo.FinalInfo) -> Unit,
        onDownloadFailed: (Long, DownloadError) -> Unit,
        onDownloadPaused: (Long, PauseEvent) -> Unit,
    )

    fun stopDownload()
}