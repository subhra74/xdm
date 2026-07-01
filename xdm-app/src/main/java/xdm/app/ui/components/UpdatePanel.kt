package xdm.app.ui.components

import xdm.core.util.Logger
import java.awt.Color
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.net.URI
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

/**
 * A slim banner shown at the bottom of the main window when a newer release is
 * available. Hidden by default; [showUpdate] makes it visible and wires the
 * button to open the download page. Must be mutated on the EDT.
 */
class UpdatePanel : JPanel() {

    private val messageLabel = JLabel()
    private val updateButton = JButton("Update")
    private val dismissButton = JButton("✕")
    private var downloadUrl: String = ""

    init {
        isVisible = false
        layout = GridBagLayout()

        val accent = UIManager.getColor("ProgressBar.foreground") ?: Color(0x3B, 0x82, 0xF6)
        border = CompoundBorder(
            MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: Color.GRAY),
            EmptyBorder(8, 12, 8, 12)
        )

        val infoIcon = JLabel("⬆").apply {
            font = font.deriveFont(Font.BOLD, 14f)
            foreground = accent
            verticalAlignment = SwingConstants.CENTER
        }

        messageLabel.apply {
            font = font.deriveFont(Font.PLAIN, 13f)
            horizontalAlignment = SwingConstants.LEFT
            verticalAlignment = SwingConstants.CENTER
        }

        updateButton.apply {
            name = "BTN_UPDATE_NOW"
            toolTipText = "Open the XDM downloads page"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { openDownloadPage() }
        }

        dismissButton.apply {
            name = "BTN_UPDATE_DISMISS"
            toolTipText = "Dismiss"
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = UIManager.getColor("Label.disabledForeground")
            addActionListener { isVisible = false }
        }

        // One centered row: icon + message pinned left, buttons pushed right.
        // GridBagLayout vertically centers each cell so the icon/text line up
        // with the (taller) buttons instead of riding at the top.
        val gc = GridBagConstraints().apply {
            gridy = 0
            anchor = GridBagConstraints.CENTER
        }

        gc.gridx = 0
        gc.insets = Insets(0, 0, 0, 8)
        add(infoIcon, gc)

        gc.gridx = 1
        gc.weightx = 1.0
        gc.fill = GridBagConstraints.HORIZONTAL
        gc.anchor = GridBagConstraints.WEST
        gc.insets = Insets(0, 0, 0, 8)
        add(messageLabel, gc)

        gc.gridx = 2
        gc.weightx = 0.0
        gc.fill = GridBagConstraints.NONE
        gc.anchor = GridBagConstraints.CENTER
        gc.insets = Insets(0, 0, 0, 8)
        add(updateButton, gc)

        gc.gridx = 3
        gc.insets = Insets(0, 0, 0, 0)
        add(dismissButton, gc)
    }

    /** Shows the banner with a message referencing [version] and remembers [url]. */
    fun showUpdate(version: String, url: String) {
        downloadUrl = url
        messageLabel.text = "A new version ($version) of XDM is available."
        isVisible = true
        revalidate()
        repaint()
    }

    private fun openDownloadPage() {
        if (downloadUrl.isBlank()) return
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(downloadUrl))
            }
        } catch (e: Exception) {
            Logger.error("UPDATE", "Failed to open download page: $downloadUrl", e)
        }
    }
}
