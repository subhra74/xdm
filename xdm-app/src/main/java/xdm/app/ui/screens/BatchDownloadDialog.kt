package xdm.app.ui.screens

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.AppContext
import xdm.app.BatchRequest
import xdm.app.BatchRequestItem
import xdm.app.I8N.text
import xdm.app.ui.components.MessageBox
import xdm.app.utils.BatchFolderState
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.batchFolderState
import xdm.app.utils.chooseFile
import xdm.app.utils.createIcon
import xdm.app.utils.defaultBatchName
import xdm.app.utils.dialogHeight
import xdm.app.utils.isAutoCategorySelected
import xdm.app.utils.populateSaveInFolders
import xdm.app.utils.px
import xdm.app.utils.rememberBatchFolder
import xdm.app.utils.rememberFolderChoice
import xdm.app.utils.rememberedBaseFolder
import xdm.app.utils.sameWidth
import xdm.app.utils.sanitizeBatchName
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import xdm.app.utils.selectedBaseFolder
import xdm.app.utils.suggestBatchName
import xdm.app.utils.uniqueFileNames
import xdm.core.downloaders.BatchDownloadTaskInfo
import xdm.core.downloaders.BatchItem
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.util.CoreUtils.uniqueId
import xdm.core.util.FileUtils
import xdm.core.util.FormatHelper
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * Downloads many links at once, in one of two modes picked with radio buttons:
 *
 * - **one batch**: a single download entry; the files go into a new folder `<save in>/<name>`, which
 *   must be missing or empty. Save in lists folders only (no automatic categories).
 * - **one download per link**: a normal download for each link, as before.
 *
 * Opened by the clipboard "Batch download" menu (URLs only) and by the browser's "Download all"
 * (with the page, headers and cookies the browser would send).
 */
class BatchDownloadDialog(owner: Window?, private val request: BatchRequest) : JDialog(owner) {
    val btnOk = JButton(text("ND_DOWNLOAD"))

    private class BatchEntry(
        val item: BatchRequestItem,
        var fileName: String,
        var selected: Boolean = true,
        /** The user typed this name: the server must not replace it. */
        var nameEdited: Boolean = false,
    )

    private val entries: List<BatchEntry> = run {
        val names = uniqueFileNames(request.items.map { initialName(it) })
        request.items.mapIndexed { i, item -> BatchEntry(item, names[i]) }
    }

    private val tableModel = BatchTableModel()
    private val lblCount = JLabel()

    private val radioBatch = JRadioButton(text("BATCH_MODE_ONE"))
    private val radioEach = JRadioButton(text("BATCH_MODE_EACH"))
    private val lblName = JLabel(text("BATCH_NAME"))
    private val txtName = JTextField(defaultBatchName(request.pageTitle, request.pageUrl)).apply {
        putClientProperty(FlatClientProperties.STYLE, "arc: 10")
    }

    /** Save in for one download per link: "Automatic (by file type)" first, then folders. */
    private val modelEach = DefaultComboBoxModel<String>()

    /** Save in for one batch: folders only. */
    private val modelBatch = DefaultComboBoxModel<String>()
    private val cmbSaveIn = JComboBox<String>()

    private val lblTarget = JLabel()
    private val lblProblem = JLabel().apply {
        foreground = UIManager.getColor("Component.error.focusedBorderColor") ?: Color(0xD0, 0x40, 0x40)
    }
    private val btnSuggest = JButton()
    private val validateTimer = Timer(250) { validateInput() }.apply { isRepeats = false }

    /** The selected mode's explanation; a read-only text area so it wraps to the dialog's width. */
    private val txtDescription = WrappingText().apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = null
        font = UIManager.getFont("Label.font").let { it.deriveFont(it.size2D - 1f) }
        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
    }

    private val batchMode: Boolean get() = radioBatch.isSelected

    private inner class BatchTableModel : AbstractTableModel() {
        private val columns = arrayOf("", text("ND_FILE"), text("PROP_SIZE"), text("ND_ADDRESS"))

        override fun getRowCount() = entries.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(col: Int) = columns[col]
        override fun getColumnClass(col: Int) = if (col == 0) Boolean::class.javaObjectType else String::class.java

        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> entries[row].selected
            1 -> entries[row].fileName
            2 -> entries[row].item.knownSize?.let { FormatHelper.formatSize(it.toDouble()) } ?: "–"
            3 -> entries[row].item.url
            else -> ""
        }

        override fun isCellEditable(row: Int, col: Int) = col == 0 || col == COL_FILE

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            val entry = entries[row]
            when {
                col == 0 && value is Boolean -> entry.selected = value
                col == COL_FILE && value is String -> {
                    val name = FileUtils.sanitizeFileName(value.trim()) ?: return
                    if (name == entry.fileName) return
                    entry.fileName = name
                    entry.nameEdited = true
                }

                else -> return
            }
            fireTableCellUpdated(row, col)
            updateCount()
        }
    }

    init {
        title = text("MENU_BATCH_DOWNLOAD")
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isModal = true
        initUI()
        populateSaveInFolders(modelEach, cmbSaveIn.apply { model = modelEach })
        modelBatch.addAll(AppContext.config.recentFolders.drop(1))
        val asOne = if (request.fromBrowser) AppContext.config.batchAsOneFromBrowser
        else AppContext.config.batchAsOneFromClipboard
        (if (asOne) radioBatch else radioEach).isSelected = true
        applyMode()
        updateCount()
        isResizable = true
        // From the browser it must not open behind the browser window.
        isAlwaysOnTop = request.fromBrowser
        size = Dimension(DIALOG_WIDTH.px, dialogHeight(DIALOG_HEIGHT))
        setLocationRelativeTo(owner)

        // The description's height depends on the width it wraps to.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = txtDescription.revalidate()
        })
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                btnOk.requestFocusInWindow()
            }

            override fun windowClosed(e: WindowEvent) {
                validateTimer.stop()
            }
        })
    }

    private fun initUI() {
        layout = BorderLayout()
        add(createModePanel(), BorderLayout.NORTH)

        val table = JTable(tableModel).apply {
            columnModel.getColumn(0).apply {
                maxWidth = 30.px
                minWidth = 30.px
                preferredWidth = 30.px
            }
            columnModel.getColumn(COL_FILE).preferredWidth = 200.px
            columnModel.getColumn(COL_SIZE).preferredWidth = 70.px
            columnModel.getColumn(COL_URL).preferredWidth = 380.px
            fillsViewportHeight = true
            rowHeight = 24.px
            autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        }
        val center = JPanel(BorderLayout(0, 4.px)).apply {
            border = ScaledEmptyBorder(0, 10, 6, 10)
            add(JScrollPane(table))
            add(lblCount, BorderLayout.SOUTH)
        }
        add(center)

        val southPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        southPanel.add(createFolderPanel())
        southPanel.add(createButtonPanel())
        add(southPanel, BorderLayout.SOUTH)
    }

    /** The two modes side by side, the selected one's explanation under them, and the batch name. */
    private fun createModePanel(): JPanel {
        val panel = JPanel(GridBagLayout()).apply { border = ScaledEmptyBorder(10, 10, 8, 10) }
        ButtonGroup().apply {
            add(radioBatch)
            add(radioEach)
        }
        radioBatch.addActionListener { applyMode() }
        radioEach.addActionListener { applyMode() }
        val radios = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(radioBatch)
            add(Box.createRigidArea(scaledSize(20, 0)))
            add(radioEach)
        }
        panel.add(radios, GridBagConstraints().apply {
            gridx = 0; gridy = 0; gridwidth = 2
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        })
        panel.add(txtDescription, GridBagConstraints().apply {
            gridx = 0; gridy = 1; gridwidth = 2
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = scaledInsets(4, 4, 8, 4)
        })
        panel.add(lblName, GridBagConstraints().apply {
            gridx = 0; gridy = 2
            anchor = GridBagConstraints.WEST
            insets = scaledInsets(0, 0, 0, 10)
        })
        panel.add(txtName, GridBagConstraints().apply {
            gridx = 1; gridy = 2
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
        })
        txtName.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = validateTimer.restart()
            override fun removeUpdate(e: DocumentEvent) = validateTimer.restart()
            override fun changedUpdate(e: DocumentEvent) = validateTimer.restart()
        })
        return panel
    }

    private fun createFolderPanel(): JPanel {
        val panel = JPanel(GridBagLayout()).apply { border = ScaledEmptyBorder(0, 10, 10, 10) }

        panel.add(JLabel(text("LBL_SAVE_IN")).apply { horizontalAlignment = SwingConstants.RIGHT },
            GridBagConstraints().apply {
                anchor = GridBagConstraints.EAST
                insets = scaledInsets(0, 0, 0, 10)
                gridx = 0; gridy = 0
            })
        panel.add(cmbSaveIn, GridBagConstraints().apply {
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = scaledInsets(0, 0, 0, 5)
            gridx = 1; gridy = 0
        })
        cmbSaveIn.addActionListener { validateInput() }

        val btnBrowse = JButton(createIcon(RemixIcon.FOLDER_FILL, 16, Color.GRAY))
        btnBrowse.addActionListener { browse() }
        panel.add(btnBrowse, GridBagConstraints().apply { gridx = 2; gridy = 0 })

        panel.add(lblTarget, GridBagConstraints().apply {
            gridx = 1; gridy = 1; gridwidth = 2
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = scaledInsets(4, 0, 0, 0)
        })
        val problemRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(lblProblem)
            add(Box.createRigidArea(scaledSize(8, 0)))
            add(btnSuggest)
        }
        btnSuggest.addActionListener {
            (btnSuggest.getClientProperty(SUGGESTION) as? String)?.let { txtName.text = it }
        }
        panel.add(problemRow, GridBagConstraints().apply {
            gridx = 1; gridy = 2; gridwidth = 2
            anchor = GridBagConstraints.WEST
            insets = scaledInsets(4, 0, 0, 0)
        })
        return panel
    }

    private fun browse() {
        val current = if (batchMode) cmbSaveIn.selectedItem?.toString() ?: rememberedBaseFolder()
        else selectedBaseFolder(cmbSaveIn)
        val selected = chooseFile(this, directoriesOnly = true, currentDir = File(current)) ?: return
        val path = selected.absolutePath
        val model = cmbSaveIn.model as DefaultComboBoxModel<String>
        val idx = model.getIndexOf(path).takeIf { it >= 0 } ?: run {
            model.addElement(path)
            model.size - 1
        }
        cmbSaveIn.selectedIndex = idx
    }

    private fun createButtonPanel(): JPanel {
        val panel = JPanel().apply {
            border = ScaledEmptyBorder(0, 10, 10, 10)
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        panel.add(Box.createHorizontalGlue())

        val btnCancel = JButton(text("ND_CANCEL"))
        btnCancel.addActionListener { dispose() }
        panel.add(btnCancel)
        panel.add(Box.createRigidArea(scaledSize(10, 0)))

        btnOk.addActionListener { if (batchMode) downloadAsBatch() else downloadEach() }
        panel.add(btnOk)

        getRootPane().defaultButton = btnOk
        sameWidth(btnOk, btnCancel)
        return panel
    }

    private fun applyMode() {
        val batch = batchMode
        txtDescription.text = text(if (batch) "BATCH_MODE_ONE_DESC" else "BATCH_MODE_EACH_DESC")
        lblName.isVisible = batch
        txtName.isVisible = batch
        lblTarget.isVisible = batch
        val wanted: DefaultComboBoxModel<String> = if (batch) modelBatch else modelEach
        if (cmbSaveIn.model !== wanted) {
            cmbSaveIn.model = wanted
            if (batch) {
                val folder = rememberedBaseFolder()
                cmbSaveIn.selectedIndex = wanted.getIndexOf(folder).takeIf { it >= 0 } ?: 0
            }
        }
        validateInput()
        revalidate()
    }

    private fun selectedFolder(): String =
        if (batchMode) cmbSaveIn.selectedItem?.toString() ?: AppContext.config.defaultDownloadFolder
        else selectedBaseFolder(cmbSaveIn)

    /** Shows where the batch goes and whether that folder can be used; enables Download accordingly. */
    private fun validateInput() {
        val anySelected = entries.any { it.selected }
        if (!batchMode) {
            showProblem(null, null)
            btnOk.isEnabled = anySelected
            return
        }
        val parent = File(selectedFolder())
        val name = sanitizeBatchName(txtName.text)
        if (name == null) {
            lblTarget.text = " "
            showProblem(text("BATCH_NAME_EMPTY"), null)
            btnOk.isEnabled = false
            return
        }
        val target = File(parent, name)
        lblTarget.text = "→ ${target.absolutePath}${File.separator}"
        val problem = when (batchFolderState(target)) {
            BatchFolderState.Ok -> null
            BatchFolderState.NotEmpty -> text("BATCH_FOLDER_NOT_EMPTY")
            BatchFolderState.NotAFolder -> text("BATCH_FOLDER_IS_FILE")
        }
        showProblem(problem, problem?.let { suggestBatchName(parent, name) })
        btnOk.isEnabled = anySelected && problem == null
    }

    private fun showProblem(message: String?, suggestion: String?) {
        lblProblem.text = message ?: ""
        lblProblem.isVisible = message != null
        btnSuggest.isVisible = suggestion != null
        btnSuggest.putClientProperty(SUGGESTION, suggestion)
        suggestion?.let { btnSuggest.text = text("BATCH_USE_NAME").format(it) }
    }

    private fun updateCount() {
        lblCount.text = text("BATCH_SELECTED").format(entries.count { it.selected }, entries.size)
        validateInput()
    }

    private fun rememberMode() {
        val config = AppContext.config
        if (request.fromBrowser) config.batchAsOneFromBrowser = batchMode
        else config.batchAsOneFromClipboard = batchMode
        config.save()
    }

    private fun downloadAsBatch() {
        val selected = entries.filter { it.selected }
        val name = sanitizeBatchName(txtName.text) ?: return
        val parent = selectedFolder()
        val names = uniqueFileNames(selected.map { it.fileName })
        val task = BatchDownloadTaskInfo(
            id = uniqueId(),
            name = name,
            folder = parent,
            headers = request.headers,
            origin = request.pageUrl,
            maxPiece = AppContext.config.maxSegments,
            cookies = request.cookies,
            items = selected.mapIndexed { i, e ->
                BatchItem(
                    url = e.item.url,
                    fileName = names[i],
                    cookieGroup = e.item.cookieGroup,
                    knownSize = e.item.knownSize,
                    nameFromPage = e.item.fileName != null || e.nameEdited,
                )
            },
        )
        if (!AppContext.downloader.startBatchDownload(task)) {
            MessageBox.show(this, text("MENU_BATCH_DOWNLOAD"), text("BATCH_FOLDER_ERROR").format(task.batchFolder))
            validateInput()
            return
        }
        rememberBatchFolder(parent)
        rememberMode()
        dispose()
    }

    private fun downloadEach() {
        val auto = isAutoCategorySelected(cmbSaveIn)
        val folder = selectedBaseFolder(cmbSaveIn)
        rememberFolderChoice(cmbSaveIn)
        rememberMode()
        entries.filter { it.selected }.forEach { e ->
            AppContext.downloader.startHttpDownload(
                HttpDownloadTaskInfo(
                    id = uniqueId(),
                    url = e.item.url,
                    fileName = e.fileName,
                    respectFileName = e.item.fileName != null || e.nameEdited,
                    cookie = request.cookies.getOrNull(e.item.cookieGroup),
                    headers = request.headers,
                    origin = request.pageUrl,
                    autoCategorize = auto,
                    defaultDownloadFolder = folder,
                    userSelectedDownloadFolder = null,
                    maxPiece = AppContext.config.maxSegments,
                    authInfo = null,
                    knownFileSize = e.item.knownSize,
                )
            )
        }
        dispose()
    }

    private companion object {
        const val SUGGESTION = "xdm.batch.suggestion"
        const val COL_FILE = 1
        const val COL_SIZE = 2
        const val COL_URL = 3
        const val DIALOG_WIDTH = 700
        const val DIALOG_HEIGHT = 550

        /** The page's name for the link, else the URL's, made safe for a file name. */
        fun initialName(item: BatchRequestItem): String =
            item.fileName?.let { FileUtils.sanitizeFileName(FileUtils.getFileName(it)) }
                ?: FileUtils.getFileName(item.url)
    }
}

/**
 * A text area that wraps to whatever width its container gives it. Its preferred height is measured at
 * that width, so a layout manager sizes it to exactly the lines it needs, and its preferred width
 * is minimal so it never forces the container wider.
 */
private class WrappingText : JTextArea() {
    override fun getPreferredSize(): Dimension {
        val width = (parent?.width ?: 0).let { if (it > 0) it - 8 else 680 }
        // Wrapped text reports the height it needs for its current width.
        setSize(width, Short.MAX_VALUE.toInt())
        return Dimension(1, super.getPreferredSize().height)
    }
}
