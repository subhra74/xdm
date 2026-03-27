package xdm.core.downloaders


interface FileProvider {
    /** If user provided output folder then use that or decide based on file type */
    fun getTempDir(id: Long, url: String, contentType: String?, contentDisposition: String?): String

    /** Rename or copy temp file to final output file */
    fun commitOutputFile(id: Long, tmpFilePath: String, downloadType: DownloadType): CommitResult
}

interface DownloadHost : FileProvider {
    fun onDownloadActivated(id: Long)
    fun onDownloadInit(data: DownloadStatusInfo.InitInfo, downloadType: DownloadType)
    fun onDownloadProgress(event: DownloadStatusInfo.ProgressInfo)
    fun onAssembleStart(id: Long)
    fun onAssembleProgress(event: DownloadStatusInfo.AssembleInfo)
    fun onDownloadSuccess(event: DownloadStatusInfo.FinalInfo)
    fun onDownloadFailed(id: Long, error: DownloadError)
    fun onDownloadPaused(id: Long, event: PauseEvent)
    val appDir: String
    val applySpeedLimit: Boolean
    val speedLimit: Int
}