package xdm.core.downloaders


interface FileProvider {
    /** If user provided output folder then use that or decide based on file type */
    fun getTempDir(id: Long, url: String, contentType: String?, contentDisposition: String?): String

    /** Rename or copy temp file to final output file */
    fun commitOutputFile(id: Long, tmpFilePath: String, downloadType: DownloadType): CommitResult

    /**
     * Where a streaming download should write its assembled output ([ext] includes the dot): a partial
     * file inside the final destination folder, so [commitOutputFile] is a same-folder rename even
     * when the temp folder is on another drive. The path is stable for a download id. Null means
     * "write it in the task's temp folder".
     */
    fun outputFilePath(id: Long, downloadType: DownloadType, ext: String): String? = null
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