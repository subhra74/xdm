package xdm.app.ui.components


import xdm.app.AppContext
import xdm.app.DbRecord
import xdm.app.DownloadCategory
import xdm.app.I8N.text
import xdm.app.RecordStatus
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.createIcon
import xdm.app.utils.px
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import javax.swing.*
import javax.swing.border.MatteBorder

/**
 * The main window's sidebar: a "Library" section filtering by download state and a "File types"
 * section filtering by category, each row showing how many downloads it matches.
 */
class FilterListPanel(
    val stateChanged: (FilterState) -> Unit,
    val categoryChanged: (DownloadCategory?) -> Unit
) :
    JPanel() {
    private val jsp: JScrollPane
    private val catFilterModel = DefaultListModel<FilterItem>()
    private val catFilterList: JList<FilterItem>
    private val stateFilterList: JList<FilterItem>

    private var stateCounts = emptyMap<FilterState, Int>()

    /** Keyed by category id; null is "All types". */
    private var categoryCounts = emptyMap<String?, Int>()
    private var countSource: (() -> List<DbRecord>)? = null

    /** Downloads report progress many times a second; recount at most a few times a second. */
    private val countTimer = Timer(250) { recount() }.apply { isRepeats = false }

    init {
        val stateFilterModel = DefaultListModel<FilterItem>()
        stateFilterList = sidebarList(stateFilterModel)
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.All, text("CAT_ALL"),
                makeIcon(RemixIcon.ARROW_DOWN_CIRCLE_LINE, false),
                makeIcon(RemixIcon.ARROW_DOWN_CIRCLE_FILL, true)
            )
        )
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.Incomplete,
                text("CAT_INCOMPLETE"),
                makeIcon(RemixIcon.PROGRESS_2_LINE, false),
                makeIcon(RemixIcon.PROGRESS_2_FILL, true)
            )
        )
        stateFilterModel.addElement(
            FilterItem.State(
                FilterState.Completed,
                text("CAT_FINISHED"),
                makeIcon(RemixIcon.CHECKBOX_CIRCLE_LINE, false),
                makeIcon(RemixIcon.CHECKBOX_CIRCLE_FILL, true)
            )
        )

        fillCategories()
        catFilterList = sidebarList(catFilterModel)

        val box = FilterBox().apply {
            add(sectionLabel(text("CAT_SECTION_LIBRARY")))
            add(stateFilterList)
            add(Box.createVerticalStrut(14.px))
            add(sectionLabel(text("CAT_SECTION_TYPES")))
            add(catFilterList)
            border = ScaledEmptyBorder(14, 2, 14, 2)
            isOpaque = true
            background = AppTheme.sidebarBackground
        }

        jsp = JScrollPane(box).apply {
            border = MatteBorder(0, 0, 0, 1, AppTheme.sidebarBorder)
            // The rows never fill the sidebar's height, so the viewport has to carry the
            // same colour as the panel -- otherwise the strip below the last row shows the
            // scroll pane's own background instead.
            isOpaque = true
            background = AppTheme.sidebarBackground
            viewport.isOpaque = true
            viewport.background = AppTheme.sidebarBackground
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

    /**
     * Sets where the row counts come from and counts once; [scheduleRecount] then refreshes them
     * whenever the list changes. Call on the EDT.
     */
    fun countsFrom(source: () -> List<DbRecord>) {
        countSource = source
        recount()
    }

    fun scheduleRecount() {
        if (!countTimer.isRunning) countTimer.start()
    }

    private fun recount() {
        val records = countSource?.invoke() ?: return
        val finished = records.count { it.status == RecordStatus.FINISHED }
        stateCounts = mapOf(
            FilterState.All to records.size,
            FilterState.Incomplete to records.size - finished,
            FilterState.Completed to finished
        )
        val counts = HashMap<String?, Int>()
        counts[null] = records.size
        for (cat in AppContext.config.categories) {
            counts[cat.id] = records.count { it.categoryId == cat.id }
        }
        categoryCounts = counts
        stateFilterList.repaint()
        catFilterList.repaint()
    }

    private fun countOf(item: FilterItem): Int? = when (item) {
        is FilterItem.State -> stateCounts[item.state]
        is FilterItem.Category -> categoryCounts[item.category?.id]
    }

    private fun fillCategories() {
        catFilterModel.clear()
        val allDot = CategoryDotIcon(AppTheme.accent)
        catFilterModel.addElement(FilterItem.Category(null, text("CAT_ALL_TYPES"), allDot, allDot))
        for (cat in AppContext.config.categories) {
            val dot = CategoryDotIcon(AppTheme.categoryColor(cat))
            catFilterModel.addElement(FilterItem.Category(cat, cat.displayName, dot, dot))
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
        recount()
        // Notify unconditionally: when the index is unchanged the selection listener does
        // not fire, but the category behind it may have been edited, so the list still
        // needs to re-filter and repaint its icons.
        categoryChanged((catFilterModel[index] as FilterItem.Category).category)
    }

    /**
     * A sidebar list: reports an unbounded maximum width but its natural height. Inside the
     * vertical [FilterBox] a plain JList would be capped at its preferred width, leaving the
     * selection pill stopping short of the sidebar's edge; the height stays preferred so the
     * two stacked lists do not share out the leftover vertical space between them. Tracks the
     * row under the mouse for the renderer's hover pill.
     */
    private fun sidebarList(model: DefaultListModel<FilterItem>): JList<FilterItem> =
        object : JList<FilterItem>(model) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            isOpaque = false
            cellRenderer = FilterListRenderer(::countOf)
            alignmentX = 0f
            // Side padding so the pill floats clear of the panel edges.
            border = ScaledEmptyBorder(0, 10, 0, 10)
            val hover = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) = setHover(locationToIndex(e.point).takeIf {
                    it != -1 && getCellBounds(it, it).contains(e.point)
                } ?: -1)

                override fun mouseExited(e: MouseEvent) = setHover(-1)

                private fun setHover(index: Int) {
                    if (getClientProperty(FilterListRenderer.HOVER_INDEX) != index) {
                        putClientProperty(FilterListRenderer.HOVER_INDEX, index)
                        repaint()
                    }
                }
            }
            addMouseListener(hover)
            addMouseMotionListener(hover)
        }

    /** The small spaced-out uppercase caption above each section, lined up with the row text. */
    private fun sectionLabel(title: String): JLabel = JLabel(title.uppercase()).apply {
        font = font.deriveFont(Font.BOLD, 9.0f.px).deriveFont(mapOf(TextAttribute.TRACKING to 0.08f))
        foreground = AppTheme.sidebarSection
        alignmentX = 0f
        border = ScaledEmptyBorder(0, 18, 8, 10)
    }

    /** State rows draw the outlined glyph muted, and the filled one in the accent when selected. */
    private fun makeIcon(icon: RemixIcon, selected: Boolean): Icon =
        createIcon(icon, 16, if (selected) AppTheme.accent else AppTheme.sidebarIcon)

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
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 24.px
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
