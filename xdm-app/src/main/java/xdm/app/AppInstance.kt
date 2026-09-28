package xdm.app

import xdm.app.AppContext.db
import xdm.app.I8N.text
import xdm.app.ui.components.MessageBox
import xdm.core.util.FormatHelper.formatSize
import xdm.app.ui.screens.AppWindow
import xdm.app.ui.screens.DownloadCompleteWindow
import xdm.app.ui.screens.NewDownloadWindow
import xdm.app.ui.screens.NewVideoDownloadWindow
import xdm.app.ui.screens.ProgressWindow
import xdm.app.ui.screens.PropertiesDialog
import xdm.app.ui.screens.RefreshLinkWindow
import xdm.app.ui.screens.ScheduleWindow
import xdm.app.utils.TextContextMenu
import xdm.app.utils.logoImage
import xdm.app.utils.createTray
import xdm.app.utils.detectOS
import xdm.app.utils.showTrayNotification
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.SegmentProgress
import xdm.core.util.Logger
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JOptionPane
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

interface IAppInstance {
    fun run(args: Array<String>)

    fun showAppWindow()

    /**
     * Reports a failure that stops XDM from starting at all. Blocks until the user dismisses it,
     * because the caller exits right afterwards.
     */
    fun showFatalError(message: String)

    fun showDownloadCompleteWindow(id: Long, folder: String, fileName: String, fileSize: Long)

    /** Posts a quiet "<file> download finished" system notification instead of the full dialog. */
    fun showDownloadCompleteNotification(fileName: String)

    fun updateDownloadInView(id: Long)

    fun deleteDownloadInView(index: Int)

    fun addDownloadInView(id: Long)

    fun addDownload(downloadInfo: HttpDownloadTaskInfo?)

    fun addVideoDownload(vid: Long, fileName: String, fileSize: Long?, fileType: String?)

    fun showProgressWindow(id: Long, fileName: String)

    fun hideProgressWindow(id: Long)

    fun showProgressError(id: Long, error: DownloadError)

    /** Drives the progress window through the publish phase, where download events have stopped. */
    fun updatePublishProgress(id: Long, prg: Int)

    /** Advisory: the temp volume looks too small for a download that is starting. */
    fun showTempSpaceWarning(tempFolder: String, needed: Long, free: Long)

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

    fun showSchedulerWindow(id: Long)

    fun showPropertiesWindow(ent: DbRecord)
}

class AppInstance : IAppInstance {
    private lateinit var appWindow: AppWindow
    private val prgWndMap = mutableMapOf<Long, ProgressWindow>()
    /**
     * Browser downloads started straight away that have not yet received a byte. The extension has
     * already cancelled the browser's copy, so if one of these fails there is no fallback - and with
     * no progress window open, nothing else would tell the user the file landed nowhere.
     */
    private val pendingHandoffs = ConcurrentHashMap.newKeySet<Long>()
    private var refreshLinkWindow: RefreshLinkWindow? = null

    override fun run(args: Array<String>) {
        val minimized = args.contains(MINIMIZED_FLAG)
        installMacHandlers()
        runOnUIThread {
            TextContextMenu.install()
            val image = logoImage(256)
            appWindow = AppWindow(image)
            val hasTray = createTray(image)
            // Starting hidden is only safe when there is a tray icon to get the window back from.
            // Without one (some Linux desktops drop the tray entirely) a hidden window would leave
            // the user with a running XDM they cannot reach, so show it anyway.
            if (minimized && hasTray) {
                Logger.info("Starting minimized to the system tray")
            } else {
                if (minimized) {
                    Logger.info("No system tray available; ignoring $MINIMIZED_FLAG")
                }
                showAppWindow()
            }
        }
    }

    /**
     * On macOS a second launch does not start a second process: LaunchServices activates the
     * running app and delivers `xdm-app://...` as an Apple event, so the argv/`/show` handover the
     * other platforms use never happens. Both events mean the same thing here - show the window.
     */
    private fun installMacHandlers() {
        if (detectOS() != OS.MacOS || !Desktop.isDesktopSupported()) {
            return
        }
        val desktop = Desktop.getDesktop()
        // Unsupported when not running from a bundle (i.e. in development), hence runCatching.
        runCatching { desktop.setOpenURIHandler { showAppWindow() } }
        runCatching { desktop.addAppEventListener(AppReopenedListener { showAppWindow() }) }
    }

    override fun showFatalError(message: String) {
        val show = Runnable {
            JOptionPane.showMessageDialog(null, message, "XDM", JOptionPane.ERROR_MESSAGE)
        }
        if (SwingUtilities.isEventDispatchThread()) {
            show.run()
        } else {
            // invokeAndWait, not invokeLater: the caller exits the process on return.
            runCatching { SwingUtilities.invokeAndWait(show) }
                .onFailure { Logger.error("Could not show the startup error dialog", it) }
        }
    }

    override fun showAppWindow() {
        runOnUIThread {
            appWindow.isVisible = true
            appWindow.toFront()
        }
    }

    override fun showDownloadCompleteWindow(id: Long, folder: String, fileName: String, fileSize: Long) {
        runOnUIThread { DownloadCompleteWindow().apply { setDetails(fileName, folder, fileSize) }.isVisible = true }
    }

    override fun showDownloadCompleteNotification(fileName: String) {
        runOnUIThread {
            showTrayNotification(text("CD_TITLE"), text("MSG_DOWNLOAD_FINISHED").format(fileName))
        }
    }

    // Rows are resolved by id on the EDT: an index taken on the calling thread can point at a
    // different row once a delete runs before the EDT gets to it.
    override fun updateDownloadInView(id: Long) {
        runOnUIThread { db.indexById(id)?.let { appWindow.updateDownloadInView(it) } }
    }

    override fun deleteDownloadInView(index: Int) {
        if (SwingUtilities.isEventDispatchThread()) {
            appWindow.deleteDownloadInView(index)
        } else {
            SwingUtilities.invokeAndWait { appWindow.deleteDownloadInView(index) }
        }
    }

    override fun addDownloadInView(id: Long) {
        runOnUIThread { db.indexById(id)?.let { appWindow.addDownloadInView(it) } }
    }

    override fun addDownload(downloadInfo: HttpDownloadTaskInfo?) {
        if (AppContext.refreshLinkInProgress.get() && downloadInfo != null) {
            AppContext.downloader.updateDownloadInfo(AppContext.refreshLinkId.get(), downloadInfo)
            // Called on the browser-integration server thread.
            runOnUIThread {
                refreshLinkWindow?.dispose()
                MessageBox.show(appWindow, "XDM", text("SUCCESS_REFRESH"))
            }
            return
        }
        if (downloadInfo != null && AppContext.config.startDownloadAutomatically) {
            pendingHandoffs.add(downloadInfo.id)
            AppContext.downloader.startHttpDownload(downloadInfo)
        } else {
            SwingUtilities.invokeLater {
                showNewDownloadWindowInternal(downloadInfo)
            }
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
        if (downloaded > 0) pendingHandoffs.remove(id)
        runOnUIThread { prgWndMap[id]?.updateProgress(fileName, downloaded, size, speed, eta, prg, segData) }
    }

    override fun updatePublishProgress(id: Long, prg: Int) {
        runOnUIThread { prgWndMap[id]?.showPublishing(prg) }
    }

    override fun showTempSpaceWarning(tempFolder: String, needed: Long, free: Long) {
        runOnUIThread {
            MessageBox.show(
                appWindow,
                text("TITLE_TEMP_SPACE_LOW"),
                text("MSG_TEMP_SPACE_LOW")
                    .replace("%folder%", tempFolder)
                    .replace("%needed%", formatSize(needed.toDouble()))
                    .replace("%free%", formatSize(free.toDouble())),
            )
        }
    }

    override fun hideProgressWindow(id: Long) {
        pendingHandoffs.remove(id)
        runOnUIThread {
            val wnd = prgWndMap.remove(id)
            wnd?.isVisible = false
            wnd?.dispose()
        }
    }

    override fun showProgressError(id: Long, error: DownloadError) {
        val handoff = pendingHandoffs.remove(id)
        runOnUIThread {
            val wnd = prgWndMap.remove(id)
            if (wnd != null) {
                wnd.showError(error)
            } else if (handoff) {
                showHandoffFailed(id)
            }
        }
    }

    private fun showHandoffFailed(id: Long) {
        val fileName = db.getById(id)?.fileName ?: return
        val message = text("MSG_BROWSER_DOWNLOAD_FAILED").format(fileName)
        if (!showTrayNotification(text("TITLE_BROWSER_DOWNLOAD_FAILED"), message)) {
            MessageBox.show(appWindow, text("TITLE_BROWSER_DOWNLOAD_FAILED"), message)
        }
    }

    override fun showRefreshWindow(id: Long) {
        runOnUIThread { showRefreshWindowInternal(id) }
    }

    private fun showRefreshWindowInternal(id: Long) {
        // Refreshing rewrites an HTTP task's link; there is nothing to refresh without one.
        if (AppContext.taskInfoDB.getHttpTask(id) == null) {
            MessageBox.show(appWindow, "XDM", text("ERR_NO_REFRESH"))
            return
        }
        // Null when the download carries no origin or Referer: the dialog then offers only the
        // manual link entry, since there is no page to send the browser back to.
        val page = AppContext.downloader.getRefererPage(id)
        AppContext.refreshLinkId.set(id)
        AppContext.refreshLinkInProgress.set(true)
        refreshLinkWindow = RefreshLinkWindow(appWindow, id, page).apply {
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
    }

    override fun showSchedulerWindow(id: Long) {
        runOnUIThread { ScheduleWindow(appWindow, id).showDialog() }
    }

    override fun showPropertiesWindow(ent: DbRecord) {
        runOnUIThread { PropertiesDialog(appWindow, ent).isVisible = true }
    }
}
