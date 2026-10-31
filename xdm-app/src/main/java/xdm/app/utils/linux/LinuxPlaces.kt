package xdm.app.utils.linux

import com.formdev.flatlaf.icons.FlatFileViewComputerIcon
import com.formdev.flatlaf.icons.FlatFileViewHardDriveIcon
import xdm.app.I8N
import xdm.core.util.Logger
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.Icon
import javax.swing.UIManager

/**
 * A places bar for Swing's `JFileChooser` on Linux: home, the XDG user folders, GTK bookmarks, mounted
 * removable drives and `/`. FlatLaf's file chooser always has a shortcuts panel, empty on Linux only
 * because the JDK's `FileSystemView` returns no shortcut files there; [install] fills it through FlatLaf's
 * `FileChooser.shortcuts.*` hooks. The list is read each time a chooser is made, so a drive plugged in
 * shows up the next time. See private-docs/docs/linux-file-chooser.md.
 */
internal object LinuxPlaces {

    private val home = File(System.getProperty("user.home"))
    private val root = File("/")

    /** Labels that are not the folder's own name (home, `/`, bookmarks), from the last [files]. */
    private val labels = ConcurrentHashMap<String, String>()

    /** Folders of mounted drives from the last [files], for their icon. */
    private val drives = ConcurrentHashMap.newKeySet<String>()

    /** XDG user folders shown, in this order; templates and public share are left out. */
    private val userDirKeys = listOf("DESKTOP", "DOCUMENTS", "DOWNLOAD", "MUSIC", "PICTURES", "VIDEOS")

    fun install() {
        UIManager.put("FileChooser.shortcuts.filesFunction", Function<Array<File>, Array<File>> { jdk ->
            runCatching { files(jdk) }.getOrElse {
                Logger.error("FileChooser", "Places bar: ${it.message}")
                jdk
            }
        })
        UIManager.put("FileChooser.shortcuts.displayNameFunction", Function<File, String> { file ->
            labels[file.path] ?: file.name.ifEmpty { file.path }
        })
        UIManager.put("FileChooser.shortcuts.iconFunction", Function<File, Icon?> { file ->
            when {
                file.path == root.path -> FlatFileViewComputerIcon()
                file.path in drives -> FlatFileViewHardDriveIcon()
                else -> null // FlatLaf's folder icon
            }
        })
    }

    private fun files(jdk: Array<File>): Array<File> {
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".config")
        val userDirs = read(File(config, "user-dirs.dirs"))?.let { userDirs(it, home) }.orEmpty()
        val bookmarks = read(File(config, "gtk-3.0/bookmarks"))?.let(::gtkBookmarks).orEmpty()
        val mounted = read(File("/proc/self/mountinfo"))?.let { removableMounts(it, System.getProperty("user.name")) }.orEmpty()

        labels.clear()
        labels[home.path] = I8N.text("PLACES_HOME")
        labels[root.path] = I8N.text("PLACES_ROOT")
        bookmarks.forEach { (file, label) -> if (label != null) labels[file.path] = label }
        drives.clear()
        mounted.forEach { drives += it.path }

        val all = jdk.toList() + home + userDirs + bookmarks.map { it.first } + mounted + root
        return all.filter { it.isDirectory }.distinctBy { it.path }.toTypedArray()
    }

    private fun read(file: File): String? = runCatching { file.readText() }.getOrNull()

    /**
     * `XDG_DOWNLOAD_DIR="$HOME/Downloads"` lines of `user-dirs.dirs`, in [userDirKeys] order. A folder set
     * to home itself means "none" there and is left out.
     */
    fun userDirs(text: String, home: File): List<File> {
        val dirs = text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("XDG_") && it.contains('=') }
            .associate { line ->
                val key = line.substringBefore('=').removePrefix("XDG_").removeSuffix("_DIR")
                val value = line.substringAfter('=').trim().removeSurrounding("\"").replace("\\\"", "\"")
                key to value
            }
        return userDirKeys.mapNotNull { key ->
            val value = dirs[key] ?: return@mapNotNull null
            val file = when {
                value == "\$HOME" -> home
                value.startsWith("\$HOME/") -> File(home, value.removePrefix("\$HOME/"))
                value.startsWith("/") -> File(value)
                else -> return@mapNotNull null
            }
            file.takeIf { it.path != home.path }
        }
    }

    /** `file:///path Label` lines of GTK's `bookmarks`; other schemes (sftp, smb) are left out. */
    fun gtkBookmarks(text: String): List<Pair<File, String?>> = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("file://") }
        .mapNotNull { line ->
            val uri = line.substringBefore(' ')
            val label = line.substringAfter(' ', "").trim().ifEmpty { null }
            runCatching { File(URI(uri)) to label }.getOrNull()
        }
        .toList()

    /**
     * Mount points of removable drives from `/proc/self/mountinfo`: under `/run/media/<user>/` (udisks on
     * Fedora, Arch, KDE), `/media/<user>/` (Ubuntu, Debian), `/media/` and `/mnt/`. udisks names the folder
     * after the volume label, so the folder name is a good label.
     */
    fun removableMounts(mountinfo: String, user: String): List<File> = mountinfo.lineSequence()
        .mapNotNull { line -> line.split(' ').getOrNull(4)?.let(::unescapeMount) }
        .filter { point ->
            val parent = point.substringBeforeLast('/', "")
            parent == "/run/media/$user" || parent == "/media/$user" || (point.startsWith("/media/") &&
                point != "/media/$user" && parent == "/media") || parent == "/mnt"
        }
        .distinct()
        .map(::File)
        .toList()

    /** mountinfo escapes space, tab, newline and backslash as `\040`, `\011`, `\012`, `\134`. */
    fun unescapeMount(field: String): String {
        if (!field.contains('\\')) return field
        val out = StringBuilder()
        var i = 0
        while (i < field.length) {
            val octal = if (field[i] == '\\' && i + 4 <= field.length) {
                field.substring(i + 1, i + 4).toIntOrNull(8)
            } else null
            if (octal != null) {
                out.append(octal.toChar())
                i += 4
            } else {
                out.append(field[i])
                i++
            }
        }
        return out.toString()
    }
}
