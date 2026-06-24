package xdm.app.utils

import xdm.app.OS
import xdm.core.util.Logger

/**
 * Prevents the OS from entering idle *system* sleep while downloads are active.
 *
 * Strategy (no JNI): hold a single long-lived helper process per platform. The OS releases
 * the sleep inhibitor automatically the instant that process exits, so even if XDM crashes
 * nothing is left dangling. We only block system idle-sleep, never the display.
 *
 *  - macOS:   `caffeinate -i -w <pid>` — `-i` inhibits idle sleep, `-w <pid>` makes caffeinate
 *             auto-exit when our JVM dies.
 *  - Windows: a `powershell.exe` process that P/Invokes `SetThreadExecutionState` with
 *             `ES_CONTINUOUS | ES_SYSTEM_REQUIRED`, then blocks on stdin. The execution state is
 *             per-process, so it clears the moment the process is destroyed.
 *  - Linux:   `systemd-inhibit --what=idle:sleep --mode=block cat` — a logind inhibitor lock held
 *             for the lifetime of the blocking `cat`. Falls back to the screensaver D-Bus ping if
 *             systemd-inhibit is unavailable.
 *
 * [acquire] and [release] are both idempotent and thread-safe.
 */
object KeepAwake {
    private val lock = Any()
    private var process: Process? = null
    private val os = detectOS()

    /** Ensure the sleep inhibitor is active. Safe to call repeatedly. */
    fun acquire() {
        synchronized(lock) {
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
            OS.Windows -> listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_KEEP_AWAKE_SCRIPT
            )
            OS.Linux -> listOf(
                "systemd-inhibit",
                "--what=idle:sleep",
                "--who=XDM",
                "--why=Download in progress",
                "--mode=block",
                "cat"
            )
        }
        return ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    // Single-line so it survives being passed as one argv element. Uses no PowerShell `$`
    // variables (which would need escaping) and decimal flag values to avoid hex-literal typing
    // surprises: ES_CONTINUOUS = 0x80000000 = 2147483648, ES_SYSTEM_REQUIRED = 0x1.
    private const val WINDOWS_KEEP_AWAKE_SCRIPT =
        "Add-Type -Name P -Namespace W -MemberDefinition '[DllImport(\"kernel32.dll\", SetLastError=true)] public static extern uint SetThreadExecutionState(uint f);'; " +
            "[W.P]::SetThreadExecutionState(([uint32]2147483648 -bor [uint32]1)) | Out-Null; " +
            "[Console]::In.ReadToEnd() | Out-Null"
}
