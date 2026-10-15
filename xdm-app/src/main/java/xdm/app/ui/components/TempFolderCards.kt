package xdm.app.ui.components

import xdm.app.I8N.text
import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Window
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JPanel

/**
 * Puts a [TempFolderPanel] card behind a download dialog's form, so changing the temp folder happens
 * in the same always-on-top window instead of a modal dialog stacked over it.
 *
 * The dialog's existing content pane becomes the first card. Each switch re-packs the dialog to the
 * card now showing, keeping its centre where it was: the form gets its own size back afterwards
 * through [fitMain] (the dialog's usual pack-plus-minimum), and the card is never sized by the other.
 *
 * @param fitMain sizes the dialog for the form, as it does when it first opens.
 * @param onSaved runs after the user saved a new temp folder, back on the form.
 */
class TempFolderCards(
    private val dialog: JDialog,
    private val fitMain: () -> Unit,
    private val onSaved: () -> Unit,
) {
    private val layout = VisibleCardLayout()
    private val cards = JPanel(layout)
    private val panel = TempFolderPanel(onClose = ::close, onRelayout = { if (showingTemp) fit { fitCard() } })
    private var showingTemp = false
    private var formDefaultButton: JButton? = null
    private var formTitle: String? = null

    init {
        cards.add(dialog.contentPane, FORM)
        cards.add(panel, TEMP)
        dialog.contentPane = cards
    }

    /** Swaps the form for the temp-folder card, for a download of [needed] bytes going to [destination]. */
    fun showTempFolder(destination: String?, needed: Long?) {
        if (showingTemp) return
        panel.open(destination, needed)
        formDefaultButton = dialog.rootPane.defaultButton
        formTitle = dialog.title
        layout.show(cards, TEMP)
        showingTemp = true
        dialog.title = text("LBL_TEMP_FOLDER")
        dialog.rootPane.defaultButton = panel.okButton
        fit { fitCard() }
        panel.focusField()
    }

    private fun close(saved: Boolean) {
        layout.show(cards, FORM)
        showingTemp = false
        dialog.title = formTitle
        dialog.rootPane.defaultButton = formDefaultButton
        fit(fitMain)
        if (saved) onSaved()
    }

    private fun fitCard() {
        dialog.pack()
        clampToScreen(dialog)
    }

    /** Runs [resize], then moves the dialog so its centre stays put (and it stays on screen). */
    private fun fit(resize: () -> Unit) {
        val centre = dialog.takeIf { it.isShowing }?.let {
            Point(it.x + it.width / 2, it.y + it.height / 2)
        }
        resize()
        if (centre != null) {
            val screen = screenBounds(dialog)
            val x = (centre.x - dialog.width / 2).coerceIn(screen.x, maxOf(screen.x, screen.x + screen.width - dialog.width))
            val y = (centre.y - dialog.height / 2).coerceIn(screen.y, maxOf(screen.y, screen.y + screen.height - dialog.height))
            dialog.setLocation(x, y)
        }
        dialog.validate()
        dialog.repaint()
    }

    /** Sizes a container to the card that is showing, not the largest card as [CardLayout] does. */
    private class VisibleCardLayout : CardLayout() {
        override fun preferredLayoutSize(parent: Container): Dimension =
            withInsets(parent, visible(parent)?.preferredSize)

        override fun minimumLayoutSize(parent: Container): Dimension =
            withInsets(parent, visible(parent)?.minimumSize)

        private fun visible(parent: Container): Component? = parent.components.firstOrNull { it.isVisible }

        private fun withInsets(parent: Container, d: Dimension?): Dimension {
            val i = parent.insets
            return Dimension((d?.width ?: 0) + i.left + i.right, (d?.height ?: 0) + i.top + i.bottom)
        }
    }

    companion object {
        private const val FORM = "form"
        private const val TEMP = "temp"

        /** Keeps [window] within 90% of its screen's usable area, whatever the font size. */
        fun clampToScreen(window: Window) {
            val screen = screenBounds(window)
            val w = minOf(window.width, screen.width * 9 / 10)
            val h = minOf(window.height, screen.height * 9 / 10)
            if (w != window.width || h != window.height) window.setSize(w, h)
        }

        private fun screenBounds(window: Window): java.awt.Rectangle {
            val gc = window.graphicsConfiguration
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
            val insets = window.toolkit.getScreenInsets(gc)
            val b = gc.bounds
            return java.awt.Rectangle(
                b.x + insets.left, b.y + insets.top,
                b.width - insets.left - insets.right, b.height - insets.top - insets.bottom
            )
        }
    }
}
