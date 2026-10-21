package xdm.app.ui.components

import xdm.app.DbRecord
import xdm.app.DownloadCategory
import xdm.app.utils.detectOS
import xdm.app.OS
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.KeyStroke

/**
 * The main window's download list. Two implementations, picked in Settings: [ModernListView]
 * (check box tiles, title click opens) and [ClassicListView] (click selects, double-click opens).
 * They share the model, filter, sorter, row display and menu, but never each other's mouse handling.
 */
interface DownloadListView {
    val component: Component

    /** Every download the list knows about, ignoring the current filter. EDT only. */
    val records: List<DbRecord>
    val selectedItems: List<DbRecord>
    val selectedRowCount: Int

    /** The current filter, search text included, so a replacement view can start from it. */
    val filter: MainListViewFilter

    /** Called with true when the toolbar should show its bulk actions for the selection, false when not. */
    var bulkSelectionCallback: ((Boolean) -> Unit)?

    fun rowUpdated(index: Int)
    fun rowDeleted(index: Int)
    fun rowsRemoved()
    fun rowAdded(index: Int)

    /** Runs [listener] after any change to the list's rows (added, removed or updated). */
    fun addRowsChangedListener(listener: () -> Unit)
    fun filterStateChanged(state: FilterState)
    fun filterCategoryChanged(category: DownloadCategory?)
    fun searchTextChanged(text: String)
    fun sort(sortKey: SortKey, ascending: Boolean)
    fun clear()
}

/**
 * Keyboard shortcuts both lists share: select all, Esc clears, Delete deletes the selection and
 * Enter opens the one selected download. Typing no longer opens the hover editor.
 */
fun installListKeys(table: JTable, onOpen: () -> Unit, onDelete: () -> Unit) {
    table.putClientProperty("JTable.autoStartsEdit", false)
    val shortcut = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
    val inputMap = table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
    val actionMap = table.actionMap
    fun bind(name: String, vararg keys: KeyStroke, action: () -> Unit) {
        keys.forEach { inputMap.put(it, name) }
        actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }
    bind("xdm.selectAll", KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut)) { table.selectAll() }
    bind("xdm.clearSelection", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)) { table.clearSelection() }
    val deleteKeys = buildList {
        add(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0))
        // Finder's delete: ⌘⌫.
        if (detectOS() == OS.MacOS) add(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, shortcut))
    }
    bind("xdm.delete", *deleteKeys.toTypedArray()) { if (table.selectedRowCount > 0) onDelete() }
    bind("xdm.open", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)) { if (table.selectedRowCount == 1) onOpen() }
}
