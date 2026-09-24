package xdm.app.ui.components


import com.formdev.flatlaf.FlatLaf
import xdm.app.ui.screens.settings.settingsAccentColor
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

class FilterListRenderer : ListCellRenderer<FilterItem> {
    companion object {
        /**
         * Emphasized variant of a normal foreground color, used for the selected
         * row's icon and text: lighter in the dark theme, darker in the light one,
         * so the selected row stands out against its neighbours.
         */
        fun emphasize(c: Color): Color =
            if (FlatLaf.isLafDark()) blend(c, Color.WHITE, 0.95f)
            else blend(c, Color.BLACK, 0.45f)

        private fun blend(c: Color, towards: Color, amount: Float): Color = Color(
            (c.red + (towards.red - c.red) * amount).toInt(),
            (c.green + (towards.green - c.green) * amount).toInt(),
            (c.blue + (towards.blue - c.blue) * amount).toInt()
        )
    }

    private val label = PillLabel().apply {
        icon = createIcon(RemixIcon.ARROW_UP_DOWN_FILL, 20, Color.GRAY)
        iconTextGap = 10
        // The same border in both states, so selecting a row never shifts its text.
        border = EmptyBorder(8, 10, 8, 15)
    }

    override fun getListCellRendererComponent(
        list: JList<out FilterItem>,
        value: FilterItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        label.apply {
            text = value.text
            selected = isSelected
            // The selection pill is a soft accent tint, not a solid fill, so the text
            // brightens/darkens from the normal foreground rather than switching to
            // selectionForeground (unreadable on the tint in the light theme).
            foreground = if (isSelected) emphasize(list.foreground) else list.foreground
            icon = if (isSelected) value.selectedIcon else value.icon
        }
        return label
    }

    /**
     * Draws the selected row as a rounded pill, shaped like the settings window's nav rail so
     * the two sidebars read as the same control.
     */
    private class PillLabel : JLabel() {
        var selected = false

        override fun paintComponent(g: Graphics) {
            if (selected) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = pillColor()
                g2.fillRoundRect(0, 2, width, height - 4, 12, 12)
                g2.dispose()
            }
            super.paintComponent(g)
        }

        /**
         * The sidebar is `Table.background`, so the pill has to come from somewhere else to be
         * visible at all. In the dark theme that is a translucent button fill, which reads as a
         * recess; in the light theme a neutral shift is too faint against an already pale
         * sidebar, so it takes the same accent tint the settings nav rail marks its selected
         * page with.
         */
        private fun pillColor(): Color =
            if (FlatLaf.isLafDark()) {
                val c1 = UIManager.getColor("Button.background")
                Color(c1.red, c1.green, c1.blue, 75) //?: settingsSurface()
            } else {
                val accent = settingsAccentColor()
                Color(accent.red, accent.green, accent.blue, 46)
            }
    }
}
