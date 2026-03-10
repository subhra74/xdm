package xdm.app

import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo

interface DownloadsController {
    fun stopDownload(id: Long)
    fun resumeDownload(id: Long)
    fun addHttpDownload(task: HttpDownloadTaskInfo)
    fun addVideoDownload(videoId: Long, fileName: String, folder: String?, autoSelectFolder: Boolean)
    fun addHlsDownload(task: HlsDownloadTaskInfo)
    fun addDashDownload(task: DashDownloadTaskInfo)
}