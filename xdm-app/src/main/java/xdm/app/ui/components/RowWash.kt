package xdm.app.ui.components

import xdm.app.utils.px
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel

/**
 * Row background of the Classic list: a soft accent card across the whole row for a selected row,
 * and a faint neutral one for the row under the mouse.
 */
class RowWashPanel : JPanel() {
    var selected = false
    var hovered = false

    override fun paintComponent(g: Graphics) {
        g.color = AppTheme.listBackground
        g.fillRect(0, 0, width, height)
        if (!selected && !hovered) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val x = 4.px
        // The row's bottom border is the gap between rows; keep the card off it.
        val h = height - 3.px
        val arc = 12.px
        g2.color = if (selected) AppTheme.listSelectionBackground else AppTheme.listHoverBackground
        g2.fillRoundRect(x, 0, width - 2 * x, h, arc, arc)
        g2.dispose()
    }
}
