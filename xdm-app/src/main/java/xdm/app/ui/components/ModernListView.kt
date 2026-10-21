package xdm.app.ui.components

import xdm.app.DbRecord
import javax.swing.event.ListSelectionEvent

/**
 * The Modern download list: the icon tile is a check box, clicking the title opens the file, and
 * selecting anything switches every row into selection mode. See [ModernListRow].
 */
class ModernListView(
    filter: MainListViewFilter = MainListViewFilter(FilterState.All, null, "")
) : DownloadListBase(filter) {
    private val menu = DownloadRowMenu(withPrimaryActions = false)

    init {
        val renderer = ModernListRow(table)
        val editor = ModernListRow(table, model)
        install(renderer, editor, renderer.height)

        // Selection mode changes every row (check box tiles, hidden buttons), not only the rows
        // whose selection changed, so repaint all of them when it starts or ends.
        var wasSelecting = false
        table.selectionModel.addListSelectionListener { e: ListSelectionEvent ->
            if (!e.valueIsAdjusting) {
                val selecting = table.selectedRowCount > 0
                if (selecting != wasSelecting) {
                    wasSelecting = selecting
                    table.repaint()
                }
                bulkSelectionCallback?.invoke(selecting)
            }
        }

        editor.apply {
            onMenuClick = { entry: DbRecord -> showPopupMenu(menu.forRow(entry, jsp)) }
            onOpenFileClick = ({ e: DbRecord? -> AppMenuHandler.openFile(e, window) })
            onOpenFolderClick = ({ e: DbRecord? -> AppMenuHandler.openFolder(e, window) })
            onPauseClick = ({ ent: DbRecord? -> AppMenuHandler.pauseDownload(ent) })
            onResumeClick = ({ ent: DbRecord? -> AppMenuHandler.resumeDownload(ent) })
            onDeleteClick = ({ e: DbRecord? -> AppMenuHandler.deleteDownload(e, window) })
        }
    }
}
