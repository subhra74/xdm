package xdm.app.utils

import xdm.app.OS
import xdm.app.utils.win.Win32Power
import xdm.core.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Prevents the OS from entering idle *system* sleep while downloads are active. We only block
 * system idle-sleep, never the display.
 *
 *  - Windows: `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)` through
 *             java.lang.foreign. The state belongs to the thread that set it, so XDM owns one
 *             thread for exactly as long as a download is running - see [WindowsInhibitor].
 *             (Previously a `powershell.exe` helper process did this P/Invoke, which cost a whole
 *             process and looked like script execution to antivirus heuristics.)
 *  - macOS:   `caffeinate -i -w <pid>` - `-i` inhibits idle sleep, `-w <pid>` makes caffeinate
 *             auto-exit when our JVM dies.
 *  - Linux:   `systemd-inhibit --what=idle:sleep --mode=block cat` - a logind inhibitor lock held
 *             for the lifetime of the blocking `cat`.
 *
 * Whichever mechanism is used, it is released when XDM exits even if it exits badly: the OS drops
 * a thread's execution state when the process dies, and the helper processes are tied to the JVM's
 * lifetime.
 *
 * [acquire] and [release] are both idempotent and thread-safe.
 */
object KeepAwake {
    private val lock = Any()
    private var process: Process? = null
    private var inhibitor: WindowsInhibitor? = null
    private val os = detectOS()

    /** Ensure the sleep inhibitor is active. Safe to call repeatedly. */
    fun acquire() {
        synchronized(lock) {
            if (os == OS.Windows) {
                if (inhibitor?.isActive == true) return
                inhibitor = try {
                    WindowsInhibitor().apply { start() }
                } catch (e: Exception) {
                    Logger.error("KeepAwake: failed to inhibit sleep", e)
                    null
                }
                if (inhibitor != null) Logger.info("KeepAwake: sleep inhibitor acquired ($os)")
                return
            }
            if (process?.isAlive == true) return
            process = try {
                startInhibitor()
            } catch (e: Exception) {
                Logger.error("KeepAwake: failed to start sleep inhibitor", e)
                null
            }
            if (process != null) Logger.info("KeepAwake: sleep inhibitor acquired ($os)")
        }
    }

    /** Release the sleep inhibitor if held. Safe to call repeatedly. */
    fun release() {
        synchronized(lock) {
            inhibitor?.let {
                runCatching { it.stop() }
                Logger.info("KeepAwake: sleep inhibitor released")
            }
            inhibitor = null
            process?.let {
                runCatching { it.destroy() }
                Logger.info("KeepAwake: sleep inhibitor released")
            }
            process = null
        }
    }

    private fun startInhibitor(): Process {
        val command = when (os) {
            OS.MacOS -> listOf("caffeinate", "-i", "-w", ProcessHandle.current().pid().toString())
            OS.Linux -> listOf(
                "systemd-inhibit",
                "--what=idle:sleep",
                "--who=XDM",
                "--why=Download in progress",
                "--mode=block",
                "cat"
            )
            OS.Windows -> throw IllegalStateException("Windows uses WindowsInhibitor")
        }
        return ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    /**
     * Holds the Windows execution state on a thread of its own.
     *
     * Windows ties the state to the thread that set it and clears it when that thread ends, so the
     * thread *is* the inhibitor: it is started when a download starts and ends when the last one
     * finishes. It must be a platform thread - a virtual thread would migrate between carriers and
     * the state would follow a carrier rather than the task - and it is a daemon so it can never be
     * what keeps the JVM alive.
     */
    private class WindowsInhibitor {
        private val stopRequested = CountDownLatch(1)
        private val settled = CountDownLatch(1)

        @Volatile
        private var active = false

        private val thread = Thread(::hold, "xdm-keep-awake").apply { isDaemon = true }

        val isActive: Boolean
            get() = active && thread.isAlive

        fun start() {
            thread.start()
            settled.await(5, TimeUnit.SECONDS)
            check(active) { "SetThreadExecutionState did not take effect" }
        }

        fun stop() {
            stopRequested.countDown()
            thread.join(TimeUnit.SECONDS.toMillis(2))
        }

        private fun hold() {
            active = Win32Power.setThreadExecutionState(
                Win32Power.ES_CONTINUOUS or Win32Power.ES_SYSTEM_REQUIRED
            ) != 0
            settled.countDown()
            if (!active) {
                Logger.error("KeepAwake: SetThreadExecutionState returned 0")
                return
            }
            try {
                stopRequested.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // Clear it explicitly rather than relying on the thread ending, so the state is gone
            // by the time stop() returns.
            Win32Power.setThreadExecutionState(Win32Power.ES_CONTINUOUS)
            active = false
        }
    }
}
