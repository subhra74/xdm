package xdm.app.ui.components

import xdm.app.DbRecord
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.event.ListSelectionEvent

/**
 * The Classic download list, as in a file manager: click selects, Ctrl/⌘ and Shift extend,
 * double-click opens, right click shows the menu for the selection. Row buttons stay visible and
 * act on their own row only. See [ClassicListRow].
 */
class ClassicListView(
    filter: MainListViewFilter = MainListViewFilter(FilterState.All, null, "")
) : DownloadListBase(filter) {
    private val menu = DownloadRowMenu(withPrimaryActions = true)

    init {
        val renderer = ClassicListRow(table)
        val editor = ClassicListRow(table, model)
        install(renderer, editor, renderer.height)

        table.selectionModel.addListSelectionListener { e: ListSelectionEvent ->
            if (!e.valueIsAdjusting) {
                editor.selectionChanged()
                // One selected row is handled by its own buttons and menu; the toolbar's bulk
                // actions are for two or more.
                bulkSelectionCallback?.invoke(table.selectedRowCount > 1)
            }
        }

        // Rows are covered by the hover editor, so the table itself only sees clicks below the last row.
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && table.rowAtPoint(e.point) == -1) {
                    table.clearSelection()
                }
            }
        })

        editor.apply {
            onMenuClick = { entry: DbRecord -> showMenuUnderButton(menu.forRow(entry, jsp)) }
            onContextMenu = { invoker, x, y -> menu.showAt(selectedItems, invoker, x, y) }
            onOpen = { e: DbRecord -> AppMenuHandler.openFile(e, window) }
            onOpenFolderClick = { e: DbRecord -> AppMenuHandler.openFolder(e, window) }
            onPauseClick = { e: DbRecord -> AppMenuHandler.pauseDownload(e) }
            onResumeClick = { e: DbRecord -> AppMenuHandler.resumeDownload(e) }
            onDeleteClick = { e: DbRecord -> AppMenuHandler.deleteDownload(e, window) }
        }
    }
}
