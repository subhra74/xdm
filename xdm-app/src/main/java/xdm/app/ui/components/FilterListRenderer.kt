package xdm.app.ui.components


import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.px
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * One sidebar row: icon (or category dot) and label on the left, the item count on the right, on a
 * rounded pill when the row is selected or hovered. Colors come from [AppTheme].
 *
 * [countOf] is the number shown on the right (null hides it). The hovered row is read from the
 * list's [HOVER_INDEX] client property.
 */
class FilterListRenderer(private val countOf: (FilterItem) -> Int?) : ListCellRenderer<FilterItem> {
    companion object {
        const val HOVER_INDEX = "xdm.hoverIndex"
    }

    private val label = JLabel().apply {
        iconTextGap = 8 // FlatLaf scales iconTextGap itself
    }
    private val count = JLabel().apply {
        font = font.deriveFont(11.0f.px)
    }
    private val plainFont = label.font.deriveFont(12.5f.px)
    private val boldFont = plainFont.deriveFont(Font.BOLD)

    private val row = PillPanel().apply {
        add(label, BorderLayout.CENTER)
        add(count, BorderLayout.EAST)
        // The same 30px row (7 + 16 + 7) whatever the state, so selecting never shifts the text.
        border = ScaledEmptyBorder(9, 8, 9, 10)
    }

    override fun getListCellRendererComponent(
        list: JList<out FilterItem>,
        value: FilterItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        row.fill = when {
            isSelected -> AppTheme.sidebarSelection
            index == list.getClientProperty(HOVER_INDEX) -> AppTheme.sidebarHover
            else -> null
        }
        label.apply {
            text = value.text
            icon = if (isSelected) value.selectedIcon else value.icon
            font = plainFont//if (isSelected) boldFont else plainFont
            foreground = if (isSelected) AppTheme.sidebarSelectedForeground else AppTheme.sidebarForeground
        }
        count.apply {
            text = countOf(value)?.toString() ?: ""
            foreground = if (isSelected) AppTheme.sidebarSelectedCount else AppTheme.sidebarCount
        }
        return row
    }

    private class PillPanel : JPanel(BorderLayout()) {
        var fill: Color? = null

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val c = fill ?: return
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = c
            g2.fillRoundRect(0, 0, width, height, 14.px, 14.px)
            g2.dispose()
        }
    }
}

/**
 * The small rounded square that stands for a file type in the sidebar, padded to the width of
 * the state icons so the labels of both lists line up.
 */
class CategoryDotIcon(private val color: Color, private val boxSize: Int = 16, private val dotSize: Int = 7) : Icon {
    override fun getIconWidth(): Int = boxSize.px
    override fun getIconHeight(): Int = boxSize.px

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val d = dotSize.px
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillRoundRect(x + (iconWidth - d) / 2, y + (iconHeight - d) / 2, d, d, 6.px, 6.px)
        g2.dispose()
    }
}
