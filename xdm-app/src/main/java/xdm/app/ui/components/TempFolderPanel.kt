package xdm.app.ui.components

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.VolumeHints
import xdm.app.utils.chooseFile
import xdm.app.utils.createIcon
import xdm.app.utils.px
import xdm.app.utils.sameWidth
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import xdm.core.util.FormatHelper
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Changes the app's temp folder from inside a download dialog: a card shown in place of the form
 * (see [TempFolderCards]), not a modal dialog over an always-on-top window. Shows how full the chosen
 * folder's drive is, and whether it is on a different drive from the download's destination, which
 * turns the final move into a copy.
 *
 * Its width is fixed by the path field's column count, never by the path or message text: every
 * message wraps at that width, so a long path or a large font makes it taller, not wider.
 *
 * Only new downloads use the new folder: running and paused ones keep the temp location recorded
 * when they started. See FILE_PLACEMENT.md.
 */
class TempFolderPanel(
    private val onClose: (saved: Boolean) -> Unit,
    private val onRelayout: () -> Unit,
) : JPanel(GridBagLayout()) {
    private val txtFolder = JTextField().apply {
        columns = 30
        putClientProperty(FlatClientProperties.STYLE, "arc: 10")
    }
    private val btnBrowse = JButton(text("BTN_BROWSE")).apply {
        icon = createIcon(RemixIcon.FOLDER_FILL, 16, Color.GRAY)
        addActionListener { browse() }
    }
    private val barSpace = JProgressBar(0, 1000).apply {
        preferredSize = Dimension(preferredSize.width, 6.px)
    }
    private val lblSpace = JLabel(" ").apply { foreground = muted() }
    private val txtOtherDrive = wrappingText()
    private val txtSameDriveTip = wrappingText().apply { foreground = muted(); text = text("TF_SAME_DRIVE_TIP") }
    private val otherDrive = JPanel(BorderLayout(6.px, 0)).apply {
        isOpaque = false
        isVisible = false
        add(JLabel(createIcon(RemixIcon.FOLDER_WARNING_FILL, 16, StorageStatusLabel.WARNING)).apply {
            verticalAlignment = JLabel.TOP
        }, BorderLayout.WEST)
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(txtOtherDrive)
            add(txtSameDriveTip)
        }, BorderLayout.CENTER)
    }
    private val txtError = wrappingText().apply { foreground = ERROR; isVisible = false }
    private val txtHint = wrappingText().apply {
        foreground = muted()
        text = "${text("TF_HINT")}\n${text("TF_HINT_NEW_ONLY")}"
    }

    /** The button Enter should press while this card shows. */
    val okButton = JButton(text("BTN_OK")).apply { addActionListener { save() } }

    private var destination: String? = null
    private var needed: Long? = null

    init {
        border = ScaledEmptyBorder(16, 16, 12, 16)
        fun gbc(x: Int, y: Int, w: Int = 1, weightX: Double = 0.0, fill: Int = GridBagConstraints.NONE) =
            GridBagConstraints().apply {
                gridx = x; gridy = y; gridwidth = w; weightx = weightX; this.fill = fill
                anchor = GridBagConstraints.WEST; insets = scaledInsets(4, 4, 4, 4)
            }
        val full = GridBagConstraints.HORIZONTAL

        // Label above the field, so the path gets the full width.
        add(JLabel(text("LBL_TEMP_FOLDER")), gbc(0, 0, 2).apply { insets = scaledInsets(4, 4, 2, 4) })
        add(txtFolder, gbc(0, 1, 1, 1.0, full))
        add(btnBrowse, gbc(1, 1))
        add(barSpace, gbc(0, 2, 1, 1.0, full).apply { insets = scaledInsets(8, 4, 2, 4) })
        add(lblSpace, gbc(0, 3, 2, 1.0, full).apply { insets = scaledInsets(0, 4, 4, 4) })
        add(otherDrive, gbc(0, 4, 2, 1.0, full).apply { insets = scaledInsets(8, 4, 4, 4) })
        add(txtError, gbc(0, 5, 2, 1.0, full))
        add(txtHint, gbc(0, 6, 2, 1.0, full).apply { insets = scaledInsets(10, 4, 4, 4) })

        val btnCancel = JButton(text("ND_CANCEL")).apply { addActionListener { onClose(false) } }
        sameWidth(okButton, btnCancel)
        val buttons = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = ScaledEmptyBorder(10, 0, 0, 0)
            add(Box.createHorizontalGlue())
            add(btnCancel); add(Box.createRigidArea(scaledSize(10, 0))); add(okButton)
        }
        add(buttons, gbc(0, 7, 2, 1.0, full))

        txtFolder.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
    }

    /** Resets the card to the saved temp folder, for a download of [needed] bytes going to [destination]. */
    fun open(destination: String?, needed: Long?) {
        this.destination = destination
        this.needed = needed
        txtError.isVisible = false
        txtFolder.text = AppContext.config.tempFolder
        txtFolder.caretPosition = 0
        refresh()
    }

    /** Focuses the path field; call once the card is showing. */
    fun focusField() {
        txtFolder.requestFocusInWindow()
    }

    private fun browse() {
        val current = File(txtFolder.text.trim()).takeIf { it.isDirectory }
        chooseFile(this, directoriesOnly = true, currentDir = current)?.let { txtFolder.text = it.absolutePath }
    }

    /** Space bar, space text and the different-drive warning, for the folder currently typed in. */
    private fun refresh() {
        val folder = txtFolder.text.trim()
        val space = VolumeHints.spaceOf(folder)
        if (space == null) {
            barSpace.value = 0
            barSpace.putClientProperty(FlatClientProperties.STYLE, null)
            lblSpace.text = text("TF_SPACE_UNKNOWN")
            lblSpace.foreground = muted()
        } else {
            val used = space.total - space.free
            barSpace.value = (used * 1000 / space.total).toInt()
            val low = VolumeHints.isLow(space.free, needed)
            barSpace.putClientProperty(FlatClientProperties.STYLE, if (low) "foreground: #E0A100" else null)
            lblSpace.text = text("TF_SPACE")
                .replace("%free%", FormatHelper.formatSize(space.free.toDouble()))
                .replace("%total%", FormatHelper.formatSize(space.total.toDouble()))
            lblSpace.foreground = if (low) StorageStatusLabel.WARNING else muted()
        }
        val other = VolumeHints.isDifferentVolume(destination, folder)
        if (other) txtOtherDrive.text = text("TF_OTHER_DRIVE")
        if (other != otherDrive.isVisible) {
            otherDrive.isVisible = other
            relayout()
        }
    }

    /** Creates the folder if needed and saves it; stays on the card with an error if it cannot be used. */
    private fun save() {
        val path = txtFolder.text.trim()
        val dir = File(path).absoluteFile
        val usable = path.isNotEmpty() &&
            runCatching { (dir.isDirectory || dir.mkdirs()) && dir.canWrite() }.getOrDefault(false)
        if (!usable) {
            Logger.error("XDM", "Temp folder is not usable: $dir")
            txtError.text = text("TF_ERR_FOLDER")
            txtError.isVisible = true
            relayout()
            return
        }
        val config = AppContext.config
        val changed = config.tempFolder != dir.path
        if (changed) {
            config.tempFolder = dir.path
            config.save()
        }
        onClose(changed)
    }

    /**
     * Wrapped text sized to the folder row, so messages never widen the card. A [JTextArea] reports
     * its wrapped height only once it has a width, hence the explicit size before measuring.
     */
    override fun getPreferredSize(): Dimension {
        val rowWidth = txtFolder.preferredSize.width + btnBrowse.preferredSize.width + 8.px
        for (area in listOf(txtOtherDrive, txtSameDriveTip, txtError, txtHint)) {
            val w = if (area === txtHint || area === txtError) rowWidth else rowWidth - 22.px
            area.setSize(w, Short.MAX_VALUE.toInt())
            area.preferredSize = null
            area.preferredSize = Dimension(w, area.preferredSize.height)
        }
        return super.getPreferredSize()
    }

    private fun relayout() {
        revalidate()
        repaint()
        onRelayout()
    }

    private fun wrappingText() = JTextArea().apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = null
        font = UIManager.getFont("Label.font")
        foreground = UIManager.getColor("Label.foreground")
    }

    private fun muted(): Color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY

    companion object {
        private val ERROR = Color(0xE5534B)
    }
}
