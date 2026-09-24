package xdm.app.ui.components


import com.formdev.flatlaf.FlatLaf
import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.I8N.text
import xdm.app.ui.screens.settings.settingsAccentColor
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
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
        val stateFilterList = stretchingList(stateFilterModel)
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.All, text("CAT_ALL"),
                makeIcon(RemixIcon.ARROW_DOWN_CIRCLE_FILL, Color.gray),
                makeIcon(RemixIcon.ARROW_DOWN_CIRCLE_FILL, selectedIconColor())
            )
        )
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.Incomplete,
                text("CAT_INCOMPLETE"),
                makeIcon(RemixIcon.PROGRESS_2_FILL, Color.gray),
                makeIcon(RemixIcon.PROGRESS_2_FILL, selectedIconColor())
            )
        )
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.Completed,
                text("CAT_FINISHED"),
                makeIcon(RemixIcon.CHECKBOX_CIRCLE_FILL, Color.gray),
                makeIcon(RemixIcon.CHECKBOX_CIRCLE_FILL, selectedIconColor())
            )
        )
        stateFilterList.isOpaque = false
        stateFilterList.cellRenderer = FilterListRenderer()
        stateFilterList.alignmentX = 0f
        // Side padding so the selection pill floats clear of the panel edges.
        stateFilterList.border = EmptyBorder(0, 13, 0, 13)

        fillCategories()

        catFilterList = stretchingList(catFilterModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            border = EmptyBorder(10, 13, 0, 13)
            isOpaque = false
            cellRenderer = FilterListRenderer()
            alignmentX = 0f
        }

        val sidebarBackground = if (AppContext.config.theme == "light") {
            UIManager.getColor("Panel.background")
        } else {
            UIManager.getColor("Table.background")
        }

        val box = FilterBox().apply {
            add(stateFilterList)
            add(catFilterList)
            border = EmptyBorder(10, 0, 10, 0)
            isOpaque = true
            background = sidebarBackground
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
            // The rows never fill the sidebar's height, so the viewport has to carry the
            // same colour as the panel -- otherwise the strip below the last row shows the
            // scroll pane's own background instead.
            isOpaque = true
            background = sidebarBackground
            viewport.isOpaque = true
            viewport.background = sidebarBackground
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
                makeIcon(CategoryStyle.ALL_ICON, selectedIconColor())
            )
        )
        for (cat in AppContext.config.categories) {
            val glyph = CategoryStyle.iconName(cat)
            catFilterModel.addElement(
                FilterItem.Category(
                    cat, cat.displayName,
                    makeIcon(glyph, Color.gray),
                    makeIcon(glyph, selectedIconColor())
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

    /**
     * A list that reports an unbounded maximum width but its natural height. Inside the
     * vertical [FilterBox] a plain JList would be capped at its preferred width, leaving the
     * selection pill stopping short of the sidebar's edge; the height stays preferred so the
     * two stacked lists do not share out the leftover vertical space between them.
     */
    private fun stretchingList(model: DefaultListModel<FilterItem>): JList<FilterItem> =
        object : JList<FilterItem>(model) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    /**
     * Color of a selected row's glyph. The dark theme brightens the normal gray, as it always
     * has; the light theme uses the accent, so the selected icon matches the settings window's
     * selected nav entry.
     */
    private fun selectedIconColor(): Color =
        if (FlatLaf.isLafDark()) FilterListRenderer.emphasize(Color.gray) else settingsAccentColor()

    private fun makeIcon(icon: RemixIcon, color: Color): Icon {
        return createIcon(icon, 20, color)
    }

    /**
     * Stretches to the scroll pane's width instead of to the lists' preferred width, so a
     * selected row's pill spans the whole sidebar (less the lists' own side padding) rather
     * than stopping at the end of the longest label.
     */
    private class FilterBox : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 24
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            visibleRect.height

        override fun getScrollableTracksViewportWidth(): Boolean = true

        /** Fill the viewport when the rows are shorter than it, but scroll when they are not. */
        override fun getScrollableTracksViewportHeight(): Boolean =
            (parent as? JViewport)?.let { it.height > preferredSize.height } ?: false
    }

    val component: Component
        get() = this.jsp
}
