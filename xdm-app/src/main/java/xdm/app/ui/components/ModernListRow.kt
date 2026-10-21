package xdm.app.ui.components

import xdm.app.DbRecord
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import xdm.core.util.Logger
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*
import javax.swing.event.CellEditorListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableModelEvent
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Row of the Modern list: clicking the title opens the file and the icon tile is a check box
 * (shown on hover). Once anything is selected every tile turns into a check box and the row
 * buttons hide.
 */
class ModernListRow(
    private val table: JTable,
    private val model: MainListViewModel? = null
) :
    TableCellRenderer, TableCellEditor {
    private val row = DownloadRowPanel()
    private val icon = row.icon
    private val iconBadge = row.iconBadge
    private var viewRow = -1
    private var editEntry: DbRecord? = null
    private val icoUnchecked: Icon = createIcon(RemixIcon.CHECKBOX_BLANK_LINE, 16, Color.WHITE)
    private val icoChecked: Icon = createIcon(RemixIcon.CHECKBOX_LINE, 16, Color.WHITE)

    var onPauseClick: ActionCallBack? by row::onPauseClick
    var onResumeClick: ActionCallBack? by row::onResumeClick
    var onOpenFileClick: ActionCallBack? = null
    var onOpenFolderClick: ActionCallBack? by row::onOpenFolderClick
    var onDeleteClick: ActionCallBack? by row::onDeleteClick
    var onMenuClick: ActionCallBack? by row::onMenuClick

    init {
        model?.addTableModelListener { e: TableModelEvent ->
            val r = e.firstRow
            if (r >= table.rowCount) {
                return@addTableModelListener
            }
            val vr = table.convertRowIndexToView(r)
            if (vr == viewRow && viewRow != -1) {
                val ent = model.getValueAt(r, 0) as DbRecord?
                if (ent != null) {
                    Logger.info("XDM", "state $ent")
                    updateLabelText(ent, table.isRowSelected(vr))
                }
            }
        }
        // The row under the mouse is this editor, which only reads the selection when it opens.
        // Keep it in step when the selection changes from the keyboard (select all, clear).
        if (model != null) {
            table.selectionModel.addListSelectionListener { e: ListSelectionEvent ->
                if (!e.valueIsAdjusting && viewRow != -1) {
                    editEntry?.let { updateLabelText(it, table.isRowSelected(viewRow)) }
                }
            }
        }

        icon.addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    icon.putClientProperty("old.icon", icon.icon)
                    icon.putClientProperty("old.background", iconBadge.background)
                    if (table.isRowSelected(viewRow) || table.selectedRowCount > 0) {
                        // Selection mode: the tile is already accent with white check boxes.
                        icon.icon = if (table.isRowSelected(viewRow)) icoChecked else icoUnchecked
                    } else {
                        // Outside selection mode, deepen the tile's own tint and offer the check box in its color.
                        val tile = AppTheme.tile(row.tileHue, hovered = true)
                        iconBadge.background = tile.background
                        icon.icon = row.tileIcon(RemixIcon.CHECKBOX_BLANK_LINE, tile.glyph)
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    val isSelectionMode = table.selectedRowCount > 0
                    icon.icon = if (table.isRowSelected(viewRow))
                        icoChecked
                    else
                        if (isSelectionMode) icoUnchecked else {
                            (icon.getClientProperty("old.background") as? Color)?.let { iconBadge.background = it }
                            val oldIcon = icon.getClientProperty("old.icon")
                            if (oldIcon != null) {
                                oldIcon as Icon
                            } else {
                                row.defaultIcon
                            }
                        }
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (viewRow == -1) {
                        return
                    }
                    // Focus the list so the keyboard shortcuts act on this selection.
                    table.requestFocusInWindow()
                    val wasInSelectionMode = table.selectedRowCount > 0
                    table.changeSelection(viewRow, 0, true, false)
                    val isInSelectionMode = table.selectedRowCount > 0
                    icon.icon = if (table.isRowSelected(viewRow)) icoChecked else icoUnchecked
                    updateLabelText(editEntry!!, table.isRowSelected(viewRow))
                    if (wasInSelectionMode != isInSelectionMode && model != null) {
                        table.revalidate()
                        table.repaint()
                    }
                }
            })

        row.lblTitle.cursor = Cursor(Cursor.HAND_CURSOR)
        row.lblTitle.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                if (editEntry != null) {
                    Logger.info(editEntry!!)
                    onOpenFileClick?.invoke(editEntry!!)
                }
            }
        })
    }

    val height: Int
        get() = row.height

    fun showPopupMenu(menu: JPopupMenu) {
        row.showMenuUnderButton(menu)
    }

    private fun updateLabelText(ent: DbRecord, isSelected: Boolean) {
        // In selection mode the tile turns accent so the white check boxes stay visible.
        if (isSelected || table.selectedRowCount > 0) {
            iconBadge.background = AppTheme.accent
            icon.icon = if (isSelected) icoChecked else icoUnchecked
        } else {
            row.showFileIcon(ent)
        }
        row.update(ent, showButtons = table.selectedRowCount == 0)
    }

    private fun getComp(table: JTable?, value: DbRecord?, isSelected: Boolean): Component {
        value?.let { updateLabelText(it, isSelected) }
        table?.let { row.root.background = it.background }
        return row.root
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
    ): Component {
        this.editEntry = null
        this.row.entry = null
        this.viewRow = -1
        return getComp(table, value as DbRecord, isSelected)
    }

    override fun getTableCellEditorComponent(
        table: JTable, value: Any, isSelected: Boolean, row: Int, column: Int
    ): Component {
        this.editEntry = value as? DbRecord
        this.row.entry = editEntry
        this.viewRow = row
        return getComp(table, value as? DbRecord, isSelected)
    }

    override fun getCellEditorValue(): Any {
        return 99
    }

    override fun isCellEditable(anEvent: EventObject?): Boolean {
        return true
    }

    override fun shouldSelectCell(anEvent: EventObject?): Boolean {
        return false
    }

    override fun stopCellEditing(): Boolean {
        viewRow = -1
        return true
    }

    override fun cancelCellEditing() {
        viewRow = -1
    }

    override fun addCellEditorListener(l: CellEditorListener) {
        // Noop
    }

    override fun removeCellEditorListener(l: CellEditorListener) {
        // Noop
    }
}
