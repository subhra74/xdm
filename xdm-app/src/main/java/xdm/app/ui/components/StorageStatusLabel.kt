package xdm.app.ui.components

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.utils.RemixIcon
import xdm.app.utils.VolumeHints
import xdm.app.utils.createIcon
import xdm.core.util.FormatHelper
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.UIManager

/**
 * The one storage line in the new-download dialogs. Normally a drive icon and the destination's free
 * space. In the video dialogs ([usesTempFolder]) two cases turn it into a warning link that runs
 * [onChangeTempFolder]: the temp folder's drive is too low (the segments fill it first), or the
 * destination is on a different drive from it (both drives need space). Low space wins when both apply; the tooltip lists all
 * of it, including the destination's free space the warning text replaced.
 */
class StorageStatusLabel(
    /**
     * True for video (HLS/DASH) dialogs, whose segments go to the temp folder first, so its space and
     * drive matter. False for HTTP, which writes straight to the destination: only its space counts.
     */
    var usesTempFolder: Boolean = true,
    private val onChangeTempFolder: (destination: String?, needed: Long?) -> Unit,
) : JLabel("---") {
    private var destination: String? = null
    private var needed: Long? = null
    private var lastKey: Any? = null
    private var clickable = false

    init {
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (clickable) onChangeTempFolder(destination, needed)
            }
        })
    }

    /**
     * Re-evaluates for a download of [needed] bytes (null when unknown) going to [destination].
     * Cheap to call on every keystroke: the disk is only queried when the inputs or the temp folder
     * actually change.
     */
    fun update(destination: String?, needed: Long?) {
        this.destination = destination
        this.needed = needed
        val key = Triple(destination, needed, AppContext.config.tempFolder)
        if (key == lastKey) return
        lastKey = key
        refresh()
    }

    private fun refresh() {
        if (!usesTempFolder) return refreshDestinationOnly()
        val temp = AppContext.config.tempFolder
        val tempSpace = VolumeHints.spaceOf(temp)
        val destFree = VolumeHints.spaceOf(destination)?.free
        val low = tempSpace != null && VolumeHints.isLow(tempSpace.free, needed)
        val otherDrive = VolumeHints.isDifferentVolume(destination, temp)
        val destFreeText = "${text("MSG_FREE_SPACE")} ${destFree?.let { size(it) } ?: "---"}"

        when {
            low -> {
                icon = createIcon(RemixIcon.ERROR_WARNING_FILL, 14, WARNING)
                text = text("STORAGE_TEMP_LOW").replace("%free%", size(tempSpace!!.free))
                foreground = UIManager.getColor("ProgressBar.foreground")
            }
            otherDrive -> {
                icon = createIcon(RemixIcon.ERROR_WARNING_FILL, 14, WARNING)
                text = text("STORAGE_OTHER_DRIVE")
                foreground = UIManager.getColor("ProgressBar.foreground")
            }
            else -> {
                icon = createIcon(RemixIcon.HARD_DRIVE_2_LINE, 14, Color.GRAY)
                text = destFreeText
                foreground = UIManager.getColor("Label.foreground")
            }
        }

        clickable = low || otherDrive
        cursor = Cursor.getPredefinedCursor(if (clickable) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        toolTipText = if (!clickable) null else buildList {
            if (low) {
                val free = size(tempSpace!!.free)
                add(needed?.takeIf { it > 0 }?.let {
                    text("TND_LOW_NEEDED").replace("%free%", free).replace("%needed%", size(it))
                } ?: text("TND_LOW_FREE").replace("%free%", free))
            }
            if (otherDrive) add(text("TND_OTHER_DRIVE"))
            // The warning text replaced the destination's number; keep it reachable.
            add(destFreeText)
            add(text("STORAGE_CLICK"))
        }.joinToString("<br>", "<html>", "</html>")
    }

    /** HTTP: the destination's free space, turning into a warning when the download will not fit. */
    private fun refreshDestinationOnly() {
        val space = VolumeHints.spaceOf(destination)
        val freeText = "${text("MSG_FREE_SPACE")} ${space?.free?.let { size(it) } ?: "---"}"
        val low = space != null && VolumeHints.isLow(space.free, needed)
        icon = if (low) createIcon(RemixIcon.ERROR_WARNING_FILL, 14, WARNING) else createIcon(RemixIcon.HARD_DRIVE_2_LINE, 14, Color.GRAY)
        text = freeText
        foreground = UIManager.getColor(if (low) "ProgressBar.foreground" else "Label.foreground")
        clickable = false
        cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        toolTipText = if (low) needed?.let { text("MSG_TARGET_SPACE_LOW").replace("%folder%", destination.orEmpty())
            .replace("%free%", size(space!!.free)).replace("%needed%", size(it)) } else null
    }

    /** Re-reads the disk even if nothing changed, e.g. after the temp folder was changed. */
    fun refreshNow() {
        lastKey = null
        update(destination, needed)
    }

    private fun size(bytes: Long) = FormatHelper.formatSize(bytes.toDouble())

    companion object {
        /** Same amber as the checksum panel's warnings. */
        val WARNING = Color(0xE0A100)
    }
}
