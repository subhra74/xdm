package xdm.app

import xdm.app.ui.screens.ShutdownCountdownWindow
import xdm.app.utils.KeepAwake
import xdm.app.utils.SoundPlayer
import xdm.app.utils.TorrentLibrary
import xdm.app.utils.WorkingSetTrim
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.web.SegmentProgress
import xdm.core.network.http.CertTrust
import xdm.core.network.http.HttpAuth
import xdm.core.util.Logger
import xdm.core.util.MovePhase
import xdm.manager.BatchFileCounts
import xdm.manager.DownloadSource
import xdm.manager.EngineConfig
import xdm.manager.EngineHost
import xdm.manager.PlatformHooks
import xdm.manager.UiSink
import java.awt.GraphicsEnvironment
import java.io.File

/** The download lifecycle on the desktop: the app's config, the Swing UI and the OS layers. */
object DesktopEngineHost : EngineHost {
    override val config: EngineConfig
        get() = AppContext.config

    override val taskInfoDB: TaskInfoDB
        get() = AppContext.taskInfoDB

    override val ui: UiSink = DesktopUiSink

    override val hooks: PlatformHooks = DesktopPlatformHooks
}

/** Forwards to [IAppInstance], which marshals to the EDT itself. */
object DesktopUiSink : UiSink {
    private val app get() = AppContext.app

    override fun downloadAdded(id: Long) = app.addDownloadInView(id)

    override fun downloadUpdated(id: Long) = app.updateDownloadInView(id)

    override fun downloadRemoved(id: Long, position: Int) = app.deleteDownloadInView(position)

    override fun downloadsRemoved() = app.downloadsRemovedInView()

    override fun showProgress(id: Long, fileName: String) = app.showProgressWindow(id, fileName)

    override fun hideProgress(id: Long) = app.hideProgressWindow(id)

    override fun progress(
        id: Long, fileName: String?, downloaded: Long, size: Long, speed: Float, eta: Long, progress: Int,
        segments: Collection<SegmentProgress>, files: BatchFileCounts?,
    ) = app.updateProgressWindow(id, fileName, downloaded, size, speed, eta, progress, segments, files)

    override fun moveProgress(id: Long, phase: MovePhase, progress: Int) = app.updateMoveProgress(id, phase, progress)

    override fun seedingFailed(id: Long) {
        val name = AppContext.db.getById(id)?.fileName ?: return
        xdm.app.ui.components.MessageBox.show(
            xdm.app.utils.mainWindowOnScreen(), I8N.text("TORRENT_SEEDING"),
            String.format(I8N.text("TORRENT_SEEDING_STOPPED"), name), javax.swing.JOptionPane.WARNING_MESSAGE,
        )
    }

    override fun downloadFailed(id: Long, error: DownloadError) = app.showProgressError(id, error)

    override fun spaceWarning(folder: String, needed: Long, free: Long, isTempFolder: Boolean) =
        app.showSpaceWarning(folder, needed, free, isTempFolder)

    override fun downloadCompleteDialog(id: Long, folder: String, fileName: String, fileSize: Long) =
        app.showDownloadCompleteWindow(id, folder, fileName, fileSize)

    override fun downloadCompleteNotification(folder: String, fileName: String) =
        app.showDownloadCompleteNotification(folder, fileName)
}

object DesktopPlatformHooks : PlatformHooks {
    override val httpAuth: HttpAuth
        get() = AppContext.httpAuth

    /** No checks at all while the user has "Ignore certificate errors" on. */
    override fun insecureTrust(): CertTrust? = InsecureTrust.ifEnabled(AppContext.config.ignoreCertErrors)

    override fun keepAwake(on: Boolean) = if (on) KeepAwake.acquire() else KeepAwake.release()

    override fun usableSpace(folder: String): Long? = File(folder).usableSpace

    override fun markDownloadedFile(file: File, source: DownloadSource): Boolean =
        AppContext.platform.markDownloadedFile(file, source)

    override fun downloadFinished(path: String, type: DownloadType) {
        SoundPlayer.playIfEnabled(SoundPlayer.Sound.COMPLETE)
        if (AppContext.config.runVirusScan) {
            AppContext.platform.runVirusScan(path)
        }
        // The per-extension rules run even with the catch-all switched off.
        AppContext.platform.runCustomCommand(path, type)
    }

    override fun downloadFailed(id: Long) = SoundPlayer.playIfEnabled(SoundPlayer.Sound.FAILED)

    override fun allDownloadsDone() {
        WorkingSetTrim.request("all downloads finished")
        if (AppContext.config.haltAfterDownload) {
            if (GraphicsEnvironment.isHeadless()) {
                Logger.info("Attempting shutdown after all downloads")
                AppContext.platform.shutdownPC()
            } else {
                Logger.info("All downloads finished; starting the shutdown countdown")
                ShutdownCountdownWindow.start()
            }
        }
    }

    override fun loadTorrentLibrary(): Boolean = TorrentLibrary.load()

    override fun downloadDeleted(id: Long) {
        if (AppContext.hasScheduler && AppContext.scheduler.contains(id)) {
            AppContext.scheduler.removeEntry(id)
        }
    }
}
