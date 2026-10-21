package xdm.app.ui.components

import xdm.app.AppContext
import xdm.app.DbRecord
import xdm.app.DownloadCategory
import xdm.app.I8N.text
import xdm.app.ui.screens.ClearDownloadsDialog
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.createIcon
import xdm.app.utils.px
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.TableModelEvent
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * What both download lists share and that has nothing to do with clicks: the table over [db], its
 * sorter and filter, the empty placeholder, and the hover editor that makes the row under the
 * mouse live. Each subclass brings its own row ([install]) and its own mouse and selection rules.
 */
abstract class DownloadListBase(final override val filter: MainListViewFilter) : DownloadListView {
    protected val model: MainListViewModel = MainListViewModel()
    protected val table: JTable = JTable(model)
    protected val jsp: JScrollPane
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout).apply {
        background = AppTheme.listBackground
    }
    private var editingRow = -1
    override var bulkSelectionCallback: ((Boolean) -> Unit)? = null

    private val sorter: TableRowSorter<MainListViewModel>
    private val emptyListIcon = createIcon(RemixIcon.BOOK_SHELF_LINE, 96, AppTheme.emptyIconColor)
    private val noMatchIcon = createIcon(RemixIcon.SEARCH_LINE, 96, AppTheme.emptyIconColor)
    private val emptyLabel = JLabel("", SwingConstants.CENTER)

    protected val window: Window? get() = SwingUtilities.windowForComponent(jsp)

    init {
        table.addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val row = table.rowAtPoint(e.point)
                    if (row != -1) {
                        if (editingRow != row || !table.isEditing) {
                            table.editCellAt(row, 0)
                            editingRow = row
                        }
                    } else {
                        // Forget the row too: coming back up from the empty space below the list
                        // re-enters the same last row, which must open the editor again.
                        resetEditor()
                    }
                }
            })

        table.tableHeader = null
        table.fillsViewportHeight = true
        table.background = AppTheme.listBackground

        val keys =
            mutableListOf(RowSorter.SortKey(0, SortOrder.DESCENDING))
        sorter = TableRowSorter(model).apply {
            setComparator(0, DownloadSorter())
            sortKeys = keys
        }
        sorter.rowFilter = filter
        table.rowSorter = sorter
        sorter.sort()

        jsp = JScrollPane(table).apply {
            viewportBorder = ScaledEmptyBorder(5, 0, 0, 0)
            border = ScaledEmptyBorder(0, 0, 0, 0)
            autoscrolls = true
        }

        installListKeys(
            table,
            onOpen = { AppMenuHandler.openFile(selectedItems.firstOrNull(), window) },
            onDelete = { AppMenuHandler.deleteSelectedDownloads(selectedItems, window) }
        )

        // The hover editor keeps painting the record it was opened on. When rows are added, removed or
        // re-sorted, JTable moves it to wherever its model index now points, so a deleted download stayed
        // visible over the row that took its place. Drop it (added after JTable's own listener, so this
        // runs first) and re-open it under the mouse once the table has caught up.
        model.addTableModelListener { e: TableModelEvent ->
            if (e.type == TableModelEvent.UPDATE && e.lastRow != Int.MAX_VALUE) return@addTableModelListener
            resetEditor()
            SwingUtilities.invokeLater { editRowUnderMouse() }
        }

        // A faint relief on the list background.
        emptyLabel.apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalTextPosition = SwingConstants.BOTTOM
            horizontalTextPosition = SwingConstants.CENTER
            foreground = Color.GRAY
            border = ScaledEmptyBorder(0, 0, 90, 0)
            font = font.deriveFont(Font.PLAIN, 14.0f.px)
        }
        val emptyPanel = JPanel(BorderLayout()).apply {
            background = AppTheme.listBackground
            add(emptyLabel, BorderLayout.CENTER)
        }
        cardPanel.add(emptyPanel, "EMPTY")
        cardPanel.add(jsp, "LIST")
        // Filter, search and sort changes re-filter the view without touching the model.
        sorter.addRowSorterListener { updateCard() }
        updateCard()
    }

    /** Sets the row [renderer] and hover [editor]; called once from the subclass constructor. */
    protected fun install(renderer: TableCellRenderer, editor: TableCellEditor, rowHeight: Int) {
        table.rowHeight = rowHeight
        table.setDefaultRenderer(Any::class.java, renderer)
        table.setDefaultEditor(Any::class.java, editor)
    }

    /** The row editors do not notify JTable when they stop, so the editor must be removed directly. */
    private fun resetEditor() {
        if (table.isEditing) {
            table.removeEditor()
        }
        editingRow = -1
    }

    private fun editRowUnderMouse() {
        if (table.isEditing) return
        val p = table.getMousePosition(true) ?: return
        val row = table.rowAtPoint(p)
        if (row != -1) {
            table.editCellAt(row, 0)
            editingRow = row
        }
    }

    /** Shows the list, or a placeholder when there are no downloads at all or none pass the filter. */
    private fun updateCard() {
        when {
            model.rowCount == 0 -> showEmpty(text("MSG_NO_DOWNLOAD"), emptyListIcon)
            table.rowCount == 0 -> showEmpty(text("MSG_NO_MATCHING_DOWNLOAD"), noMatchIcon)
            else -> cardLayout.show(cardPanel, "LIST")
        }
    }

    private fun showEmpty(message: String, icon: Icon) {
        emptyLabel.text = message
        emptyLabel.icon = icon
        cardLayout.show(cardPanel, "EMPTY")
    }

    override fun rowUpdated(index: Int) {
        model.fireTableRowsUpdated(index, index)
        updateCard()
    }

    override fun rowDeleted(index: Int) {
        model.fireTableDataChanged()
        updateCard()
    }

    override fun rowsRemoved() {
        // Not sorter.modelStructureChanged(): that clears the sort keys and comparator, leaving the
        // list in insertion order and every later sort() a no-op.
        model.listChanged()
        updateCard()
    }

    override fun rowAdded(index: Int) {
        model.fireTableRowsInserted(index, index)
        updateCard()
    }

    override val component: Component
        get() = cardPanel

    override val records: List<DbRecord>
        get() = (0 until model.rowCount).mapNotNull { model.getItemAt(it) }

    override fun addRowsChangedListener(listener: () -> Unit) {
        model.addTableModelListener { listener() }
    }

    override val selectedItems: List<DbRecord>
        get() = table.selectedRows.asList().mapNotNull { model.getItemAt(table.convertRowIndexToModel(it)) }

    override val selectedRowCount: Int
        get() = table.selectedRowCount

    override fun filterStateChanged(state: FilterState) {
        filter.filterState = state
        model.fireTableDataChanged()
    }

    override fun filterCategoryChanged(category: DownloadCategory?) {
        filter.filterCategory = category
        model.fireTableDataChanged()
    }

    override fun searchTextChanged(text: String) {
        filter.searchText = text
        model.fireTableDataChanged()
    }

    override fun sort(sortKey: SortKey, ascending: Boolean) {
        AppContext.config.sortKey = sortKey
        AppContext.config.sortAscending = ascending
        AppContext.config.save()
        sorter.sort()
        model.fireTableDataChanged()
    }

    override fun clear() {
        val window = SwingUtilities.windowForComponent(cardPanel) as? JFrame
        ClearDownloadsDialog(window).isVisible = true
    }
}
