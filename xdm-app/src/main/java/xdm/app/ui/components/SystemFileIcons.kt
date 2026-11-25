package xdm.app.ui.components

import xdm.app.utils.px
import xdm.core.downloaders.DownloadType
import xdm.core.util.Logger
import xdm.core.util.PlatformUtils
import xdm.manager.DbRecord
import xdm.manager.RecordStatus
import xdm.manager.getFileFolder
import java.awt.Component
import java.awt.Graphics
import java.awt.Window
import java.io.File
import java.nio.file.Files
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.swing.Icon
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileSystemView

/**
 * The Windows shell icon of a download's file, for the list's icon tile.
 *
 * A finished program (.exe and similar) shows the icon embedded in that very file; anything else shows
 * the icon Windows associates with its extension (looked up on an empty sample file, so it works while
 * the download is still running). Icons load on a background thread: [iconFor] returns null until one
 * is ready (the caller keeps its category glyph meanwhile) and repaints the windows once it is.
 * Other platforms always get null.
 */
object SystemFileIcons {
    /** Extensions whose icon lives inside each file rather than coming from the extension. */
    private val perFileExtensions = setOf("exe", "ico", "lnk", "cur", "scr")

    /** Size the shell icon is drawn at; the tile reserves [SLOT] (its glyph size) for it. */
    private val SIZE get() = 20.px
    private val SLOT get() = 16.px

    private val enabled = PlatformUtils.isWindows
    private val cache = ConcurrentHashMap<String, Optional<Icon>>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xdm-file-icons").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val sampleDir: File by lazy {
        Files.createTempDirectory("xdm-icons").toFile().apply { deleteOnExit() }
    }

    fun iconFor(ent: DbRecord): Icon? {
        if (!enabled || ent.downloadType == DownloadType.Batch) return null
        val ext = ent.fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext.length > 16 || !ext.all { it.isLetterOrDigit() }) return null

        if (ext in perFileExtensions && ent.status == RecordStatus.FINISHED) {
            val key = "file:${ent.id}:${ent.fileName}"
            val own = lookup(key) { loadFromFile(ent) }
            // Still loading, or the file is gone: fall back to the extension's icon.
            if (own != null && own.isPresent) return own.get()
        }
        return lookup("ext:$ext") { loadForExtension(ext) }?.orElse(null)
    }

    /** The cached result for [key], or null after queueing [load] when there is none yet. */
    private fun lookup(key: String, load: () -> Icon?): Optional<Icon>? {
        cache[key]?.let { return it }
        if (pending.add(key)) {
            loader.execute {
                val icon = runCatching { load() }.onFailure { Logger.error(it) }.getOrNull()
                cache[key] = Optional.ofNullable(icon?.let { SlotIcon(it) })
                pending.remove(key)
                SwingUtilities.invokeLater { Window.getWindows().forEach { if (it.isShowing) it.repaint() } }
            }
        }
        return null
    }

    private fun loadFromFile(ent: DbRecord): Icon? {
        val (fileName, folder) = getFileFolder(ent) ?: return null
        val file = File(folder ?: return null, fileName ?: ent.fileName)
        return if (file.isFile) systemIcon(file) else null
    }

    private fun loadForExtension(ext: String): Icon? {
        val sample = File(sampleDir, "sample.$ext")
        if (!sample.exists()) {
            sample.createNewFile()
            sample.deleteOnExit()
        }
        return systemIcon(sample)
    }

    private fun systemIcon(file: File): Icon? =
        FileSystemView.getFileSystemView().getSystemIcon(file, SIZE, SIZE)

    /**
     * Reports the glyph's [SLOT] size so the tile and row keep their layout, and paints the larger shell
     * icon centered over that slot (the tile's padding leaves room around it).
     */
    private class SlotIcon(private val icon: Icon) : Icon {
        override fun getIconWidth() = SLOT
        override fun getIconHeight() = SLOT
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            icon.paintIcon(c, g, x + (SLOT - icon.iconWidth) / 2, y + (SLOT - icon.iconHeight) / 2)
        }
    }
}
