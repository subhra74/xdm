package xdm.app.utils

import xdm.app.MINIMIZED_FLAG
import xdm.app.OS
import xdm.app.utils.win.Win32Registry
import xdm.core.util.Logger
import java.io.File

/**
 * Registers/unregisters XDM to start automatically on user login. No JNI; each platform uses a
 * plain user-level mechanism that requires no elevation:
 *
 *  - macOS:   a LaunchAgent plist at ~/Library/LaunchAgents/<LABEL>.plist with RunAtLoad=true.
 *  - Linux:   an XDG autostart .desktop file at ~/.config/autostart/xdm-app.desktop.
 *  - Windows: a value under HKCU\Software\Microsoft\Windows\CurrentVersion\Run, written through
 *             [Win32Registry] (java.lang.foreign -> Advapi32). This used to shell out to `reg`,
 *             which meant a `.reg` file in UTF-16LE and two layers of escaping; the API takes the
 *             value verbatim, so only command-line quoting is left.
 *
 * Enable/disable is idempotent. The launch target is always the `xdm-app` executable (see
 * [launchCommand]), started with [MINIMIZED_FLAG] so logging in brings up XDM in the tray rather
 * than throwing its window in the user's face. [sync] rewrites entries written by older versions,
 * which lack that flag.
 */
object AutoStart {
    private const val LABEL = "app.xdm.autostart"
    private const val APP_NAME = "Xtreme Download Manager"

    /** Relative to HKCU - [Win32Registry] works there and nowhere else. */
    private const val WIN_RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val WIN_VALUE_NAME = "XDM"

    private val os = detectOS()

    fun isEnabled(): Boolean = runCatching {
        when (os) {
            OS.MacOS -> macPlist().isFile
            OS.Linux -> linuxDesktopFile().isFile
            OS.Windows -> Win32Registry.getString(WIN_RUN_KEY, WIN_VALUE_NAME) != null
        }
    }.getOrDefault(false)

    /** Enable or disable autostart. Safe to call repeatedly. Returns true on success. */
    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (enabled) enable() else disable()
        true
    }.getOrElse {
        Logger.error("AutoStart: failed to set autostart=$enabled", it)
        false
    }

    /**
     * Brings an existing login entry up to date with what [enable] would write now - in practice,
     * adding [MINIMIZED_FLAG] to entries created before it existed. Does nothing when autostart is
     * off or the entry already matches, so it is cheap enough to call on every launch.
     */
    fun sync() {
        runCatching {
            if (!isEnabled() || isUpToDate()) return
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
            OS.Windows -> Win32Registry.getString(WIN_RUN_KEY, WIN_VALUE_NAME) == commandLine(cmd)
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
            OS.Windows -> Win32Registry.deleteValue(WIN_RUN_KEY, WIN_VALUE_NAME)
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
        val exec = cmd.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
        return """[Desktop Entry]
Type=Application
Name=$APP_NAME
Exec=$exec
Terminal=false
X-GNOME-Autostart-enabled=true
"""
    }

    // --- Windows -----------------------------------------------------------------------------

    private fun writeWindowsRunValue(cmd: List<String>) {
        // The value is the launch command line with the exe path quoted, so Explorer parses spaces
        // correctly at logon.
        Win32Registry.setString(WIN_RUN_KEY, WIN_VALUE_NAME, commandLine(cmd))
    }

    private fun xmlEscape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
