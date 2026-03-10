package xdm.app.service.impl

import xdm.app.AppContext.db
import xdm.app.service.AppControllerService
import xdm.app.ui.screens.AppWindow
import xdm.app.ui.screens.NewDownloadWindow
import xdm.app.ui.screens.NewVideoDownloadWindow
import xdm.app.utils.UiHelper.createSVGIcon
import xdm.app.utils.createTray
import xdm.core.downloaders.HttpDownloadTaskInfo
import javax.swing.SwingUtilities

class AppControllerServiceImpl : AppControllerService {
    private lateinit var appWindow: AppWindow

    override fun run(args: Array<String>) {
        SwingUtilities.invokeLater {
            val image = createSVGIcon("xdm-logo.svg", 256).image
            appWindow = AppWindow(image)
            createTray(image)
            showAppWindow()
        }
    }

    override fun showAppWindow() {
        appWindow.isVisible = true
        appWindow.toFront()
    }

    override fun hideDownloadProgressWindow(id: Long) {}

    override fun showDownloadProgressWindow(id: Long) {}

    override fun showDownloadCompleteWindow(id: Long, folder: String, fileName: String) {}

    override fun updateDownloadInView(id: Long) {
        val index = db.indexById(id)
        if (index != null) {
            SwingUtilities.invokeLater { appWindow.updateDownloadInView(index) }
        }
    }

    override fun addDownloadInView(id: Long) {
        val index = db.indexById(id)
        if (index != null) {
            SwingUtilities.invokeLater { appWindow.addDownloadInView(index) }
        }
    }

    //  @Override
    //  public void updateProgressWindow(long id, AbstractDownloader downloader) {}
    override fun showErrorInProgressWindow(id: Long, errorMessage: String) {}

    override fun addDownload(downloadInfo: HttpDownloadTaskInfo?) {
        // TODO: Check if link refresh is searching for download
        // TODO: Check if download window needs to be shown, or directly start the download
        SwingUtilities.invokeLater {
            showNewDownloadWindowInternal(downloadInfo)
        }
    }

    override fun addVideoDownload(vid: Long, fileName: String, fileSize: Long?, contentType: String?) {
        // TODO: Check if download window needs to be shown, or directly start the download
        SwingUtilities.invokeLater {
            showNewVideoDownloadWindowInternal(vid, fileName, fileSize, contentType)
        }
    }

    private fun showNewDownloadWindowInternal(downloadInfo: HttpDownloadTaskInfo?) {
        val dlg = NewDownloadWindow()
        dlg.showWindow(downloadInfo)
    }

    private fun showNewVideoDownloadWindowInternal(
        vid: Long, fileName: String, fileSize: Long?, contentType: String?
    ) {
        val dlg = NewVideoDownloadWindow()
        dlg.showWindow(vid, fileName, fileSize, contentType)
    }
}
