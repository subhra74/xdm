package xdm.app.ui.components

import com.formdev.flatlaf.ui.FlatComboBoxUI
import java.awt.Point
import java.awt.dnd.DragSource
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import javax.swing.JComponent
import javax.swing.plaf.ComponentUI
import javax.swing.plaf.basic.ComboPopup
import kotlin.math.abs

/**
 * FlatLaf's combo box with one fix for native Wayland (JBR `WLToolkit`), installed only there
 * (`AppMain.setupTheme`).
 *
 * The popup opens on mouse press. KWin may slide it to fit the screen so that it covers the pointer,
 * and once it is mapped the release of that same click goes to the popup's list, which Swing takes as
 * picking the item under the pointer: the popup flickers shut and the selection changes. Whether the
 * popup maps before the release is a race, so it happens only now and then. Under X11 the release
 * stays with the combo box (implicit grab), and GNOME does not move the popup under the pointer.
 *
 * The popup drops a release on the list that ends the opening press: no press on the list since, and
 * the pointer still where it went down. A click on an item has its own press, and press-drag-release
 * moves the pointer, so both still select.
 */
class WaylandComboBoxUI : FlatComboBoxUI() {
    override fun createPopup(): ComboPopup = WaylandComboPopup()

    private inner class WaylandComboPopup : FlatComboPopup(comboBox) {
        /** Screen point of the press on the combo box or its arrow that may have opened the popup. */
        private var openingPress: Point? = null

        override fun createMouseListener(): MouseListener {
            val delegate = super.createMouseListener()
            return object : MouseListener by delegate {
                override fun mousePressed(e: MouseEvent) {
                    openingPress = e.locationOnScreen
                    delegate.mousePressed(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    openingPress = null
                    delegate.mouseReleased(e)
                }
            }
        }

        override fun createListMouseListener(): MouseListener {
            val delegate = super.createListMouseListener()
            return object : MouseListener by delegate {
                override fun mousePressed(e: MouseEvent) {
                    openingPress = null
                    delegate.mousePressed(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    val press = openingPress
                    openingPress = null
                    if (press != null && isNear(press, e.locationOnScreen)) return
                    delegate.mouseReleased(e)
                }
            }
        }

        override fun hide() {
            openingPress = null
            super.hide()
        }

        private fun isNear(a: Point, b: Point): Boolean {
            val threshold = DragSource.getDragThreshold()
            return abs(a.x - b.x) <= threshold && abs(a.y - b.y) <= threshold
        }
    }

    companion object {
        // What UIManager calls; it hides FlatComboBoxUI.createUI the way a Java static would, which
        // Kotlin reports as an accidental override.
        @JvmStatic
        @Suppress("UNUSED_PARAMETER", "ACCIDENTAL_OVERRIDE")
        fun createUI(c: JComponent): ComponentUI = WaylandComboBoxUI()
    }
}
