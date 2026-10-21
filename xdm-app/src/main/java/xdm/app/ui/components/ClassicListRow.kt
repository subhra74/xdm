package xdm.app.ui.components

import xdm.app.DbRecord
import xdm.app.utils.isMacPopupTrigger
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.event.CellEditorListener
import javax.swing.event.TableModelEvent
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Row of the Classic list, used both as renderer and as the hover editor that makes the row
 * buttons live. A click anywhere but a button selects the row (Ctrl/⌘ toggles, Shift extends),
 * a double-click opens it and a right click asks for the menu. The tile always shows the file type;
 * selection is the [RowWashPanel] background.
 *
 * The editor reads its row from [JTable.getEditingRow] rather than remembering it, so it stays
 * right after keyboard navigation (which stops editing without removing this editor).
 */
class ClassicListRow(
    private val table: JTable,
    private val model: MainListViewModel? = null
) : TableCellRenderer, TableCellEditor {
    private val wash = RowWashPanel()
    private val row = DownloadRowPanel(wash)
    private var editEntry: DbRecord? = null

    var onPauseClick: ActionCallBack? by row::onPauseClick
    var onResumeClick: ActionCallBack? by row::onResumeClick
    var onOpenFolderClick: ActionCallBack? by row::onOpenFolderClick
    var onDeleteClick: ActionCallBack? by row::onDeleteClick
    var onMenuClick: ActionCallBack? by row::onMenuClick
    var onOpen: ActionCallBack? = null

    /** A right click on the row at ([x], [y]) in [invoker]; the row is already part of the selection. */
    var onContextMenu: ((invoker: Component, x: Int, y: Int) -> Unit)? = null

    private val editRow: Int
        get() = if (editEntry != null && table.isEditing) table.editingRow else -1

    init {
        model?.addTableModelListener { e: TableModelEvent ->
            val r = e.firstRow
            val vr = editRow
            if (vr == -1 || r < 0 || r >= table.rowCount) return@addTableModelListener
            if (table.convertRowIndexToView(r) == vr) {
                (model.getValueAt(r, 0) as? DbRecord)?.let { show(it, table.isRowSelected(vr)) }
            }
        }

        val shortcut = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        wash.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val vr = editRow
                if (vr == -1) return
                table.requestFocusInWindow()
                if (e.isPopupTrigger || isMacPopupTrigger(e)) {
                    showContextMenu(e, vr)
                    return
                }
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val toggle = (e.modifiersEx and shortcut) != 0
                table.changeSelection(vr, 0, toggle, e.isShiftDown)
                if (e.clickCount == 2 && !toggle && !e.isShiftDown) {
                    editEntry?.let { onOpen?.invoke(it) }
                }
            }

            // Windows raises the popup trigger on release.
            override fun mouseReleased(e: MouseEvent) {
                val vr = editRow
                if (vr != -1 && e.isPopupTrigger) showContextMenu(e, vr)
            }

            override fun mouseEntered(e: MouseEvent) {
                setHovered(true)
            }

            // Also raised when the mouse moves onto a row button; only leaving the row counts.
            override fun mouseExited(e: MouseEvent) {
                if (wash.getMousePosition(true) == null) setHovered(false)
            }
        })
    }

    private fun showContextMenu(e: MouseEvent, vr: Int) {
        if (!table.isRowSelected(vr)) table.changeSelection(vr, 0, false, false)
        onContextMenu?.invoke(e.component, e.x, e.y)
    }

    private fun setHovered(hovered: Boolean) {
        if (wash.hovered == hovered) return
        wash.hovered = hovered
        wash.repaint()
    }

    /** Called by the list when the selection changes, so the open editor follows it. */
    fun selectionChanged() {
        val vr = editRow
        if (vr == -1) return
        val selected = table.isRowSelected(vr)
        if (wash.selected != selected) {
            wash.selected = selected
            wash.repaint()
        }
    }

    val height: Int
        get() = row.height

    fun showMenuUnderButton(menu: javax.swing.JPopupMenu) {
        row.showMenuUnderButton(menu)
    }

    private fun show(ent: DbRecord, isSelected: Boolean) {
        row.showFileIcon(ent)
        row.update(ent, showButtons = true)
        wash.selected = isSelected
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
    ): Component {
        editEntry = null
        this.row.entry = null
        wash.hovered = false
        show(value as DbRecord, isSelected)
        return wash
    }

    override fun getTableCellEditorComponent(
        table: JTable, value: Any, isSelected: Boolean, row: Int, column: Int
    ): Component {
        editEntry = value as? DbRecord
        this.row.entry = editEntry
        // Only opened for the row under the mouse.
        wash.hovered = true
        editEntry?.let { show(it, isSelected) }
        return wash
    }

    override fun getCellEditorValue(): Any = 99

    override fun isCellEditable(anEvent: EventObject?): Boolean = true

    override fun shouldSelectCell(anEvent: EventObject?): Boolean = false

    override fun stopCellEditing(): Boolean = true

    override fun cancelCellEditing() {
        // Noop
    }

    override fun addCellEditorListener(l: CellEditorListener) {
        // Noop
    }

    override fun removeCellEditorListener(l: CellEditorListener) {
        // Noop
    }
}
