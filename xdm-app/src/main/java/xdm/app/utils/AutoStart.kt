package xdm.app.utils

import xdm.app.MINIMIZED_FLAG
import xdm.app.OS
import xdm.app.recording.RecordingSession
import xdm.app.utils.win.Win32Registry
import xdm.core.util.Logger
import java.io.File

/**
 * Registers/unregisters XDM to start automatically on user login. No JNI; each platform uses a
 * plain user-level mechanism that requires no elevation:
 *
 *  - macOS:   a LaunchAgent plist at ~/Library/LaunchAgents/<LABEL>.plist with RunAtLoad=true.
 *  - Linux:   an XDG autostart .desktop file at ~/.config/autostart/xdm-app.desktop.
 *  - Windows: a value under HKCU\Software\Microsoft\Windows\CurrentVersion\Run, created by the
 *             MSI (packaging/windows/xdm.wxs), not by the app: an app writing its own Run value at
 *             startup is what antivirus heuristics flag. The toggle turns it on and off the way Task
 *             Manager does, through the value's flag under Explorer\StartupApproved\Run, so a
 *             Task Manager "Disable" shows up as off here and an MSI repair or upgrade, which
 *             rewrites the Run value, never turns autostart back on. The app writes the Run value
 *             itself only when the user switches the toggle on and it is missing (another user of a
 *             per-machine install, or a value deleted from outside). All of it goes through
 *             [Win32Registry] (java.lang.foreign -> Advapi32).
 *
 * Enable/disable is idempotent. The launch target is always the `xdm-app` executable (see
 * [launchCommand]), started with [MINIMIZED_FLAG] so logging in brings up XDM in the tray rather
 * than throwing its window in the user's face. On macOS and Linux [sync] rewrites entries written
 * by older versions, which lack that flag; on Windows nothing is written unless the user asks.
 */
object AutoStart {
    private const val LABEL = "app.xdm.autostart"
    private const val APP_NAME = "Xtreme Download Manager"

    /** Relative to HKCU - [Win32Registry] works there and nowhere else. */
    private const val WIN_RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    /** Task Manager's and Settings > Startup's on/off flag for each [WIN_RUN_KEY] value, same name. */
    private const val WIN_APPROVED_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"

    /** Must match the MSI's RegistryValue Name; also the name XDM 8 used. */
    private const val WIN_VALUE_NAME = "XDM"

    private val os = detectOS()

    /** On Windows the MSI creates the login entry, so the app never enables it by itself. */
    val isInstallerOwned: Boolean get() = os == OS.Windows

    fun isEnabled(): Boolean = runCatching {
        when (os) {
            OS.MacOS -> macPlist().isFile
            OS.Linux -> linuxDesktopFile().isFile
            OS.Windows -> Win32Registry.getString(WIN_RUN_KEY, WIN_VALUE_NAME) != null &&
                isApproved(Win32Registry.getBinary(WIN_APPROVED_KEY, WIN_VALUE_NAME))
        }
    }.getOrDefault(false)

    /**
     * Enable or disable autostart. Safe to call repeatedly. Returns true when the OS entry now
     * matches [enabled] - checked, not assumed, since [enable] gives up without throwing when the
     * executable cannot be resolved.
     */
    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (enabled) enable() else disable()
        isEnabled() == enabled
    }.getOrElse {
        Logger.error("AutoStart: failed to set autostart=$enabled", it)
        false
    }

    /**
     * Brings an existing login entry up to date with what [enable] would write now - in practice,
     * adding [MINIMIZED_FLAG] to entries created before it existed. Does nothing when autostart is
     * off or the entry already matches, so it is cheap enough to call on every launch.
     *
     * Not on Windows: the MSI writes the entry, and the app touches it only on the user's request.
     */
    fun sync() {
        if (os == OS.Windows) return
        runCatching {
            if (!isEnabled() || isUpToDate()) return
            // A class-list recording run reads the entry like any start, but must not point the
            // user's login entry at the build folder it runs from.
            if (System.getProperty(RecordingSession.PROPERTY) != null) return
            Logger.info("AutoStart: refreshing the login entry")
            enable()
        }.onFailure { Logger.error("AutoStart: could not refresh the login entry", it) }
    }

    /** Whether the stored entry is exactly what [enable] would write today. */
    private fun isUpToDate(): Boolean {
        val cmd = launchCommand() ?: return true
        return when (os) {
            OS.MacOS -> macPlist().readText() == macPlistContent(cmd)
            OS.Linux -> linuxDesktopFile().readText() == linuxDesktopContent(cmd)
            OS.Windows -> true
        }
    }

    private fun enable() {
        val cmd = launchCommand() ?: run {
            Logger.error("AutoStart: could not resolve the app executable; autostart not enabled")
            return
        }
        when (os) {
            OS.MacOS -> {
                val f = macPlist()
                f.parentFile?.mkdirs()
                f.writeText(macPlistContent(cmd))
            }
            OS.Linux -> {
                val f = linuxDesktopFile()
                f.parentFile?.mkdirs()
                f.writeText(linuxDesktopContent(cmd))
                f.setExecutable(true)
            }
            OS.Windows -> writeWindowsRunValue(cmd)
        }
        Logger.info("AutoStart: enabled ($os) -> ${cmd.joinToString(" ")}")
    }

    private fun disable() {
        when (os) {
            OS.MacOS -> deleteFile(macPlist())
            OS.Linux -> deleteFile(linuxDesktopFile())
            OS.Windows -> disableWindows()
        }
        Logger.info("AutoStart: disabled ($os)")
    }

    private fun deleteFile(f: File) {
        if (f.exists() && !f.delete()) Logger.error("AutoStart: failed to delete ${f.absolutePath}")
    }

    // --- launch target resolution ------------------------------------------------------------

    /** The launcher plus [MINIMIZED_FLAG]: starting with the machine should not open a window. */
    private fun launchCommand(): List<String>? = AppLauncher.command()?.plus(MINIMIZED_FLAG)

    private fun commandLine(cmd: List<String>): String = AppLauncher.commandLine(cmd)

    // --- macOS -------------------------------------------------------------------------------

    private fun macPlist() =
        File(System.getProperty("user.home"), "Library/LaunchAgents/$LABEL.plist")

    private fun macPlistContent(cmd: List<String>): String {
        val args = cmd.joinToString("\n") { "        <string>${xmlEscape(it)}</string>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
$args
    </array>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>
"""
    }

    // --- Linux -------------------------------------------------------------------------------

    private fun linuxDesktopFile() =
        File(System.getProperty("user.home"), ".config/autostart/xdm-app.desktop")

    private fun linuxDesktopContent(cmd: List<String>): String {
        return """[Desktop Entry]
Type=Application
Name=$APP_NAME
Exec=${AppLauncher.desktopExec(cmd)}
Terminal=false
X-GNOME-Autostart-enabled=true
"""
    }

    // --- Windows -----------------------------------------------------------------------------

    private fun writeWindowsRunValue(cmd: List<String>) {
        // Normally the MSI's value. Written here only when it is missing, as the launch command line
        // with the exe path quoted, so Explorer parses spaces correctly at logon.
        if (Win32Registry.getString(WIN_RUN_KEY, WIN_VALUE_NAME) == null) {
            Win32Registry.setString(WIN_RUN_KEY, WIN_VALUE_NAME, commandLine(cmd))
        }
        // No flag means enabled, so only a flag that says otherwise is rewritten.
        val flag = Win32Registry.getBinary(WIN_APPROVED_KEY, WIN_VALUE_NAME)
        if (!isApproved(flag)) Win32Registry.setBinary(WIN_APPROVED_KEY, WIN_VALUE_NAME, approvedFlag(true))
    }

    /**
     * Keeps the Run value (the MSI's) and marks it disabled, as Task Manager does. A repair or
     * upgrade rewrites the Run value but never touches this flag, so the choice sticks.
     */
    private fun disableWindows() {
        if (Win32Registry.getString(WIN_RUN_KEY, WIN_VALUE_NAME) != null) {
            Win32Registry.setBinary(WIN_APPROVED_KEY, WIN_VALUE_NAME, approvedFlag(false))
        }
    }

    /**
     * A StartupApproved flag is 12 bytes: a state byte, three zero bytes and the FILETIME it was
     * disabled at. Even state bytes (2, 6) are enabled, odd ones (3, 7) disabled; a missing or empty
     * flag counts as enabled, which is how Explorer treats it.
     */
    internal fun isApproved(flag: ByteArray?): Boolean = flag == null || flag.isEmpty() || (flag[0].toInt() and 1) == 0

    /** The flag Task Manager writes: state 2 with no time when enabling, state 3 and the time now when disabling. */
    internal fun approvedFlag(enabled: Boolean, nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val flag = ByteArray(12)
        flag[0] = if (enabled) 2 else 3
        if (!enabled) {
            // FILETIME: 100 ns ticks since 1601-01-01 UTC, little-endian.
            val ticks = (nowMillis + 11_644_473_600_000L) * 10_000L
            for (i in 0 until 8) flag[4 + i] = (ticks ushr (8 * i)).toByte()
        }
        return flag
    }

    private fun xmlEscape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
