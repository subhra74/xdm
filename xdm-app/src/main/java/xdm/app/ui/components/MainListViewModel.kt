package xdm.app.ui.components

import xdm.app.AppContext.db
import xdm.app.DbRecord
import xdman.ListChangeListener
import xdman.util.Logger
import javax.swing.table.AbstractTableModel

class MainListViewModel : AbstractTableModel(), ListChangeListener {
    override fun getColumnCount(): Int {
        return 1
    }

    override fun getRowCount(): Int {
        return db.size
    }

    override fun getColumnClass(c: Int): Class<*> {
        return DbRecord::class.java
    }

    override fun getValueAt(row: Int, col: Int): Any {
        return getItemAt(row)
    }

    override fun listChanged() {
        Logger.log("List changed")
        fireTableDataChanged()
    }

    override fun listItemUpdated(id: Long) {
        Logger.log("List updated")
        val index = db.indexById(id)
        if (index != null) {
            fireTableRowsUpdated(index, index)
        }
    }

    private fun getItemAt(index: Int): DbRecord {
        return db.getByIndex(index)
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        return true
    }
}
