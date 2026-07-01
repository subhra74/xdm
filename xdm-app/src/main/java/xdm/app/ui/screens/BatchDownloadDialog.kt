package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.utils.chooseFile
import xdm.app.utils.createSVGIcon
import xdm.app.utils.sameWidth
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.util.CoreUtils.uniqueId
import xdm.core.util.FileUtils
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel

class BatchDownloadDialog(owner: Window?, urls: List<String>) : JDialog(owner) {
    val btnOk = JButton(text("BTN_OK"))
    private val entries: MutableList<BatchEntry> = urls.map { url ->
        BatchEntry(url = url, fileName = FileUtils.getFileName(url), selected = true)
    }.toMutableList()

    private val tableModel = BatchTableModel(entries)
    private val modelSaveIn = DefaultComboBoxModel<String>()
    private val cmbSaveIn = JComboBox(modelSaveIn)

    data class BatchEntry(val url: String, val fileName: String, var selected: Boolean)

    private inner class BatchTableModel(private val items: MutableList<BatchEntry>) : AbstractTableModel() {
        private val columns = arrayOf("", text("ND_ADDRESS"), text("ND_FILE"))

        override fun getRowCount() = items.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(col: Int) = columns[col]
        override fun getColumnClass(col: Int) = if (col == 0) Boolean::class.javaObjectType else String::class.java

        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> items[row].selected
            1 -> items[row].url
            2 -> items[row].fileName
            else -> ""
        }

        override fun isCellEditable(row: Int, col: Int) = col == 0

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            if (col == 0 && value is Boolean) {
                items[row] = items[row].copy(selected = value)
                fireTableCellUpdated(row, col)
            }
        }

        fun getSelectedItems(): List<BatchEntry> {
            return items.filter { it.selected }
        }
    }

    init {
        title = text("MENU_BATCH_DOWNLOAD")
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isModal = true
        initUI()
        populateFolders()
        size = Dimension(600, 450)
        setLocationRelativeTo(owner)

        addWindowListener(
            object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent) {
                    btnOk.requestFocusInWindow()
                    requestFocus()
                }

                override fun windowClosed(e: WindowEvent) {
                    System.gc()
                }
            })
    }

    private fun initUI() {
        layout = BorderLayout()

        val table = JTable(tableModel).apply {
            columnModel.getColumn(0).apply {
                maxWidth = 30
                minWidth = 30
                preferredWidth = 30
            }
            columnModel.getColumn(1).preferredWidth = 340
            columnModel.getColumn(2).preferredWidth = 180
            fillsViewportHeight = true
            rowHeight = 24
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        }

        val panel1 = JPanel(BorderLayout())
        panel1.border = EmptyBorder(10, 10, 10, 10)
        panel1.add(JScrollPane(table))
        add(panel1)

        val southPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(0, 0, 0, 0)
        }
        add(southPanel, BorderLayout.SOUTH)

        southPanel.add(createFolderPanel())
        southPanel.add(createButtonPanel())
    }

    private fun createFolderPanel(): JPanel {
        val panel = JPanel(GridBagLayout()).apply {
            border = EmptyBorder(0, 10, 10, 10)
        }

        val lblSaveIn = JLabel(text("LBL_SAVE_IN")).apply {
            horizontalAlignment = SwingConstants.RIGHT
        }
        panel.add(lblSaveIn, GridBagConstraints().apply {
            anchor = GridBagConstraints.EAST
            insets = Insets(0, 0, 0, 10)
            gridx = 0
            gridy = 0
        })

        panel.add(cmbSaveIn, GridBagConstraints().apply {
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0, 0, 0, 5)
            gridx = 1
            gridy = 0
        })

        val btnBrowse = JButton(createSVGIcon("folder-fill.svg", 16, Color.GRAY))
        btnBrowse.addActionListener {
            val selected = chooseFile(
                this,
                directoriesOnly = true,
                currentDir = File(AppContext.defaultDownloadFolder)
            )
            if (selected != null) {
                val path = selected.absolutePath
                val idx = modelSaveIn.getIndexOf(path).takeIf { it >= 0 } ?: run {
                    modelSaveIn.addElement(path)
                    modelSaveIn.size - 1
                }
                cmbSaveIn.selectedIndex = idx
            }
        }
        panel.add(btnBrowse, GridBagConstraints().apply {
            gridx = 2
            gridy = 0
        })

        return panel
    }

    private fun createButtonPanel(): JPanel {
        val panel = JPanel().apply {
            //background = UIManager.getColor("Table.background")
            border = EmptyBorder(0, 10, 10, 10)
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

        panel.add(Box.createHorizontalGlue())

        val btnCancel = JButton(text("ND_CANCEL"))
        btnCancel.addActionListener { dispose() }
        panel.add(btnCancel)

        panel.add(Box.createRigidArea(Dimension(10, 0)))


        btnOk.addActionListener {
            val selectedItems = tableModel.getSelectedItems()
            selectedItems.forEach { item ->
                val task = HttpDownloadTaskInfo(
                    id = uniqueId(),
                    url = item.url,
                    fileName = item.fileName,
                    respectFileName = false,
                    cookie = null,
                    headers = null,
                    origin = null,
                    autoCategorize = false,
                    defaultDownloadFolder = cmbSaveIn.selectedItem?.toString() ?: AppContext.defaultDownloadFolder,
                    userSelectedDownloadFolder = null,
                    maxPiece = 8,
                    authInfo = null,
                    knownFileSize = null
                )
                AppContext.downloader.startHttpDownload(task)
            }
            dispose()
        }
        panel.add(btnOk)

        getRootPane().defaultButton = btnOk
        sameWidth(btnOk, btnCancel)

        return panel
    }

    private fun populateFolders() {
        modelSaveIn.addElement(AppContext.defaultDownloadFolder)
        cmbSaveIn.selectedIndex = 0
    }
}
