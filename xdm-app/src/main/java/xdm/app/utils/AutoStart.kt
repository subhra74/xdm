package xdm.app.utils

import xdm.app.MINIMIZED_FLAG
import xdm.app.OS
import xdm.core.util.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Registers/unregisters XDM to start automatically on user login. No JNI; each platform uses a
 * plain user-level mechanism that requires no elevation:
 *
 *  - macOS:   a LaunchAgent plist at ~/Library/LaunchAgents/<LABEL>.plist with RunAtLoad=true.
 *  - Linux:   an XDG autostart .desktop file at ~/.config/autostart/xdm-app.desktop.
 *  - Windows: a value under HKCU\Software\Microsoft\Windows\CurrentVersion\Run. Writing is done by
 *             generating a .reg file and running `reg import`, which gives byte-exact control over
 *             quoting/escaping (passing a value that itself contains quotes and spaces through
 *             ProcessBuilder to `reg add` is unreliable). Reading/removing use plain `reg query`
 *             / `reg delete` whose arguments contain neither spaces nor quotes.
 *
 * Enable/disable is idempotent. The launch target is always the `xdm-app` executable (see
 * [launchCommand]), started with [MINIMIZED_FLAG] so logging in brings up XDM in the tray rather
 * than throwing its window in the user's face. [sync] rewrites entries written by older versions,
 * which lack that flag.
 */
object AutoStart {
    private const val LABEL = "app.xdm.autostart"
    private const val APP_NAME = "Xtreme Download Manager"

    private const val WIN_RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val WIN_VALUE_NAME = "XDM"

    private val os = detectOS()

    fun isEnabled(): Boolean = runCatching {
        when (os) {
            OS.MacOS -> macPlist().isFile
            OS.Linux -> linuxDesktopFile().isFile
            OS.Windows -> windowsRunValueExists()
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
            OS.Windows -> windowsRunValueMatches(commandLine(cmd))
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
            OS.Windows -> deleteWindowsRunValue()
        }
        Logger.info("AutoStart: disabled ($os)")
    }

    private fun deleteFile(f: File) {
        if (f.exists() && !f.delete()) Logger.error("AutoStart: failed to delete ${f.absolutePath}")
    }

    // --- launch target resolution ------------------------------------------------------------

    /**
     * Resolves the command that launches XDM. In production the executable is always named
     * `xdm-app` (jpackage launcher on all platforms, or the GraalVM native-image binary on Windows).
     * Falls back to `java -jar <jar>` when running from a plain JVM during development.
     */
    private fun launchCommand(): List<String>? = launcher()?.plus(MINIMIZED_FLAG)

    /** The executable part of [launchCommand], without the arguments XDM adds. */
    private fun launcher(): List<String>? {
        // jpackage sets this to the absolute path of the native launcher.
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { return listOf(it) }

        // native-image (and most direct launches): the actual process executable.
        val processCmd = ProcessHandle.current().info().command().orElse(null)
        if (processCmd != null) {
            val name = File(processCmd).name.lowercase()
            if (!name.startsWith("java")) return listOf(processCmd)

            // Dev fallback: running under a JVM, reconstruct `java -jar <jar>`.
            val jar = runCatching {
                File(AutoStart::class.java.protectionDomain.codeSource.location.toURI())
            }.getOrNull()
            if (jar != null && jar.isFile) return listOf(processCmd, "-jar", jar.absolutePath)
        }
        return null
    }

    /** Joins a launch command into a single Windows command line, quoting tokens with spaces. */
    private fun commandLine(cmd: List<String>): String =
        cmd.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }

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
        // The Run value must contain the launch command line with the exe path quoted so Explorer
        // parses spaces correctly at logon.
        val value = regEscape(commandLine(cmd))
        // "Version 5.00" .reg files are UTF-16LE with a BOM; reg import requires this encoding for
        // reliable handling of non-ASCII paths.
        val content = "Windows Registry Editor Version 5.00\r\n\r\n" +
            "[$WIN_RUN_KEY_FULL]\r\n" +
            "\"$WIN_VALUE_NAME\"=\"$value\"\r\n"
        val tmp = File.createTempFile("xdm-autostart", ".reg")
        try {
            tmp.outputStream().use { out ->
                out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) // UTF-16LE BOM
                out.write(content.toByteArray(Charsets.UTF_16LE))
            }
            runReg("import", tmp.absolutePath)
        } finally {
            tmp.delete()
        }
    }

    private fun deleteWindowsRunValue() {
        runReg("delete", WIN_RUN_KEY, "/v", WIN_VALUE_NAME, "/f")
    }

    private fun windowsRunValueExists(): Boolean =
        runReg("query", WIN_RUN_KEY, "/v", WIN_VALUE_NAME) == 0

    /** True when the stored Run value is already the command line we would write. */
    private fun windowsRunValueMatches(expected: String): Boolean = runCatching {
        val proc = ProcessBuilder(listOf("reg", "query", WIN_RUN_KEY, "/v", WIN_VALUE_NAME))
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(20, TimeUnit.SECONDS)) {
            proc.destroy()
            return false
        }
        proc.exitValue() == 0 && out.contains(expected)
    }.getOrDefault(false)

    /** Runs `reg <args>` quietly and returns its exit code (or -1 on failure). */
    private fun runReg(vararg args: String): Int = runCatching {
        val proc = ProcessBuilder(listOf("reg") + args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (proc.waitFor(20, TimeUnit.SECONDS)) proc.exitValue() else { proc.destroy(); -1 }
    }.getOrDefault(-1)

    /** Escapes a REG_SZ value for a .reg file: backslashes and quotes are backslash-escaped. */
    private fun regEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    // The HKCU\... short form works for `reg query`/`reg delete`, but .reg files need the long
    // HKEY_CURRENT_USER form for the key path.
    private val WIN_RUN_KEY_FULL =
        "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    private fun xmlEscape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
