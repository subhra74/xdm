package xdm.app.ui.components


import xdm.app.I8N.text
import xdm.app.utils.createSVGIcon
import java.awt.Color
import java.awt.Component
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

class FilterListPanel {
    private val jsp: JScrollPane

    init {
        val stateFilterModel = DefaultListModel<FilterListItem>()
        val stateFilterList = JList(stateFilterModel)
        stateFilterModel.addElement(
            FilterListItem(
                FilterItemType.ALL, text("CAT_ALL"),
                makeIcon("arrow-down-circle-fill.svg", Color.GRAY),
                makeIcon("arrow-down-circle-fill.svg", stateFilterList.selectionForeground)
            )
        )
        stateFilterModel.addElement(
            FilterListItem(
                FilterItemType.UNFINISHED,
                text("CAT_INCOMPLETE"),
                makeIcon("progress-2-fill.svg", Color.GRAY),
                makeIcon("progress-2-fill.svg", stateFilterList.selectionForeground)
            )
        )
        stateFilterModel.addElement(
            FilterListItem(
                FilterItemType.FINISHED,
                text("CAT_FINISHED"),
                makeIcon("checkbox-circle-fill.svg", Color.GRAY),
                makeIcon("checkbox-circle-fill.svg", stateFilterList.selectionForeground)
            )
        )
        stateFilterList.isOpaque = false
        stateFilterList.cellRenderer = FilterListRenderer()
        stateFilterList.alignmentX = 0f

        val catFilterModel = DefaultListModel<FilterListItem>()
        for ((type, iconName) in listOf(
            Pair(FilterItemType.CAT_ALL_TYPES, "archive-2-fill.svg"),
            Pair(FilterItemType.CAT_DOCUMENTS, "file-list-2-fill.svg"),
            Pair(FilterItemType.CAT_COMPRESSED, "file-zip-fill.svg"),
            Pair(FilterItemType.CAT_MUSIC, "mv-fill.svg"),
            Pair(FilterItemType.CAT_VIDEOS, "movie-fill.svg"),
            Pair(FilterItemType.CAT_PROGRAMS, "microsoft-fill.svg")
        )) {
            catFilterModel.addElement(
                FilterListItem(
                    type,
                    text(type.toString()),
                    makeIcon(iconName, Color.GRAY),
                    makeIcon(iconName, stateFilterList.selectionForeground)
                )
            )
        }

        val catFilterList = JList(catFilterModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            border = EmptyBorder(10, 0, 0, 0)
            isOpaque = false
            cellRenderer = FilterListRenderer()
            alignmentX = 0f
        }

        val box = Box.createVerticalBox().apply {
            add(stateFilterList)
            add(catFilterList)
            border = EmptyBorder(10, 0, 10, 10)
            isOpaque = true
            background = UIManager.getColor("Table.background")
        }

        jsp = JScrollPane(box).apply {
            border = MatteBorder(0, 0, 0, 1, Color.BLACK)
            isOpaque = false
        }

        stateFilterList.selectedIndex = 0
        catFilterList.selectedIndex = 0
    }

    private fun makeIcon(icon: String, color: Color): Icon {
        return createSVGIcon(icon, 20, color)
    }

    val component: Component
        get() = this.jsp
}
