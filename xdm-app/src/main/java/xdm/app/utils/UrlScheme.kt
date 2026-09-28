package xdm.app.utils

import xdm.app.OS
import xdm.app.utils.win.Win32Registry
import xdm.core.util.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Registers `xdm-app://` with the desktop, so a browser (or anything else) can start XDM by
 * opening a link - the same mechanism Ubuntu's software centre uses for `apt://`.
 *
 * Per platform, all per-user and without elevation:
 *  - Windows: `HKCU\Software\Classes\xdm-app` with a `URL Protocol` marker and a
 *             `shell\open\command`, written through [Win32Registry] - the same java.lang.foreign
 *             layer [AutoStart] uses.
 *  - Linux:   a `.desktop` file declaring `MimeType=x-scheme-handler/xdm-app`, registered with
 *             `update-desktop-database` / `xdg-mime`.
 *  - macOS:   nothing to do at runtime. A bundle can only declare its schemes in `Info.plist`, so
 *             that is done at packaging time (see `packaging/build-bundle.sh`).
 *
 * Unlike the login entry, the handler command carries **no** `--minimized`: someone following an
 * `xdm-app://` link is asking to see XDM.
 *
 * [sync] is idempotent - it rewrites only when what is registered differs from what we would write
 * now (a moved or upgraded install), so it is cheap enough to call on every launch.
 */
object UrlScheme {

    const val SCHEME = "xdm-app"

    private const val APP_NAME = "Xtreme Download Manager"
    private const val WIN_KEY = "Software\\Classes\\$SCHEME"
    private const val WIN_COMMAND_KEY = "$WIN_KEY\\shell\\open\\command"
    private const val LINUX_DESKTOP_NAME = "xdm-app-url-handler.desktop"

    private val os = detectOS()

    /** Register the handler if it is missing or out of date. Never throws. */
    fun sync() {
        runCatching {
            if (isUpToDate()) return
            Logger.info("UrlScheme: registering the $SCHEME:// handler")
            register()
        }.onFailure { Logger.error("UrlScheme: could not register the $SCHEME:// handler", it) }
    }

    /** Remove the handler. Used when uninstalling; safe to call when nothing is registered. */
    fun unregister() {
        runCatching {
            when (os) {
                OS.Windows -> Win32Registry.deleteTree(WIN_KEY)
                OS.Linux -> if (linuxDesktopFile().delete()) updateLinuxDatabase()
                OS.MacOS -> Unit
            }
        }.onFailure { Logger.error("UrlScheme: could not remove the $SCHEME:// handler", it) }
    }

    private fun isUpToDate(): Boolean {
        val command = handlerCommand() ?: return true
        return when (os) {
            OS.Windows -> Win32Registry.getString(WIN_COMMAND_KEY, "") == command
            OS.Linux -> linuxDesktopFile().let { it.isFile && it.readText() == linuxDesktopContent() }
            OS.MacOS -> true
        }
    }

    private fun register() {
        when (os) {
            OS.Windows -> {
                val command = handlerCommand() ?: return
                // The marker value is what makes Windows treat the key as a URL scheme; its name
                // is "URL Protocol" and its (empty) data is ignored.
                Win32Registry.setString(WIN_KEY, "", "URL:$APP_NAME Protocol")
                Win32Registry.setString(WIN_KEY, "URL Protocol", "")
                Win32Registry.setString(WIN_COMMAND_KEY, "", command)
            }

            OS.Linux -> {
                val file = linuxDesktopFile()
                file.parentFile?.mkdirs()
                file.writeText(linuxDesktopContent())
                updateLinuxDatabase()
            }

            OS.MacOS -> Unit
        }
    }

    /** `"<launcher>" "%1"` on Windows - the URL is passed as the first argument. */
    private fun handlerCommand(): String? = when (os) {
        OS.Windows -> AppLauncher.commandLine("%1")
        else -> AppLauncher.command()?.let { AppLauncher.commandLine(it) }
    }

    // --- Linux -----------------------------------------------------------------------------

    private fun linuxDesktopDir() =
        File(System.getProperty("user.home"), ".local/share/applications")

    private fun linuxDesktopFile() = File(linuxDesktopDir(), LINUX_DESKTOP_NAME)

    private fun linuxDesktopContent(): String {
        // %u hands the URL to the launcher; NoDisplay keeps this out of the application menu,
        // where the package's own entry already lives.
        val exec = handlerCommand() ?: ""
        return """[Desktop Entry]
Type=Application
Name=$APP_NAME
Exec=$exec %u
Terminal=false
NoDisplay=true
MimeType=x-scheme-handler/$SCHEME;
"""
    }

    private fun updateLinuxDatabase() {
        run("update-desktop-database", linuxDesktopDir().absolutePath)
        run("xdg-mime", "default", LINUX_DESKTOP_NAME, "x-scheme-handler/$SCHEME")
    }

    /** Best effort: neither tool is guaranteed to exist, and neither is fatal if it doesn't. */
    private fun run(vararg command: String) {
        runCatching {
            val proc = ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!proc.waitFor(20, TimeUnit.SECONDS)) proc.destroy()
        }.onFailure { Logger.info("UrlScheme: ${command.first()} is unavailable") }
    }
}
