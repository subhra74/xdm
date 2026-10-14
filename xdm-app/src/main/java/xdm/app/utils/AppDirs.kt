package xdm.app.utils

import xdm.app.OS
import java.io.File

/**
 * Where XDM keeps its state (config, download lists, task state, logs and `tmp/` with the partial
 * downloads): the OS's per-user application data folder, named [NAME].
 *
 * - Windows: `%LOCALAPPDATA%\xdm-app` - local, not roaming: partial downloads are large and machine bound.
 * - macOS: `~/Library/Application Support/xdm-app`
 * - Linux: `$XDG_DATA_HOME/xdm-app`, by default `~/.local/share/xdm-app`. Not the cache dir: cleaners
 *   empty it, and `tmp/` holds the partial downloads.
 *
 * `-D`[PROPERTY]`=<dir>` overrides it. The class-list recording uses that for a throwaway profile:
 * `-Duser.home` alone does not move `%LOCALAPPDATA%`, which comes from the environment.
 */
object AppDirs {
    const val NAME = "xdm-app"
    const val PROPERTY = "xdm.data.dir"

    val root: File by lazy {
        resolve(detectOS(), System.getProperty(PROPERTY), System.getProperty("user.home"), System::getenv)
    }

    internal fun resolve(os: OS, override: String?, home: String, env: (String) -> String?): File {
        override?.takeIf { it.isNotBlank() }?.let { return File(it).absoluteFile }
        val base = when (os) {
            OS.Windows -> sequenceOf("LOCALAPPDATA", "APPDATA").mapNotNull(env).firstOrNull { it.isNotBlank() }
                ?.let(::File) ?: File(home)
            OS.MacOS -> File(home, "Library/Application Support")
            // The XDG spec ignores a relative XDG_DATA_HOME.
            OS.Linux -> env("XDG_DATA_HOME")?.takeIf { File(it).isAbsolute }?.let(::File)
                ?: File(home, ".local/share")
        }
        return File(base, NAME)
    }
}
