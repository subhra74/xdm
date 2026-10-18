package xdm.core.downloaders

import xdm.core.storage.LocalStorage
import xdm.core.storage.Storage
import xdm.core.storage.StorageFile

interface FileProvider {
    /** If user provided output folder then use that or decide based on file type */
    fun getTempDir(id: Long, url: String, contentType: String?, contentDisposition: String?): String

    /**
     * Resolves target folders (`targetFolder`, a batch's folder) to the place the bytes are written:
     * the local file system on desktop, the Storage Access Framework on Android.
     */
    val storage: Storage get() = LocalStorage

    /**
     * Publishes the finished [part] file under its final name, normally a rename within [part]'s folder.
     * [CommitResult.Success.outputDir] is that folder's [xdm.core.storage.StorageFolder.location].
     */
    fun commitOutputFile(id: Long, part: StorageFile, downloadType: DownloadType): CommitResult

    /**
     * Where a streaming download should write its assembled output ([ext] includes the dot): a partial
     * file inside the final destination folder, so [commitOutputFile] is a same-folder rename even
     * when the temp folder is on another drive. Stable for a download id. Null means "write it in the
     * task's temp folder".
     */
    fun outputFile(id: Long, downloadType: DownloadType, ext: String): StorageFile? = null
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