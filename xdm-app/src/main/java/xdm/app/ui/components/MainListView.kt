package xdm.app.ui.components


import xdm.app.AppContext
import xdm.app.DbRecord
import xdm.app.I8N.text
import xdm.app.RecordStatus
import xdm.app.utils.createSVGIcon
import xdm.app.utils.isMacPopupTrigger
import xdm.app.utils.setClipBoardText
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.TaskInfoDB
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.ChangeEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.table.TableRowSorter

class MainListView {
    private val model: MainListViewModel = MainListViewModel()
    private val table: JTable = JTable(model)
    private val jsp: JScrollPane
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private var editingRow = -1
    private val contextMenu: JPopupMenu

    //    private lateinit var mSaveAs: JMenuItem
    private lateinit var mRefresh: JMenuItem
    private lateinit var mSchedule: JMenuItem
    private lateinit var mProgress: JMenuItem
    private lateinit var mCopyUrl: JMenuItem

    //    private lateinit var mCopyFile: JMenuItem
    private lateinit var mProperty: JMenuItem
    var selectModeCallback: ((Boolean) -> Unit)? = null

    private val filter = MainListViewFilter(FilterState.All, FilterCategory.All, "")
    private val sorter: TableRowSorter<MainListViewModel>

    init {
        this.contextMenu = createContextMenu(this.table)
        table.addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val row = table.rowAtPoint(e.point)
                    if (row != -1) {
                        if (editingRow != row) {
                            table.editCellAt(row, 0)
                            editingRow = row
                        }
                    } else {
                        table.editingCanceled(ChangeEvent(table))
                    }
                }
            })
        val renderer = MainListViewRow(table)
        val editor =
            MainListViewRow(table, model).apply { onMenuClick = ({ entry: DbRecord -> showMenu(entry, this) }) }

        table.tableHeader = null
        table.rowHeight = renderer.height
        table.setDefaultRenderer(Any::class.java, renderer)
        table.setDefaultEditor(Any::class.java, editor)
        table.fillsViewportHeight = true
        table.background = UIManager.getColor("Panel.background")


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
            viewportBorder = EmptyBorder(5, 0, 0, 0)
            border = EmptyBorder(0, 0, 0, 0)
            autoscrolls = true
        }

        table.selectionModel.addListSelectionListener { e: ListSelectionEvent ->
            if (!e.valueIsAdjusting && selectModeCallback != null) {
                selectModeCallback?.invoke(table.selectedRowCount > 0)
            }
        }

        editor.apply {
            onOpenFileClick = (
                    { e: DbRecord? -> AppMenuHandler.openFile(e, SwingUtilities.windowForComponent(jsp)) })
            onOpenFolderClick = (
                    { e: DbRecord? -> AppMenuHandler.openFolder(e, SwingUtilities.windowForComponent(jsp)) })
            onPauseClick = ({ ent: DbRecord? -> AppMenuHandler.pauseDownload(ent) })
            onResumeClick = ({ ent: DbRecord? -> AppMenuHandler.resumeDownload(ent) })
            onDeleteClick = (
                    { e: DbRecord? -> AppMenuHandler.deleteDownload(e, SwingUtilities.windowForComponent(jsp)) })
        }

        val emptyLabel = JLabel(text("MSG_NO_DOWNLOAD"), SwingConstants.CENTER).apply {
            icon = createSVGIcon("sparkling-2-fill.svg", 96, UIManager.getColor("Table.background"))
            horizontalAlignment = SwingConstants.CENTER
            verticalTextPosition = SwingConstants.BOTTOM
            horizontalTextPosition = SwingConstants.CENTER
            foreground = Color.GRAY
            border = EmptyBorder(0, 0, 90, 0)
        }
        val emptyPanel = JPanel(BorderLayout()).apply {
            add(emptyLabel, BorderLayout.CENTER)
        }
        cardPanel.add(emptyPanel, "EMPTY")
        cardPanel.add(jsp, "LIST")
        updateCard()
    }

    private fun updateCard() {
        if (model.rowCount == 0) {
            cardLayout.show(cardPanel, "EMPTY")
        } else {
            cardLayout.show(cardPanel, "LIST")
        }
    }

    private fun showMenu(entry: DbRecord, editor: MainListViewRow) {
        prepareMenu(this.contextMenu, entry)
        editor.showPopupMenu(this.contextMenu)
    }

    fun rowUpdated(index: Int) {
        model.fireTableRowsUpdated(index, index)
        updateCard()
    }

    fun rowDeleted(index: Int) {
        model.fireTableDataChanged()
        updateCard()
    }

    fun rowAdded(index: Int) {
        if (table.isEditing) {
            table.cellEditor.cancelCellEditing()
        }
        model.fireTableRowsInserted(index, index)
        updateCard()
    }

    val component: Component
        get() = cardPanel

    val selectedRows: IntArray
        get() = table.selectedRows

    val selectedItems: List<DbRecord>
        get() = table.selectedRows.map { model.getItemAt(table.convertRowIndexToModel(it)) }

    val selectedRowCount: Int
        get() = table.selectedRowCount

    private fun createMenuListener(): ActionListener {
        return ActionListener { e: ActionEvent ->
            val name = (e.source as? JComponent)?.name
            val ent = contextMenu.getClientProperty("menu.context") as? DbRecord
            if (ent != null) {
                when (name) {
                    "CTX_SAVE_AS" -> {}
                    "MENU_REFRESH_LINK" -> AppContext.app.showRefreshWindow(ent.id)
                    "MENU_SCHEDULE" -> AppContext.app.showSchedulerWindow(ent.id)
                    "LBL_SHOW_PROGRESS" -> AppContext.app.showProgressWindow(ent.id, ent.fileName)
                    "CTX_COPY_URL" -> AppMenuHandler.copyUrl(ent)
                    "CTX_COPY_FILE" -> {}
                    "MENU_PROPERTIES" -> AppContext.app.showPropertiesWindow(ent)
                }
            }
        }
    }

    private fun createContextMenu(table: JTable): JPopupMenu {
        val a = createMenuListener()

        val ctx = JPopupMenu()
//        mSaveAs = addMenuItem("CTX_SAVE_AS", ctx, a)
        mRefresh = addMenuItem("MENU_REFRESH_LINK", ctx, a)
        mSchedule = addMenuItem("MENU_SCHEDULE", ctx, a)
        mProgress = addMenuItem("LBL_SHOW_PROGRESS", ctx, a)
        mCopyUrl = addMenuItem("CTX_COPY_URL", ctx, a)
//        mCopyFile = addMenuItem("CTX_COPY_FILE", ctx, a)
        mProperty = addMenuItem("MENU_PROPERTIES", ctx, a)
        table.addMouseListener(
            object : MouseAdapter() {
                override fun mouseReleased(me: MouseEvent) {
                    if (me.button == MouseEvent.BUTTON3 || SwingUtilities.isRightMouseButton(me)
                        || me.isPopupTrigger
                        || isMacPopupTrigger(me)
                    ) {
                        if (table.rowCount < 1) return
                        if (table.selectedRows.isNotEmpty()) {
                            ctx.show(table, me.x, me.y)
                        }
                    }
                }
            })
        return ctx
    }

    private fun prepareMenu(contextMenu: JPopupMenu, entry: DbRecord) {
//        mSaveAs.isVisible = entry.status != RecordStatus.FINISHED
        mRefresh.isVisible = entry.status == RecordStatus.PAUSED && !AppContext.refreshLinkInProgress.get()
        mSchedule.isVisible = entry.status == RecordStatus.PAUSED
        mProgress.isVisible = entry.status == RecordStatus.DOWNLOADING
//        mCopyFile.isVisible = entry.status == RecordStatus.FINISHED
        contextMenu.putClientProperty("menu.context", entry)
    }

    private fun addMenuItem(id: String, menu: JComponent, a: ActionListener): JMenuItem {
        val mItem = JMenuItem(text(id)).apply {
            name = id
            addActionListener(a)
        }
        menu.add(mItem)
        return mItem
    }

    fun filterStateChanged(state: FilterState) {
        filter.filterState = state
        model.fireTableDataChanged()
    }

    fun filterCategoryChanged(category: FilterCategory) {
        filter.filterCategory = category
        model.fireTableDataChanged()
    }

    fun searchTextChanged(text: String) {
        filter.searchText = text
        model.fireTableDataChanged()
    }

    fun sort(sortKey: SortKey, ascending: Boolean) {
        AppContext.config.sortKey = sortKey
        AppContext.config.sortAscending = ascending
        AppContext.config.save()
        sorter.sort()
        model.fireTableDataChanged()
    }

    fun clear() {
        val window = SwingUtilities.windowForComponent(cardPanel) as? JFrame
        if (MessageBox.confirm(window, text("TOOL_CLEAR"), text("MSG_CLEAR_CONFIRM"))) {
            AppContext.db.clear()
            sorter.modelStructureChanged()
            model.listChanged()
            updateCard()
        }
    }
}
