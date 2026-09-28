package xdm.app.utils

import xdm.core.util.Logger
import java.io.File

/**
 * Resolves the command that starts XDM, for the places that have to hand the OS a launch command:
 * the login entry ([AutoStart]) and the `xdm-app://` handler ([UrlScheme]).
 *
 * A packaged build ships two launchers: the one the user sees ("Xtreme Download Manager", which
 * owns the Start-menu shortcut and the .app bundle) and `xdm-app` / `xdm-app.exe`, a stable,
 * space-free name added for exactly this purpose. What gets registered with the OS must not change
 * when the display name does, and must not depend on which launcher the user happened to start, so
 * [command] resolves `jpackage.app-path` and then prefers its `xdm-app` sibling when one exists.
 *
 * Running from a plain JVM during development, the command is reconstructed as `java -jar <jar>`.
 */
object AppLauncher {

    /** The name of the launcher XDM registers with the OS, on every platform. */
    const val LAUNCHER_NAME = "xdm-app"

    /** The executable, with no arguments. Null when it cannot be worked out. */
    fun command(): List<String>? {
        // jpackage sets this to the absolute path of the launcher that started this process.
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { appPath ->
            return listOf(preferStableLauncher(File(appPath)).absolutePath)
        }

        // native-image (and most direct launches): the actual process executable.
        val processCmd = ProcessHandle.current().info().command().orElse(null)
        if (processCmd != null) {
            val name = File(processCmd).name.lowercase()
            if (!name.startsWith("java")) return listOf(processCmd)

            // Dev fallback: running under a JVM, reconstruct `java -jar <jar>`.
            val jar = runCatching {
                File(AppLauncher::class.java.protectionDomain.codeSource.location.toURI())
            }.getOrNull()
            if (jar != null && jar.isFile) return listOf(processCmd, "-jar", jar.absolutePath)
        }
        Logger.error("Could not resolve the XDM executable")
        return null
    }

    /**
     * Swaps the launcher that started us for the `xdm-app` one beside it, when it is there. Falls
     * back to [started] itself - an older install, or a build without the extra launcher.
     */
    private fun preferStableLauncher(started: File): File {
        val extension = started.name.substringAfterLast('.', "")
        val stableName = if (extension.isEmpty()) LAUNCHER_NAME else "$LAUNCHER_NAME.$extension"
        if (started.name.equals(stableName, ignoreCase = true)) {
            return started
        }
        val sibling = File(started.parentFile, stableName)
        return if (sibling.isFile) sibling else started
    }

    /** [command] plus [args], as one command line with space-containing tokens quoted. */
    fun commandLine(vararg args: String): String? =
        command()?.let { cmd -> commandLine(cmd + args) }

    /** Joins a command into a single command line, quoting tokens with spaces. */
    fun commandLine(cmd: List<String>): String =
        cmd.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
}
