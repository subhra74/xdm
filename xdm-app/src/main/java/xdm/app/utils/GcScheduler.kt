package xdm.app.utils

import xdm.app.AppContext
import xdm.core.util.Logger
import java.awt.Window

/**
 * Runs XDM's periodic `System.gc()`, which keeps the heap small: the bundles run ZGC without timer or
 * proactive cycles (and G1 with no `-Xmx`), so without it the heap only shrinks under allocation
 * pressure. How often depends on what XDM is doing:
 * - a download queued or running: every [BUSY_MS]
 * - no download, an XDM window showing: every [WINDOW_MS]
 * - otherwise (idle, in the tray, with or without a browser): every [IDLE_MS], plus one GC once
 *   browser integration has gone [QUIET_MS] without a request (browser closed, a burst of captures over)
 *
 * The quiet wait must outlast the extension's long poll ([xdm.integration.EventChannel.POLL_TIMEOUT_MS]),
 * which re-connects every 20 s while a browser runs. Download-finished, window-closed and working-set
 * trim GCs still run where they happen; this only covers the time in between.
 */
object GcScheduler {
    private const val TICK_MS = 5_000L
    private const val BUSY_MS = 15_000L
    private const val WINDOW_MS = 30_000L
    private const val IDLE_MS = 120_000L
    private const val QUIET_MS = 45_000L

    /** [System.nanoTime] of the last browser integration request; 0 if none yet. */
    @Volatile
    private var lastIntegrationRequest = 0L

    /** Call on every browser integration request, from any thread. */
    fun noteIntegrationRequest() {
        lastIntegrationRequest = System.nanoTime()
    }

    /** Call once, after [AppContext] is initialised. */
    fun start() {
        Logger.info("Registering periodic GC")
        Thread {
            var lastGc = System.nanoTime()
            while (true) {
                Thread.sleep(TICK_MS)
                try {
                    val now = System.nanoTime()
                    val sinceGc = (now - lastGc) / 1_000_000
                    val request = lastIntegrationRequest
                    val due = when {
                        AppContext.downloader.hasPendingDownloads() -> sinceGc >= BUSY_MS
                        // read off the EDT: a stale answer only picks the wrong interval for one tick
                        Window.getWindows().any { it.isShowing } -> sinceGc >= WINDOW_MS
                        else -> sinceGc >= IDLE_MS || (request != 0L && request - lastGc > 0
                                && (now - request) / 1_000_000 >= QUIET_MS)
                    }
                    if (due) {
                        System.gc()
                        lastGc = System.nanoTime()
                    }
                } catch (e: Exception) {
                    Logger.error("Periodic GC check failed", e)
                }
            }
        }.apply {
            name = "periodic-gc"
            isDaemon = true // must never keep the JVM alive on its own
        }.start()
    }
}
