package xdm.app.ui.screens

import xdm.app.I8N.text
import xdm.app.RecordStatus
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.isMacPopupTrigger
import xdm.app.utils.openFileExternal
import xdm.app.utils.openFolderExternal
import xdm.app.utils.px
import xdm.app.utils.setClipBoardText
import xdm.core.downloaders.BatchDownloadTaskInfo
import xdm.core.downloaders.web.batch.BatchTaskContext
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.util.FormatHelper
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JCheckBox
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * Every file of a batch: `# | URL | File | Size | Status`. [state] is null until the batch first
 * starts, when every file is still waiting. [recordStatus] tells "downloading" apart from "waiting"
 * for unfinished files, since the state is a snapshot of a running download.
 */
class BatchFilesView(
    private val task: BatchDownloadTaskInfo,
    private val state: BatchTaskContext?,
    private val recordStatus: RecordStatus,
) : JPanel(BorderLayout(0, 4.px)) {

    private val model = FilesModel()
    private val table = JTable(model)

    init {
        border = ScaledEmptyBorder(6, 0, 0, 0)
        val sorter = TableRowSorter(model)
        table.rowSorter = sorter
        table.rowHeight = 22.px
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        val widths = intArrayOf(50, 330, 170, 80, 140)
        widths.forEachIndexed { i, w -> table.columnModel.getColumn(i).preferredWidth = w.px }
        add(JScrollPane(table), BorderLayout.CENTER)

        if ((state?.failedCount ?: 0) > 0) {
            val chkFailed = JCheckBox(text("BATCH_FAILED_ONLY"))
            chkFailed.addActionListener {
                sorter.rowFilter = if (chkFailed.isSelected) object : RowFilter<FilesModel, Int>() {
                    override fun include(entry: Entry<out FilesModel, out Int>) =
                        state?.files?.get(entry.identifier)?.status?.get() == ChunkStatus.Failed
                } else null
            }
            add(chkFailed, BorderLayout.NORTH)
        }
        installContextMenu()
    }

    private fun fileName(index: Int) = state?.files?.get(index)?.finalName ?: task.items[index].fileName

    private fun folder() = state?.batchFolder ?: task.batchFolder

    private fun isDone(index: Int) = state?.files?.get(index)?.status?.get() == ChunkStatus.Finished

    private fun statusText(index: Int): String {
        val f = state?.files?.get(index) ?: return text("BATCH_STATUS_WAITING")
        return when (f.status.get()) {
            ChunkStatus.Finished -> text("BATCH_STATUS_DONE")
            ChunkStatus.Failed -> f.error.get()?.let { "${text("BATCH_STATUS_FAILED")}: ${downloadErrorText(it)}" }
                ?: text("BATCH_STATUS_FAILED")

            else -> if (recordStatus == RecordStatus.DOWNLOADING && f.downloaded.get() > 0)
                text("STAT_DOWNLOADING") else text("BATCH_STATUS_WAITING")
        }
    }

    private fun sizeText(index: Int): String {
        val length = state?.files?.get(index)?.length?.get() ?: -1
        val size = if (length >= 0) length else task.items[index].knownSize
        return size?.let { FormatHelper.formatSize(it.toDouble()) } ?: "–"
    }

    private fun installContextMenu() {
        val menu = JPopupMenu()
        val mOpen = JMenuItem(text("CTX_OPEN_FILE"))
        val mFolder = JMenuItem(text("CTX_OPEN_FOLDER"))
        val mCopy = JMenuItem(text("CTX_COPY_URL"))
        menu.add(mOpen)
        menu.add(mFolder)
        menu.add(mCopy)

        fun selectedIndexes() = table.selectedRows.map { table.convertRowIndexToModel(it) }

        mOpen.addActionListener {
            selectedIndexes().firstOrNull()?.let { i ->
                runCatching { openFileExternal(fileName(i), folder()) }.onFailure { Logger.error(it) }
            }
        }
        mFolder.addActionListener {
            val i = selectedIndexes().firstOrNull()
            val name = i?.let { fileName(it) }?.takeIf { isDone(i) && File(folder(), it).exists() }
            runCatching { openFolderExternal(name, folder()) }.onFailure { Logger.error(it) }
        }
        mCopy.addActionListener {
            setClipBoardText(selectedIndexes().joinToString("\n") { task.items[it].url })
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (!(SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger || isMacPopupTrigger(e))) return
                val row = table.rowAtPoint(e.point)
                if (row < 0) return
                if (!table.isRowSelected(row)) table.setRowSelectionInterval(row, row)
                val first = table.convertRowIndexToModel(table.selectedRow)
                mOpen.isEnabled = table.selectedRowCount == 1 && isDone(first)
                menu.show(table, e.x, e.y)
            }
        })
    }

    private inner class FilesModel : AbstractTableModel() {
        private val columns = arrayOf("#", text("PROP_URL"), text("ND_FILE"), text("PROP_SIZE"), text("PROP_STATUS"))

        override fun getRowCount() = task.items.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(column: Int): Class<*> =
            if (column == 0) Int::class.javaObjectType else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            0 -> rowIndex + 1
            1 -> task.items[rowIndex].url
            2 -> fileName(rowIndex)
            3 -> sizeText(rowIndex)
            else -> statusText(rowIndex)
        }
    }
}
