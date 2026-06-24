package xdm.app.ui.components


import com.formdev.flatlaf.FlatLaf
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
                makeIcon("arrow-down-circle-fill.svg", CategoryStyle.INDIGO),
                makeIcon("arrow-down-circle-fill.svg", CategoryStyle.INDIGO)
            )
        )
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.Incomplete,
                text("CAT_INCOMPLETE"),
                makeIcon("progress-2-fill.svg", CategoryStyle.EMERALD),
                makeIcon("progress-2-fill.svg", CategoryStyle.EMERALD)
            )
        )
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.Completed,
                text("CAT_FINISHED"),
                makeIcon("checkbox-circle-fill.svg", CategoryStyle.AMBER),
                makeIcon("checkbox-circle-fill.svg", CategoryStyle.AMBER)
            )
        )
        stateFilterList.isOpaque = false
        stateFilterList.cellRenderer = FilterListRenderer()
        stateFilterList.alignmentX = 0f

        val catFilterModel = DefaultListModel<FilterItem>()
        for (type in FilterCategory.values()) {
            val icon = makeIcon(CategoryStyle.iconName(type), CategoryStyle.color(type))
            catFilterModel.addElement(
                FilterItem.Category(type, text(type.text), icon, icon)
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

        // Keep the black divider in the dark theme (as before); use FlatLaf's
        // border color in the light theme so it isn't a harsh black line.
        val dividerColor = if (FlatLaf.isLafDark()) {
            Color.BLACK
        } else {
            UIManager.getColor("Component.borderColor") ?: Color.GRAY
        }
        jsp = JScrollPane(box).apply {
            border = MatteBorder(0, 0, 0, 1, dividerColor)
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
}
