package xdm.app.ui.components


import com.formdev.flatlaf.FlatLaf
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import java.awt.Color
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

class FilterListRenderer : ListCellRenderer<FilterItem> {
    companion object {
        /** Color of the left selection bar. */
        val SELECTION_COLOR = Color(30, 144, 255)

        /**
         * Emphasized variant of a normal foreground color, used for the selected
         * row's icon and text: lighter in the dark theme, darker in the light one,
         * so the selected row stands out against its neighbours.
         */
        fun emphasize(c: Color): Color =
            if (FlatLaf.isLafDark()) blend(c, Color.WHITE, 0.45f)
            else blend(c, Color.BLACK, 0.45f)

        private fun blend(c: Color, towards: Color, amount: Float): Color = Color(
            (c.red + (towards.red - c.red) * amount).toInt(),
            (c.green + (towards.green - c.green) * amount).toInt(),
            (c.blue + (towards.blue - c.blue) * amount).toInt()
        )
    }

    private val defaultBorder = EmptyBorder(7, 20, 7, 15)
    private val selectedBorder: Border = CompoundBorder(
        EmptyBorder(7, 0, 7, 0),
        CompoundBorder(
            MatteBorder(0, 3, 0, 0, SELECTION_COLOR),
            EmptyBorder(0, 17, 0, 15)
        )
    )
    private val label: JLabel = JLabel().apply {
        icon = createIcon(RemixIcon.ARROW_UP_DOWN_FILL, 20, Color.GRAY)
        iconTextGap = 10
        border = defaultBorder
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
            // Selection is indicated by the blue left bar, not a filled background,
            // so brighten/darken the normal foreground instead of using
            // selectionForeground (unreadable on the unfilled cell in the light theme).
            foreground = if (isSelected) emphasize(list.foreground) else list.foreground
            icon = if (isSelected) value.selectedIcon else value.icon
            border = if (isSelected) selectedBorder else defaultBorder
        }
        return label
    }
}
