package xdm.app

import xdm.app.AppContext.db
import xdm.app.I8N.text
import xdm.app.ui.components.MessageBox
import xdm.app.ui.screens.AppWindow
import xdm.app.ui.screens.DownloadCompleteWindow
import xdm.app.ui.screens.NewDownloadWindow
import xdm.app.ui.screens.NewVideoDownloadWindow
import xdm.app.ui.screens.ProgressWindow
import xdm.app.ui.screens.RefreshLinkWindow
import xdm.app.utils.createSVGIcon
import xdm.app.utils.createTray
import xdm.app.utils.openWebPage
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.SegmentProgress
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

interface IAppInstance {
    fun run(args: Array<String>)

    fun showAppWindow()

    fun showDownloadCompleteWindow(id: Long, folder: String, fileName: String, fileSize: Long)

    fun updateDownloadInView(id: Long)

    fun deleteDownloadInView(id: Long)

    fun deleteDownloadInView(index: Int)

    fun addDownloadInView(id: Long)

    fun addDownload(downloadInfo: HttpDownloadTaskInfo?)

    fun addVideoDownload(vid: Long, fileName: String, fileSize: Long?, fileType: String?)

    fun showProgressWindow(id: Long, fileName: String)

    fun hideProgressWindow(id: Long)

    fun showProgressError(id: Long, error: DownloadError)

    fun updateProgressWindow(
        id: Long,
        fileName: String?,
        downloaded: Long,
        size: Long,
        speed: Float,
        eta: Long,
        prg: Int,
        segData: Collection<SegmentProgress>
    )

    fun showRefreshWindow(id: Long)
}

class AppInstance : IAppInstance {
    private lateinit var appWindow: AppWindow
    private val prgWndMap = mutableMapOf<Long, ProgressWindow>()
    private var refreshLinkWindow: RefreshLinkWindow? = null

    override fun run(args: Array<String>) {
        runOnUIThread {
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

    override fun showDownloadCompleteWindow(id: Long, folder: String, fileName: String, fileSize: Long) {
        runOnUIThread { DownloadCompleteWindow().apply { setDetails(fileName, folder, fileSize) }.isVisible = true }
    }

    override fun updateDownloadInView(id: Long) {
        val index = db.indexById(id)
        if (index != null) {
            SwingUtilities.invokeLater { appWindow.updateDownloadInView(index) }
        }
    }

    override fun deleteDownloadInView(id: Long) {
        val index = db.indexById(id)
        if (index != null) {
            SwingUtilities.invokeLater { appWindow.deleteDownloadInView(index) }
        }
    }

    override fun deleteDownloadInView(index: Int) {
        if (SwingUtilities.isEventDispatchThread()) {
            appWindow.deleteDownloadInView(index)
        } else {
            SwingUtilities.invokeAndWait { appWindow.deleteDownloadInView(index) }
        }
    }

    override fun addDownloadInView(id: Long) {
        val index = db.indexById(id)
        if (index != null) {
            SwingUtilities.invokeLater { appWindow.addDownloadInView(index) }
        }
    }

    override fun addDownload(downloadInfo: HttpDownloadTaskInfo?) {
        if (AppContext.refreshLinkInProgress.get() && downloadInfo != null) {
            AppContext.downloader.updateDownloadInfo(AppContext.refreshLinkId.get(), downloadInfo)
            refreshLinkWindow?.dispose()
            MessageBox.show(appWindow, "XDM", text("SUCCESS_REFRESH"))
            return
        }
        // TODO: Check if download window needs to be shown, or directly start the download
        SwingUtilities.invokeLater {
            showNewDownloadWindowInternal(downloadInfo)
        }
    }

    override fun addVideoDownload(vid: Long, fileName: String, fileSize: Long?, contentType: String?) {
        AppContext.videoTracker.getHttpVideo(vid)?.let { source ->
            if (AppContext.refreshLinkInProgress.get()) {
                AppContext.downloader.updateDownloadInfo(AppContext.refreshLinkId.get(), source)
                return
            }
        }

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

    private inline fun runOnUIThread(crossinline action: () -> Unit) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { action() }
        } else {
            action()
        }
    }

    override fun showProgressWindow(id: Long, fileName: String) {
        runOnUIThread {
            var prgWnd = prgWndMap[id]
            if (prgWnd != null) {
                prgWnd.isVisible = true
            } else {
                prgWnd = ProgressWindow(id).apply {
                    lblFileName.text = fileName
                    title = "[ 0% ] $fileName"
                    isVisible = true
                }
                prgWndMap[id] = prgWnd
            }
        }
    }

    override fun updateProgressWindow(
        id: Long,
        fileName: String?,
        downloaded: Long,
        size: Long,
        speed: Float,
        eta: Long,
        prg: Int,
        segData: Collection<SegmentProgress>
    ) {
        runOnUIThread { prgWndMap[id]?.updateProgress(fileName, downloaded, size, speed, eta, prg, segData) }
    }

    override fun hideProgressWindow(id: Long) {
        runOnUIThread {
            val wnd = prgWndMap.remove(id)
            wnd?.isVisible = false
            wnd?.dispose()
        }
    }

    override fun showProgressError(id: Long, error: DownloadError) {
        runOnUIThread {
            val wnd = prgWndMap.remove(id)
            wnd?.showError(error)
        }
    }

    override fun showRefreshWindow(id: Long) {
        val url = AppContext.downloader.getOriginPage(id)
        if (url == null) {
            MessageBox.show(appWindow, "XDM", text("ERR_NO_REFRESH"))
            return
        }
        AppContext.refreshLinkId.set(id)
        AppContext.refreshLinkInProgress.set(true)
        refreshLinkWindow = RefreshLinkWindow(id).apply {
            addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) {
                    AppContext.refreshLinkInProgress.set(false)
                    AppContext.refreshLinkId.set(-1)
                    refreshLinkWindow = null
                    System.gc()
                }
            })
        }
        refreshLinkWindow?.isVisible = true
        openWebPage(url)
    }
}
