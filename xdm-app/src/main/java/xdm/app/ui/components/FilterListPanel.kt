package xdm.app.ui.components


import xdm.app.I8N.text
import xdm.app.utils.createSVGIcon
import java.awt.Color
import java.awt.Component
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

class FilterListPanel(
    val stateChanged: (FilterState) -> Unit,
    val categoryChanged: (FilterCategory) -> Unit
) :
    JPanel() {
    private val jsp: JScrollPane

    init {
        val stateFilterModel = DefaultListModel<FilterItem>()
        val stateFilterList = JList(stateFilterModel)
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.All, text("CAT_ALL"),
                makeIcon("arrow-down-circle-fill.svg", INDIGO),
                makeIcon("arrow-down-circle-fill.svg", INDIGO)
            )
        )
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.Incomplete,
                text("CAT_INCOMPLETE"),
                makeIcon("progress-2-fill.svg", EMERALD),
                makeIcon("progress-2-fill.svg", EMERALD)
            )
        )
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.Completed,
                text("CAT_FINISHED"),
                makeIcon("checkbox-circle-fill.svg", AMBER),
                makeIcon("checkbox-circle-fill.svg", AMBER)
            )
        )
        stateFilterList.isOpaque = false
        stateFilterList.cellRenderer = FilterListRenderer()
        stateFilterList.alignmentX = 0f

        val catFilterModel = DefaultListModel<FilterItem>()
        for ((type, iconName, color) in listOf(
            Triple(FilterCategory.All, "archive-2-fill.svg", VIOLET),
            Triple(FilterCategory.Docs, "file-list-2-fill.svg", SKY),
            Triple(FilterCategory.Zip, "file-zip-fill.svg", ORANGE),
            Triple(FilterCategory.Music, "mv-fill.svg", PINK),
            Triple(FilterCategory.Video, "movie-fill.svg", RED),
            Triple(FilterCategory.Apps, "microsoft-fill.svg", TEAL)
        )) {
            catFilterModel.addElement(
                FilterItem.Category(
                    type,
                    text(type.text),
                    makeIcon(iconName, color),
                    makeIcon(iconName, color)
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

        stateFilterList.addListSelectionListener {
            val index = stateFilterList.selectedIndex
            if (index != -1) {
                val state = stateFilterModel[index] as FilterItem.State
                stateChanged(state.state)
            }
        }

        catFilterList.addListSelectionListener {
            val index = catFilterList.selectedIndex
            if (index != -1) {
                val category = catFilterModel[index] as FilterItem.Category
                categoryChanged(category.category)
            }
        }
    }

    private fun makeIcon(icon: String, color: Color): Icon {
        return createSVGIcon(icon, 20, color)
    }

    val component: Component
        get() = this.jsp

    companion object {
        // Vibrant, mid-luminance hues (Tailwind 500-ish) that keep good
        // contrast on both light and dark theme backgrounds.
        private val INDIGO = Color(0x63, 0x66, 0xF1)
        private val EMERALD = Color(0x10, 0xB9, 0x81)
        private val AMBER = Color(0xF5, 0x9E, 0x0B)
        private val VIOLET = Color(0x8B, 0x5C, 0xF6)
        private val SKY = Color(0x0E, 0xA5, 0xE9)
        private val ORANGE = Color(0xF9, 0x73, 0x16)
        private val PINK = Color(0xEC, 0x48, 0x99)
        private val RED = Color(0xEF, 0x44, 0x44)
        private val TEAL = Color(0x14, 0xB8, 0xA6)
    }
}
