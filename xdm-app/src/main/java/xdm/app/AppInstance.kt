package xdm.app

import xdm.app.AppContext.db
import xdm.app.I8N.text
import xdm.app.ui.components.ChecksumPanel
import xdm.app.ui.components.MessageBox
import xdm.app.ui.screens.AboutDialog
import xdm.app.ui.screens.AppWindow
import xdm.app.ui.screens.BatchDownloadDialog
import xdm.app.ui.screens.BrowserSetupDialog
import xdm.app.ui.screens.DownloadCompleteWindow
import xdm.app.ui.screens.NewDownloadWindow
import xdm.app.ui.screens.NewVideoDownloadWindow
import xdm.app.ui.screens.ProgressWindow
import xdm.app.ui.screens.PropertiesDialog
import xdm.app.torrent.TorrentLaunch
import xdm.app.torrent.TorrentLinkFetcher
import xdm.app.ui.components.AppMenuHandler
import xdm.app.ui.screens.TorrentProgressWindow
import xdm.app.ui.screens.TorrentPropertiesWindow
import xdm.app.ui.screens.RefreshLinkWindow
import xdm.app.ui.screens.ScheduleWindow
import xdm.app.utils.SingleWindows
import xdm.app.utils.TextContextMenu
import xdm.app.utils.WorkingSetTrim
import xdm.app.utils.createTray
import xdm.app.utils.detectOS
import xdm.app.utils.logoImage
import xdm.app.utils.mac.MacNotifications
import xdm.app.utils.mainWindowOnScreen
import xdm.app.utils.offerMacNotificationSettings
import xdm.app.utils.openFolderExternal
import xdm.app.utils.Browser
import xdm.app.utils.showTrayNotification
import xdm.app.utils.windowIconImages
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.SegmentProgress
import xdm.core.util.FormatHelper.formatSize
import xdm.core.util.Logger
import xdm.core.util.MovePhase
import xdm.manager.BatchFileCounts
import xdm.manager.DbRecord
import java.awt.Desktop
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Taskbar
import java.awt.desktop.AppReopenedListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

interface IAppInstance {
    fun run(args: Array<String>)

    fun showAppWindow()

    /**
     * Reports a failure that stops XDM from starting at all. Blocks until the user dismisses it,
     * because the caller exits right afterwards.
     */
    fun showFatalError(message: String)

    /** [note]: one line under the details about how the download was saved, when it wasn't the usual way. */
    fun showDownloadCompleteWindow(id: Long, folder: String, fileName: String, fileSize: Long, note: String? = null)

    /**
     * Posts a quiet "<file> download finished" system notification instead of the full dialog. A click
     * opens [folder], as the dialog's Open folder does.
     */
    fun showDownloadCompleteNotification(folder: String, fileName: String)

    fun updateDownloadInView(id: Long)

    fun deleteDownloadInView(index: Int)

    /** Refreshes the list once after several downloads were removed together. */
    fun downloadsRemovedInView()

    fun addDownloadInView(id: Long)

    fun addDownload(downloadInfo: HttpDownloadTaskInfo?)

    fun addVideoDownload(vid: Long, fileName: String, fileSize: Long?, fileType: String?)

    fun showProgressWindow(id: Long, fileName: String)

    fun hideProgressWindow(id: Long)

    fun showProgressError(id: Long, error: DownloadError)

    /** Drives the progress window through the publish phase, where download events have stopped. */
    fun updateMoveProgress(id: Long, phase: MovePhase, prg: Int)

    /**
     * Advisory: the volume a starting download writes to looks too small. [isTempFolder] says whether that
     * is the temp folder (HLS/DASH segments) or the download's own folder (HTTP).
     */
    fun showSpaceWarning(folder: String, needed: Long, free: Long, isTempFolder: Boolean)

    fun updateProgressWindow(
        id: Long,
        fileName: String?,
        downloaded: Long,
        size: Long,
        speed: Float,
        eta: Long,
        prg: Int,
        segData: Collection<SegmentProgress>,
        /** A batch's file counts; null for other downloads. */
        files: BatchFileCounts? = null,
    )

    fun showRefreshWindow(id: Long)

    fun showSchedulerWindow(id: Long)

    fun showPropertiesWindow(ent: DbRecord)

    /** Opens the batch dialog for links sent by the browser's "Download all". */
    fun showBatchDialog(request: BatchRequest)

    /** Opens Add torrent with [source] (a magnet link or a .torrent path) filled in; the OS handed it to XDM. */
    fun openTorrent(source: String)

    /** The XDM extension in [browser] is older than this XDM expects: asks the user to update it. */
    fun showExtensionUpdate(browser: Browser)
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

    /** EDT only. Whether this run already told the user that macOS has XDM's notifications off. */
    private var notificationsOffShown = false

    override fun run(args: Array<String>) {
        val minimized = args.contains(MINIMIZED_FLAG)
        installMacHandlers()
        runOnUIThread {
            TextContextMenu.install()
            val image = logoImage(256)
            // Closing the last window (the main one to the tray included): XDM's working set can go back to Windows.
            WorkingSetTrim.install()
            appWindow = AppWindow(image, startHidden = minimized)
            val hasTray = createTray(image)
            // Hidden even without a tray icon (GNOME without the AppIndicator extension, the Wayland
            // toolkit): the browser extension still works, and launching XDM again hands over to this
            // instance, which shows the window - the same state as closing the window.
            if (minimized) {
                Logger.info(if (hasTray) "Starting minimized to the system tray" else "Starting hidden (no system tray)")
                WorkingSetTrim.request("started hidden")
            } else {
                showAppWindow()
                if (AppContext.firstRun) {
                    BrowserSetupDialog(appWindow).isVisible = true
                }
            }
            // Started by opening a magnet link or a .torrent file with XDM (Windows, Linux).
            TorrentLaunch.sourceIn(args.asList())?.let { openTorrent(it) }
            // "Keep BitTorrent running": the session starts with XDM (off the EDT).
            if (AppContext.config.torrentKeepSession) AppContext.downloader.torrents.warmUp()
        }
    }

    override fun openTorrent(source: String) {
        runOnUIThread { AppMenuHandler.showTorrentDialog(mainWindowOnScreen(), source) }
    }

    /**
     * On macOS a second launch does not start a second process: LaunchServices activates the
     * running app and delivers `xdm-app://...` as an Apple event, so the argv/`/show` handover the
     * other platforms use never happens. Both events mean the same thing here - show the window -
     * except a `magnet:` link or a .torrent file (also how the first launch gets them), which open
     * Add torrent.
     */
    private fun installMacHandlers() {
        if (detectOS() != OS.MacOS || !Desktop.isDesktopSupported()) {
            return
        }
        val desktop = Desktop.getDesktop()
        // Unsupported when not running from a bundle (i.e. in development), hence runCatching.
        runCatching {
            desktop.setOpenURIHandler { e ->
                TorrentLaunch.sourceIn(listOf(e.uri.toString()))?.let { openTorrent(it) } ?: showAppWindow()
            }
        }
        runCatching {
            desktop.setOpenFileHandler { e ->
                TorrentLaunch.sourceIn(e.files.map { it.path })?.let { openTorrent(it) } ?: showAppWindow()
            }
        }
        runCatching { desktop.addAppEventListener(AppReopenedListener { showAppWindow() }) }
        // Dock and app menu Quit: the same exit as the Exit items (closes torrents, with its window if slow).
        runCatching { desktop.setQuitHandler { _, _ -> xdm.app.utils.AppExit.exit() } }
        // App menu → "About Xtreme Download Manager": XDM's own dialog instead of the generic one.
        // Called on the EDT; owned by the main window only while it is on screen.
        runCatching {
            desktop.setAboutHandler { AboutDialog.open(if (::appWindow.isInitialized) appWindow else null) }
        }
        installDockMenu()
    }

    /** Right-click on the Dock icon: Restore Window. macOS adds its own Quit below it. */
    private fun installDockMenu() {
        if (!Taskbar.isTaskbarSupported() || !Taskbar.getTaskbar().isSupported(Taskbar.Feature.MENU)) return
        runCatching {
            Taskbar.getTaskbar().menu = PopupMenu().apply {
                add(MenuItem(text("MSG_RESTORE")).apply { addActionListener { showAppWindow() } })
            }
        }.onFailure { Logger.error("Could not set the Dock menu", it) }
    }

    override fun showFatalError(message: String) {
        val show = Runnable {
            // Truly ownerless, so it has a taskbar button on Windows: with no main window yet, nothing else shows.
            JOptionPane(message, JOptionPane.ERROR_MESSAGE).createDialog("XDM").apply {
                runCatching { iconImages = windowIconImages }
                isVisible = true
                dispose()
            }
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

    override fun showDownloadCompleteWindow(id: Long, folder: String, fileName: String, fileSize: Long, note: String?) {
        runOnUIThread {
            // Checksums only make sense for a file fetched from one URL as-is.
            val source = if (AppContext.db.getById(id)?.downloadType == DownloadType.Http) ChecksumPanel.sourceOf(id) else null
            DownloadCompleteWindow().apply { setDetails(fileName, folder, fileSize, source, note) }.isVisible = true
        }
    }

    override fun showDownloadCompleteNotification(folder: String, fileName: String) {
        runOnUIThread {
            showTrayNotification(
                text("CD_TITLE"), fileName,
                onClick = { runCatching { openFolderExternal(fileName, folder) }.onFailure { Logger.error(it) } },
            ) {
                // macOS has XDM's notifications off: say so once per run rather than stay silent.
                if (MacNotifications.isAvailable && !notificationsOffShown) {
                    notificationsOffShown = true
                    offerMacNotificationSettings(appWindow)
                }
            }
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

    override fun downloadsRemovedInView() {
        runOnUIThread { appWindow.downloadsRemovedInView() }
    }

    override fun addDownloadInView(id: Long) {
        runOnUIThread { db.indexById(id)?.let { appWindow.addDownloadInView(it) } }
    }

    override fun addDownload(downloadInfo: HttpDownloadTaskInfo?) {
        if (downloadInfo != null && AppContext.refreshLinkInProgress.compareAndSet(true, false)) {
            AppContext.downloader.updateDownloadInfo(AppContext.refreshLinkId.get(), downloadInfo)
            // Called on the browser-integration server thread.
            runOnUIThread { refreshLinkWindow?.showRefreshed() }
            return
        }
        // A .torrent link: Add torrent with its file list; the usual dialog if it is not one after all.
        if (downloadInfo != null && TorrentLinkFetcher.wants(downloadInfo)) {
            TorrentLinkFetcher.fetch(downloadInfo) { file ->
                if (file != null) openTorrent(file.absolutePath) else showNewDownloadWindowInternal(downloadInfo)
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
            if (AppContext.refreshLinkInProgress.compareAndSet(true, false)) {
                AppContext.downloader.updateDownloadInfo(AppContext.refreshLinkId.get(), source)
                runOnUIThread { refreshLinkWindow?.showRefreshed() }
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

    override fun showExtensionUpdate(browser: Browser) {
        runOnUIThread {
            if (::appWindow.isInitialized) {
                appWindow.showExtensionUpdate(browser)
            }
        }
    }

    private inline fun runOnUIThread(crossinline action: () -> Unit) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { action() }
        } else {
            action()
        }
    }

    override fun showProgressWindow(id: Long, fileName: String) {
        // Always deferred, even on the EDT: a download started from a dialog's action would otherwise
        // show its window before that dialog closes, and on Windows closing it then activates the main
        // window over the new progress window (and a modal dialog would block the window meanwhile).
        SwingUtilities.invokeLater {
            if (isTorrent(id)) return@invokeLater showTorrentProgress(id)
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
            prgWnd.toFront()
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
        segData: Collection<SegmentProgress>,
        files: BatchFileCounts?,
    ) {
        if (downloaded > 0) pendingHandoffs.remove(id)
        runOnUIThread { prgWndMap[id]?.updateProgress(fileName, downloaded, size, speed, eta, prg, segData, files) }
    }

    override fun updateMoveProgress(id: Long, phase: MovePhase, prg: Int) {
        runOnUIThread { prgWndMap[id]?.showMoving(phase, prg) }
    }

    override fun showSpaceWarning(folder: String, needed: Long, free: Long, isTempFolder: Boolean) {
        runOnUIThread {
            MessageBox.show(
                appWindow,
                text("TITLE_TEMP_SPACE_LOW"),
                text(if (isTempFolder) "MSG_TEMP_SPACE_LOW" else "MSG_TARGET_SPACE_LOW")
                    .replace("%folder%", folder)
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
            torrentProgress.remove(id)?.dispose()
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
        showTrayNotification(text("TITLE_BROWSER_DOWNLOAD_FAILED"), message) {
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
        // A finished refresh stays on screen with its success line until closed; one at a time.
        refreshLinkWindow?.dispose()
        AppContext.refreshLinkId.set(id)
        AppContext.refreshLinkInProgress.set(true)
        refreshLinkWindow = RefreshLinkWindow(appWindow, id, page).apply {
            addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) {
                    // WINDOW_CLOSED arrives later: a replaced window must not end the refresh that replaced it.
                    if (refreshLinkWindow !== e.window) return
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
        runOnUIThread { SingleWindows.show("schedule" to id) { ScheduleWindow(appWindow, id, modal = false) } }
    }

    override fun showPropertiesWindow(ent: DbRecord) {
        runOnUIThread {
            if (ent.downloadType == DownloadType.Torrent) {
                SingleWindows.show("torrent-properties" to ent.id) { TorrentPropertiesWindow(appWindow, ent.id) }
            }
            else SingleWindows.show("properties" to ent.id) { PropertiesDialog(appWindow, ent) }
        }
    }

    private fun isTorrent(id: Long) = db.getById(id)?.downloadType == DownloadType.Torrent

    /** Torrent progress windows by download id; closed like the HTTP ones when the download stops. */
    private val torrentProgress = HashMap<Long, TorrentProgressWindow>()

    private fun showTorrentProgress(id: Long) {
        torrentProgress[id]?.takeIf { it.isDisplayable }?.let { it.isVisible = true; it.toFront(); return }
        val w = TorrentProgressWindow(id)
        torrentProgress[id] = w
        w.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                if (torrentProgress[id] === w) torrentProgress.remove(id)
            }
        })
        w.isVisible = true
        w.toFront()
    }

    override fun showBatchDialog(request: BatchRequest) {
        runOnUIThread {
            BatchDownloadDialog(mainWindowOnScreen(), request).isVisible = true
        }
    }
}
