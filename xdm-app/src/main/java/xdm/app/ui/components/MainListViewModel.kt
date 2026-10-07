package xdm.app.ui.components

import xdm.app.AppContext.db
import xdm.app.DbRecord
import xdm.app.ListChangeListener
import xdm.app.RecordStatus
import xdm.core.downloaders.DownloadType
import xdm.core.util.Logger
import javax.swing.SwingUtilities
import javax.swing.event.TableModelEvent
import javax.swing.table.AbstractTableModel

/**
 * Table model over [db]. Downloads are added to / removed from [db] on background threads and the
 * view is notified afterwards on the EDT, so the live `db.size` can run ahead of what the
 * TableRowSorter knows. Reporting that live size made the sorter index rows it had no mapping for
 * (IndexOutOfBoundsException / NPE on viewToModel while painting, leaving the window blank).
 *
 * The model therefore reports [rows], a count owned by the EDT that only changes together with the
 * event the sorter receives, and never indexes past the current [db] contents.
 */
class MainListViewModel : AbstractTableModel(), ListChangeListener {
    private var rows = db.size

    override fun getColumnCount(): Int {
        return 1
    }

    override fun getRowCount(): Int {
        return rows
    }

    override fun getColumnClass(c: Int): Class<*> {
        return DbRecord::class.java
    }

    /** A row the sorter still knows about may already be gone from [db]; paint a blank row until the refresh arrives. */
    override fun getValueAt(row: Int, col: Int): Any {
        return getItemAt(row) ?: PLACEHOLDER
    }

    override fun listChanged() {
        Logger.info("List changed. row count: ${db.size}")
        fireTableDataChanged()
    }

    override fun listItemUpdated(id: Long) {
        Logger.info("List updated")
        val index = db.indexById(id)
        if (index != null) {
            fireTableRowsUpdated(index, index)
        }
    }

    /** Null when the row was removed from [db] but the view has not been refreshed yet. */
    fun getItemAt(index: Int): DbRecord? {
        return db.getOrNull(index)
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        return true
    }

    /**
     * Every fireTable* call ends here. Runs on the EDT only and keeps [rows] in step with the event:
     * an insert at the current end is applied incrementally, an in-range row update passes through,
     * and anything else (deletes, out-of-order or stale indices, full refreshes) becomes a full
     * refresh with [rows] re-read from [db].
     */
    override fun fireTableChanged(e: TableModelEvent) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { fireTableChanged(e) }
            return
        }
        val size = db.size
        val count = e.lastRow - e.firstRow + 1
        val event = when {
            e.firstRow == TableModelEvent.HEADER_ROW -> {
                rows = size
                e
            }
            e.type == TableModelEvent.INSERT && e.firstRow == rows && rows + count <= size -> {
                rows += count
                e
            }
            e.type == TableModelEvent.UPDATE && e.lastRow != Int.MAX_VALUE && e.lastRow < rows -> e
            else -> {
                rows = size
                TableModelEvent(this)
            }
        }
        super.fireTableChanged(event)
    }

    private companion object {
        val PLACEHOLDER = DbRecord(
            id = -1, size = 0, downloaded = 0, progress = 0, date = 0, fileName = "", eta = 0, speed = 0f,
            selected = false, status = RecordStatus.FINISHED, downloadType = DownloadType.Http
        )
    }
}
