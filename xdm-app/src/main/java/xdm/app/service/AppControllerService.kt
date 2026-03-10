package xdm.app.service

import xdm.core.downloaders.HttpDownloadTaskInfo

interface AppControllerService {
    fun run(args: Array<String>)

    fun showAppWindow()

    fun hideDownloadProgressWindow(id: Long)

    fun showDownloadProgressWindow(id: Long)

    fun showDownloadCompleteWindow(id: Long, folder: String, fileName: String)

    fun updateDownloadInView(id: Long)

    fun addDownloadInView(id: Long)

    //  void updateProgressWindow(long id, AbstractDownloader downloader);
    fun showErrorInProgressWindow(id: Long, errorMessage: String)

    fun addDownload(metadata: HttpDownloadTaskInfo?)

    fun addVideoDownload(vid: Long, fileName: String, fileSize: Long?, fileType: String?)
}
