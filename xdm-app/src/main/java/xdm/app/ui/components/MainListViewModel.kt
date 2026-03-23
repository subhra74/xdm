package xdm.app.ui.components

import xdm.app.AppContext.db
import xdm.app.DbRecord
import xdm.app.ListChangeListener
import xdm.core.util.Logger
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
        Logger.info("List changed")
        fireTableDataChanged()
    }

    override fun listItemUpdated(id: Long) {
        Logger.info("List updated")
        val index = db.indexById(id)
        if (index != null) {
            fireTableRowsUpdated(index, index)
        }
    }

    fun getItemAt(index: Int): DbRecord {
        return db.getByIndex(index)
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        return true
    }
}
