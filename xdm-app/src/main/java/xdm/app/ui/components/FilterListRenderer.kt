package xdm.app.ui.components

import xdm.app.utils.AppUtils
import java.awt.Color
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

class FilterListRenderer : ListCellRenderer<FilterListItem> {
    private val defaultBorder: Border
    private val selectedBorder: Border
    private val label: JLabel

    init {
        defaultBorder = EmptyBorder(7, 20, 7, 15)
        selectedBorder =
            CompoundBorder(
                EmptyBorder(7, 0, 7, 0),
                CompoundBorder(
                    MatteBorder(0, 3, 0, 0, Color(30, 144, 255)),
                    EmptyBorder(0, 17, 0, 15)
                )
            )
        label = JLabel().apply {
            icon = AppUtils.createSVGIcon("arrow-up-down-fill.svg", 20, Color.GRAY)
            iconTextGap = 10
            border = defaultBorder
        }
    }

    override fun getListCellRendererComponent(
        list: JList<out FilterListItem>,
        value: FilterListItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        label.apply {
            text = value.text
            foreground = if (isSelected) list.selectionForeground else list.foreground
            icon = if (isSelected) value.selectedIcon else value.icon
            border = if (isSelected) selectedBorder else defaultBorder
        }
        return label
    }
}
