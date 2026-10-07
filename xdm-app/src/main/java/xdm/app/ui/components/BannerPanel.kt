package xdm.app.ui.components

import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.createIcon
import xdm.app.utils.px
import xdm.app.utils.scaledInsets
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.border.CompoundBorder
import javax.swing.border.MatteBorder

/**
 * A slim banner for the bottom of the main window: icon, message, one action button and a dismiss
 * button. Hidden by default. Subclasses set the texts and wire [actionButton]. Must be mutated on
 * the EDT.
 */
open class BannerPanel(icon: RemixIcon, actionName: String, dismissName: String) : JPanel() {

    protected val messageLabel = JLabel()
    protected val actionButton = JButton()
    private val dismissButton = JButton("✕")

    init {
        isVisible = false
        layout = GridBagLayout()

        val accent = UIManager.getColor("ProgressBar.foreground") ?: Color(0x3B, 0x82, 0xF6)
        border = CompoundBorder(
            MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: Color.GRAY),
            ScaledEmptyBorder(8, 12, 8, 12)
        )

        val infoIcon = JLabel(createIcon(icon, 18, accent)).apply {
            verticalAlignment = SwingConstants.CENTER
        }

        messageLabel.apply {
            font = font.deriveFont(Font.PLAIN, 13f.px)
            horizontalAlignment = SwingConstants.LEFT
            verticalAlignment = SwingConstants.CENTER
        }

        actionButton.apply {
            name = actionName
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        dismissButton.apply {
            name = dismissName
            toolTipText = "Dismiss"
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = UIManager.getColor("Label.disabledForeground")
            addActionListener { dismissed() }
        }

        // One centered row: icon + message pinned left, buttons pushed right.
        // GridBagLayout vertically centers each cell so the icon/text line up
        // with the (taller) buttons instead of riding at the top.
        val gc = GridBagConstraints().apply {
            gridy = 0
            anchor = GridBagConstraints.CENTER
        }

        gc.gridx = 0
        gc.insets = scaledInsets(0, 0, 0, 8)
        add(infoIcon, gc)

        gc.gridx = 1
        gc.weightx = 1.0
        gc.fill = GridBagConstraints.HORIZONTAL
        gc.anchor = GridBagConstraints.WEST
        gc.insets = scaledInsets(0, 0, 0, 8)
        add(messageLabel, gc)

        gc.gridx = 2
        gc.weightx = 0.0
        gc.fill = GridBagConstraints.NONE
        gc.anchor = GridBagConstraints.CENTER
        gc.insets = scaledInsets(0, 0, 0, 8)
        add(actionButton, gc)

        gc.gridx = 3
        gc.insets = Insets(0, 0, 0, 0)
        add(dismissButton, gc)
    }

    /** The dismiss button was clicked. Hides the banner. */
    protected open fun dismissed() {
        setShown(false)
    }

    protected fun setShown(shown: Boolean) {
        if (isVisible == shown) return
        isVisible = shown
        parent?.revalidate()
        parent?.repaint()
    }
}
