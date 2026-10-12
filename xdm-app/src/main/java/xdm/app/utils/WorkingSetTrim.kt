package xdm.app.utils

import xdm.app.AppContext
import xdm.app.OS
import xdm.app.utils.win.Win32Memory
import xdm.core.util.Logger
import java.awt.Window
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Windows only: hands XDM's working set back to Windows once it has nothing to show and nothing to
 * download - the main window was closed to the tray and no download is pending, or the last download
 * finished while no window was open. Task Manager's "Memory" then drops to a few MB and climbs back
 * only as far as XDM touches pages again (measured on Windows 11: ~34 MB idle in the tray -> ~19 MB
 * settled after a trim; reopening the window takes as long as without the trim, ~670 ms).
 *
 * A full GC runs first, so the heap is compacted and shrunk before its pages are dropped. Safe only
 * because the heap is ordinary memory: with -XX:AllocateHeapAt, paged-out heap came back as zeros on
 * Windows (JDK-8390353), which is why that flag is gone.
 *
 * [request] may be called from any thread; requests within [DELAY_MS] collapse into one check.
 */
object WorkingSetTrim {
    /** Lets a window close and a finishing download's last callbacks settle first. */
    private const val DELAY_MS = 5_000L

    private val enabled = detectOS() == OS.Windows
    private val scheduler by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "working-set-trim").apply { isDaemon = true } }
    }
    private var pending: ScheduledFuture<*>? = null

    fun request(reason: String) {
        if (!enabled) return
        synchronized(this) {
            pending?.cancel(false)
            pending = scheduler.schedule({ trimIfIdle(reason) }, DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun trimIfIdle(reason: String) {
        try {
            var windowShowing = false
            SwingUtilities.invokeAndWait { windowShowing = Window.getWindows().any { it.isShowing } }
            if (windowShowing || AppContext.downloader.hasPendingDownloads()) return
            System.gc()
            if (Win32Memory.trimWorkingSet()) Logger.info("Working set trimmed ($reason)")
            else Logger.info("Working set trim refused by Windows ($reason)")
        } catch (e: Throwable) {
            Logger.error("Working set trim failed", e)
        }
    }
}
