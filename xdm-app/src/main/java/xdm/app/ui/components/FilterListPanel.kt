package xdm.app.ui.components


import com.formdev.flatlaf.FlatLaf
import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.I8N.text
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import java.awt.Color
import java.awt.Component
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

class FilterListPanel(
    val stateChanged: (FilterState) -> Unit,
    val categoryChanged: (DownloadCategory?) -> Unit
) :
    JPanel() {
    private val jsp: JScrollPane
    private val catFilterModel = DefaultListModel<FilterItem>()
    private val catFilterList: JList<FilterItem>

    init {
        val stateFilterModel = DefaultListModel<FilterItem>()
        val stateFilterList = JList(stateFilterModel)
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.All, text("CAT_ALL"),
                makeIcon(RemixIcon.ARROW_DOWN_CIRCLE_FILL, Color.gray),
                makeIcon(RemixIcon.ARROW_DOWN_CIRCLE_FILL, FilterListRenderer.emphasize(Color.gray))
            )
        )
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.Incomplete,
                text("CAT_INCOMPLETE"),
                makeIcon(RemixIcon.PROGRESS_2_FILL, Color.gray),
                makeIcon(RemixIcon.PROGRESS_2_FILL, FilterListRenderer.emphasize(Color.gray))
            )
        )
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.Completed,
                text("CAT_FINISHED"),
                makeIcon(RemixIcon.CHECKBOX_CIRCLE_FILL, Color.gray),
                makeIcon(RemixIcon.CHECKBOX_CIRCLE_FILL, FilterListRenderer.emphasize(Color.gray))
            )
        )
        stateFilterList.isOpaque = false
        stateFilterList.cellRenderer = FilterListRenderer()
        stateFilterList.alignmentX = 0f

        fillCategories()

        catFilterList = JList(catFilterModel).apply {
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
            background = if (AppContext.config.theme == "light") {
                UIManager.getColor("Panel.background")
            } else {
                UIManager.getColor("Table.background")
            }
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

    private fun fillCategories() {
        catFilterModel.clear()
        catFilterModel.addElement(
            FilterItem.Category(
                null, text("CAT_ALL_TYPES"),
                makeIcon(CategoryStyle.ALL_ICON, Color.gray),
                makeIcon(CategoryStyle.ALL_ICON, FilterListRenderer.emphasize(Color.gray))
            )
        )
        for (cat in AppContext.config.categories) {
            val glyph = CategoryStyle.iconName(cat)
            catFilterModel.addElement(
                FilterItem.Category(
                    cat, cat.displayName,
                    makeIcon(glyph, Color.gray),
                    makeIcon(glyph, FilterListRenderer.emphasize(Color.gray))
                )
            )
        }
    }

    /**
     * Rebuilds the category rows from the config after the settings window edits them.
     * Keeps the current selection when that category still exists, otherwise falls back
     * to "All types" so the list never shows a filter the user can no longer see.
     */
    fun reloadCategories() {
        val selectedId = (catFilterList.selectedValue as? FilterItem.Category)?.category?.id
        fillCategories()
        val index = (0 until catFilterModel.size())
            .firstOrNull { (catFilterModel[it] as FilterItem.Category).category?.id == selectedId }
            ?: 0
        catFilterList.selectedIndex = index
        // Notify unconditionally: when the index is unchanged the selection listener does
        // not fire, but the category behind it may have been edited, so the list still
        // needs to re-filter and repaint its icons.
        categoryChanged((catFilterModel[index] as FilterItem.Category).category)
    }

    private fun makeIcon(icon: RemixIcon, color: Color): Icon {
        return createIcon(icon, 20, color)
    }

    val component: Component
        get() = this.jsp
}
