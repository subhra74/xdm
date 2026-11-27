package xdm.app.utils

import xdm.app.OS
import xdm.app.utils.linux.XdgUserDirs
import xdm.app.utils.win.Win32KnownFolders
import java.io.File

/**
 * The user's Downloads folder: where the default download folder, and with it the built-in category
 * folders, start on first run. Asked of the OS rather than built as `~/Downloads`, which is only the
 * default: on Windows the user can move the folder (Downloads > Properties > Location), and on Linux
 * its name is localized (`~/Téléchargements`). Building it ourselves created a stray `~/Downloads`.
 *
 * - Windows: the Downloads known folder (`SHGetKnownFolderPath`), what Explorer opens for `shell:Downloads`.
 * - Linux: `XDG_DOWNLOAD_DIR` in `user-dirs.dirs`.
 * - macOS: `~/Downloads`, which can't be moved.
 *
 * `~/Downloads` whenever the OS has no answer.
 */
object DownloadsFolder {

    fun resolve(): String = resolve(
        detectOS(), File(System.getProperty("user.home")), System::getenv,
        // A lambda, not a reference: the Win32 class is loaded only on Windows.
        knownFolder = { Win32KnownFolders.downloads() },
    ).absolutePath

    internal fun resolve(
        os: OS,
        home: File,
        env: (String) -> String?,
        knownFolder: () -> String?,
        read: (File) -> String? = { runCatching { it.readText() }.getOrNull() },
    ): File {
        val found = when (os) {
            OS.Windows -> knownFolder()?.takeIf { it.isNotBlank() }?.let(::File)
            OS.Linux -> read(XdgUserDirs.file(home, env))?.let { XdgUserDirs.parse(it, home)["DOWNLOAD"] }
            OS.MacOS -> null
        }
        return found ?: File(home, "Downloads")
    }
}
