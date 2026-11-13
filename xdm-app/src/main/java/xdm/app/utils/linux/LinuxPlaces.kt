package xdm.app.utils.linux

import com.formdev.flatlaf.icons.FlatFileViewComputerIcon
import com.formdev.flatlaf.icons.FlatFileViewHardDriveIcon
import xdm.app.I8N
import xdm.app.OS
import xdm.app.utils.detectOS
import xdm.core.util.Logger
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import javax.swing.Icon
import javax.swing.UIManager

/**
 * A places bar for Swing's `JFileChooser` on Linux and the BSDs: home, the XDG user folders, GTK bookmarks,
 * mounted removable drives and `/`. FlatLaf's file chooser always has a shortcuts panel, empty there only
 * because the JDK's `FileSystemView` returns no shortcut files; [install] fills it through FlatLaf's
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
        // The chooser asks on the UI thread; on the BSDs the list comes from mount(8), so start it now.
        if (detectOS() == OS.BSD) refreshMounts(System.getProperty("user.name"))
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
        val mounted = currentRemovableMounts(System.getProperty("user.name"))

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
    fun removableMounts(mountinfo: String, user: String): List<File> = removable(
        mountinfo.lineSequence().mapNotNull { line -> line.split(' ').getOrNull(4)?.let(::unescapeMount) },
        user,
    )

    /**
     * Same as [removableMounts] but from the output of `mount(8)` on the BSDs, which have no
     * `/proc/self/mountinfo` (and often no procfs at all). FreeBSD, DragonFly and MidnightBSD print
     * `dev on /point (fstype, flags)`; OpenBSD and NetBSD print `dev on /point type fstype (flags)`, which
     * [typed] selects. The two cannot be told apart from a line alone: a FreeBSD folder may be named
     * `a type b`.
     */
    fun removableMountsFromMount(output: String, user: String, typed: Boolean = false): List<File> {
        val line = if (typed) typedMountLine else mountLine
        return removable(output.lineSequence().mapNotNull { line.matchEntire(it.trim())?.groupValues?.get(1) }, user)
    }

    private val mountLine = Regex("""^.+? on (.+) \([^()]*\)$""")
    private val typedMountLine = Regex("""^.+? on (.+) type \S+ \([^()]*\)$""")

    /** OpenBSD and NetBSD put the file system type after the mount point; the other BSDs put it in the brackets. */
    fun printsTypeAfterPoint(osName: String? = System.getProperty("os.name")): Boolean {
        val name = osName?.lowercase() ?: return false
        return "openbsd" in name || "netbsd" in name
    }

    private fun removable(points: Sequence<String>, user: String): List<File> = points
        .filter { point ->
            val parent = point.substringBeforeLast('/', "")
            parent == "/run/media/$user" || parent == "/media/$user" || (point.startsWith("/media/") &&
                point != "/media/$user" && parent == "/media") || parent == "/mnt"
        }
        .distinct()
        .map(::File)
        .toList()

    /**
     * Linux reads `/proc/self/mountinfo` and nothing else; the BSDs ask `mount(8)` and nothing else. Both
     * sources are lambdas so the one that does not apply is never touched.
     */
    fun removableMountsFor(
        bsd: Boolean, user: String, typed: Boolean,
        mountinfo: () -> String?, mountOutput: () -> String?,
    ): List<File> =
        if (bsd) mountOutput()?.let { removableMountsFromMount(it, user, typed) }.orEmpty()
        else mountinfo()?.let { removableMounts(it, user) }.orEmpty()

    /** Drives from the last [refreshMounts]; mount(8) is slow enough to freeze the UI, so it is never run there. */
    @Volatile
    private var bsdMounts: List<File> = emptyList()
    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var mountProcess: Process? = null

    private fun currentRemovableMounts(user: String): List<File> {
        if (detectOS() != OS.BSD) {
            return removableMountsFor(false, user, false, { read(File("/proc/self/mountinfo")) }, { null })
        }
        refreshMounts(user) // a drive plugged in shows up the next time the chooser opens
        return bsdMounts
    }

    private fun refreshMounts(user: String) {
        if (mountProcess?.isAlive == true || !refreshing.compareAndSet(false, true)) return
        Thread({
            try {
                bsdMounts = removableMountsFor(true, user, printsTypeAfterPoint(), { null }, ::runMount)
            } finally {
                refreshing.set(false)
            }
        }, "xdm-mounts").apply { isDaemon = true; start() }
    }

    private val mountCommands = listOf("/sbin/mount", "/bin/mount", "/usr/sbin/mount", "/usr/bin/mount")

    private fun runMount(): String? {
        // One command, not a chain of fallbacks: each that hangs would cost its whole timeout.
        val command = mountCommands.firstOrNull { File(it).canExecute() } ?: return null
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return null
        }
        mountProcess = process
        // readText() blocks until mount closes its output, so the read is on a thread of its own that
        // is a daemon and not from a shared pool: if mount cannot be killed it stays blocked, harmlessly.
        val output = CompletableFuture<String>()
        Thread({
            try {
                output.complete(process.inputStream.bufferedReader().readText())
            } catch (e: Exception) {
                output.completeExceptionally(e)
            }
        }, "xdm-mounts-read").apply { isDaemon = true; start() }
        return try {
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) output.get(1, TimeUnit.SECONDS) else null
        } catch (e: Exception) {
            null
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
    }

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
