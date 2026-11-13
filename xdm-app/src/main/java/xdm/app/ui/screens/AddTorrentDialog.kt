package xdm.app.ui.screens

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.torrent.TorrentSelection
import xdm.app.torrent.DuplicateAction
import xdm.app.torrent.TorrentLinkFetcher
import xdm.app.torrent.TorrentSource
import xdm.app.ui.components.AppMenuHandler
import xdm.app.ui.components.DestinationPicker
import xdm.app.ui.components.MessageBox
import xdm.app.ui.components.StorageStatusLabel
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.VolumeHints
import xdm.app.utils.chooseFile
import xdm.app.utils.createIcon
import xdm.app.utils.dialogHeight
import xdm.app.utils.px
import xdm.app.utils.sameWidth
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import xdm.app.utils.windowIconImages
import xdm.core.downloaders.TorrentDownloadTaskInfo
import xdm.core.util.CoreUtils.uniqueId
import xdm.core.util.FormatHelper
import xdm.core.util.Logger
import xdm.manager.RecordStatus
import xdm.manager.torrent.MetadataFetch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.ButtonGroup
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Adds a torrent from a .torrent file, a link to one, or a magnet link (or a bare info-hash), picked above the field:
 *
 * ```
 * Source     (•) Torrent file  ( ) Torrent link  ( ) Magnet link
 * Torrent    [ magnet:?xt=urn:btih:…                 ] [📁]
 * Name       ubuntu-24.04-desktop-amd64.iso
 * Category   [ None ▾ ] [+]
 * Save to    [ ~/Downloads ▾ ] [📁]
 * [✓] ▾ 📁 folder                   size
 * [✓]     name                       size
 * 3 of 3 files, 4.0 GB · All · None · Expand all · Collapse all
 * 4.0 GB free on the drive                [ ] Download in order
 *                          [Cancel] [Download Later] [Download]
 * ```
 *
 * A .torrent file is parsed at once. For a magnet the file list is fetched from peers (the session starts
 * then, so the DHT bootstrap begins as the dialog opens); Download before it arrives adds the magnet with
 * every file. Closing the dialog cancels the fetch. A torrent already in the list is refused here.
 *
 * Folders fold and unfold with their arrow, a double click, or Left/Right on the selected row.
 *
 * An independent frame (docs/windows-and-modality.md), like the batch dialog.
 */
class AddTorrentDialog(near: Window?, initial: String?) : JFrame() {
    // Rounded like the address fields of the other download dialogs.
    private val txtSource = JTextField(40).apply { putClientProperty(FlatClientProperties.STYLE, "arc: 10") }
    private val btnOpen = DestinationPicker.fieldIconButton(RemixIcon.FOLDER_FILL).apply {
        toolTipText = text("TORRENT_OPEN_FILE")
    }
    private val rbFile = JRadioButton(text("TORRENT_KIND_FILE"))
    private val rbLink = JRadioButton(text("TORRENT_KIND_LINK"))
    private val rbMagnet = JRadioButton(text("TORRENT_KIND_MAGNET"))
    private val lblName = JLabel(" ")
    private val lblState = JLabel(" ")
    private val lblCount = JLabel(" ")
    private val chkSequential = JCheckBox(text("TORRENT_SEQUENTIAL"))
    private val btnDownload = JButton(text("ND_DOWNLOAD"))
    private val btnLater = JButton(text("ND_DOWNLOAD_LATER"))
    private val btnCancel = JButton(text("ND_CANCEL"))
    private val picker = DestinationPicker(this, onChanged = { updateStorage() })
    private val storage = StorageStatusLabel(usesTempFolder = false) { _, _ -> }
    private val tableModel = FilesModel()
    private val table = JTable(tableModel)
    /** Expand all / Collapse all, shown only when the torrent has folders. */
    private val foldLinks = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        isVisible = false
    }

    /** What the field resolved to, and what is known about it. */
    private var source: TorrentSource? = null
    private var key: String? = null
    private var torrentBytes: ByteArray? = null
    private var selection: TorrentSelection? = null
    private var fetch: MetadataFetch? = null
    /** Bumped on every new source, so a late answer for an old one is dropped. */
    private var generation = 0
    /** Set when this torrent is already in the list; [duplicateAction] is what the main button does then. */
    private var duplicate = false
    private var duplicateId: Long? = null
    private var duplicateAction: DuplicateAction? = null

    private val sourceTimer = Timer(400) { sourceChanged() }.apply { isRepeats = false }
    private val stateTimer = Timer(1000) { showFetching() }

    init {
        title = text("TORRENT_ADD_TITLE")
        iconImages = windowIconImages
        defaultCloseOperation = DISPOSE_ON_CLOSE
        initUI()
        picker.reset(null, null, null)
        isResizable = true
        size = Dimension(DIALOG_WIDTH.px, dialogHeight(DIALOG_HEIGHT))
        setLocationRelativeTo(near)
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                txtSource.requestFocusInWindow()
            }

            override fun windowClosed(e: WindowEvent) {
                sourceTimer.stop()
                stateTimer.stop()
                fetch?.cancel()
                picker.dispose()
            }
        })
        // DHT needs a few seconds to find peers: start the session while the user pastes a link.
        AppContext.downloader.torrents.warmUp()
        txtSource.text = initial.orEmpty()
        // The kind the field starts with: what it holds, else a .torrent file.
        selectKind(TorrentSource.of(initial.orEmpty()) { File(it).isFile } ?: TorrentSource.TorrentFile(""))
        txtSource.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = sourceTimer.restart()
            override fun removeUpdate(e: DocumentEvent) = sourceTimer.restart()
            override fun changedUpdate(e: DocumentEvent) = sourceTimer.restart()
        })
        sourceChanged()
        // An empty field is no change for sourceChanged: Download starts disabled here.
        updateButtons()
    }

    private fun initUI() {
        layout = BorderLayout()
        val top = JPanel(GridBagLayout()).apply { border = ScaledEmptyBorder(10, 0, 4, 10) }
        fun label(key: String, row: Int) = top.add(JLabel(text(key)), GridBagConstraints().apply {
            gridx = 0; gridy = row; anchor = GridBagConstraints.EAST; insets = scaledInsets(5, 20, 5, 10)
        })
        label("TORRENT_KIND", 0)
        val group = ButtonGroup()
        top.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            listOf(rbFile, rbLink, rbMagnet).forEachIndexed { i, rb ->
                if (i > 0) add(Box.createRigidArea(scaledSize(16, 0)))
                group.add(rb)
                add(rb)
                rb.addActionListener { kindPicked() }
            }
            add(Box.createHorizontalGlue())
        }, GridBagConstraints().apply {
            gridx = 1; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = scaledInsets(5, 0, 5, 0)
        })
        label("TORRENT_SOURCE", 1)
        top.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(txtSource)
            add(Box.createRigidArea(scaledSize(5, 0)))
            add(btnOpen)
        }, GridBagConstraints().apply {
            gridx = 1; gridy = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = scaledInsets(5, 0, 5, 0)
        })
        label("TORRENT_NAME", 2)
        top.add(lblName.apply { font = font.deriveFont(java.awt.Font.BOLD) }, GridBagConstraints().apply {
            gridx = 1; gridy = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = scaledInsets(5, 0, 5, 0)
        })
        picker.addTo(top, firstRow = 3)
        add(top, BorderLayout.NORTH)
        btnOpen.addActionListener { openFile() }

        table.apply {
            columnModel.getColumn(0).apply { maxWidth = 30.px; minWidth = 30.px; preferredWidth = 30.px }
            columnModel.getColumn(1).preferredWidth = 420.px
            columnModel.getColumn(1).cellRenderer = NameRenderer()
            columnModel.getColumn(2).preferredWidth = 90.px
            columnModel.getColumn(2).cellRenderer = DefaultTableCellRenderer().apply { horizontalAlignment = JLabel.RIGHT }
            fillsViewportHeight = true
            rowHeight = 24.px
            autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            tableHeader.reorderingAllowed = false
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = folderClicked(e)
            })
            // Left/Right fold and unfold instead of moving between columns.
            val keys = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "xdmFold")
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "xdmUnfold")
            actionMap.put("xdmFold", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = foldKey(expand = false)
            })
            actionMap.put("xdmUnfold", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = foldKey(expand = true)
            })
        }
        fun link(key: String, action: () -> Unit) = JLabel(text(key)).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = UIManager.getColor("Component.linkColor") ?: UIManager.getColor("ProgressBar.foreground")
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = action()
            })
        }
        foldLinks.add(Box.createRigidArea(scaledSize(12, 0)))
        foldLinks.add(link("TORRENT_EXPAND_ALL") { foldAll(expand = true) })
        foldLinks.add(Box.createRigidArea(scaledSize(12, 0)))
        foldLinks.add(link("TORRENT_COLLAPSE_ALL") { foldAll(expand = false) })
        val countRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(lblCount)
            add(Box.createHorizontalGlue())
            add(link("TORRENT_SELECT_ALL") { selectAll(true) })
            add(Box.createRigidArea(scaledSize(12, 0)))
            add(link("TORRENT_SELECT_NONE") { selectAll(false) })
            add(foldLinks)
        }
        add(JPanel(BorderLayout(0, 4.px)).apply {
            border = ScaledEmptyBorder(0, 10, 6, 10)
            add(JScrollPane(table))
            add(countRow, BorderLayout.SOUTH)
        })

        val south = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        south.add(JPanel(BorderLayout()).apply {
            border = ScaledEmptyBorder(0, 10, 6, 10)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(lblState)
                add(storage)
            }, BorderLayout.WEST)
            add(chkSequential, BorderLayout.EAST)
        })
        south.add(JPanel().apply {
            border = ScaledEmptyBorder(0, 10, 10, 10)
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalGlue())
            btnCancel.addActionListener { dispose() }
            add(btnCancel)
            add(Box.createRigidArea(scaledSize(10, 0)))
            add(btnLater)
            add(Box.createRigidArea(scaledSize(10, 0)))
            add(btnDownload)
            sameWidth(btnDownload, btnLater, btnCancel)
        })
        add(south, BorderLayout.SOUTH)
        btnDownload.addActionListener { add(now = true) }
        btnLater.addActionListener { add(now = false) }
        getRootPane().defaultButton = btnDownload
    }

    /** Shows the field for [s]'s kind: its hint, and the folder button only for a .torrent file. */
    private fun selectKind(s: TorrentSource) {
        val (rb, hint) = when (s) {
            is TorrentSource.TorrentFile -> rbFile to "TORRENT_HINT_FILE"
            is TorrentSource.Link -> rbLink to "TORRENT_HINT_LINK"
            is TorrentSource.Magnet -> rbMagnet to "TORRENT_HINT_MAGNET"
        }
        rb.isSelected = true
        btnOpen.isVisible = rb === rbFile
        txtSource.putClientProperty("JTextField.placeholderText", text(hint))
    }

    /** The user picked a kind: a field holding another kind is cleared; Torrent file opens the chooser. */
    private fun kindPicked() {
        val kind = when {
            rbFile.isSelected -> TorrentSource.TorrentFile("")
            rbLink.isSelected -> TorrentSource.Link("")
            else -> TorrentSource.Magnet("")
        }
        selectKind(kind)
        val current = source
        if (current != null && current::class != kind::class) {
            txtSource.text = ""
            sourceTimer.stop()
            sourceChanged()
        }
        if (kind is TorrentSource.TorrentFile && txtSource.text.isBlank()) openFile() else txtSource.requestFocusInWindow()
    }

    private fun openFile() {
        val start = source.let { it as? TorrentSource.TorrentFile }?.let { File(it.path).parentFile }
        val file = chooseFile(this, directoriesOnly = false, currentDir = start,
            filter = text("TORRENT_FILES") to listOf("torrent")) ?: return
        txtSource.text = file.absolutePath
        sourceTimer.stop()
        sourceChanged()
    }

    /** Re-reads the field: parses a .torrent file, or starts fetching a magnet's file list. */
    private fun sourceChanged() {
        val s = TorrentSource.of(txtSource.text) { File(it).isFile }
        if (s == source) return
        generation++
        fetch?.cancel()
        fetch = null
        stateTimer.stop()
        source = s
        // Pasting another kind of source switches the choice above the field.
        s?.let { selectKind(it) }
        key = null
        torrentBytes = null
        duplicate = false
        duplicateId = null
        duplicateAction = null
        showSelection(null)
        when (s) {
            null -> {
                lblName.text = " "
                showState(if (txtSource.text.isBlank()) " " else text("TORRENT_NOT_RECOGNISED"))
            }
            is TorrentSource.TorrentFile -> readTorrentFile(s)
            is TorrentSource.Link -> startLinkFetch(s)
            is TorrentSource.Magnet -> startFetch(s)
        }
        updateButtons()
    }

    private fun readTorrentFile(s: TorrentSource.TorrentFile) {
        val file = File(s.path)
        if (file.length() > MAX_TORRENT_FILE) return showState(text("TORRENT_NOT_A_TORRENT"))
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (!showTorrent(bytes)) showState(text("TORRENT_NOT_A_TORRENT"))
    }

    /** Shows a .torrent's name and files; false (name cleared) when [bytes] is not one. */
    private fun showTorrent(bytes: ByteArray?): Boolean {
        val info = bytes?.let { AppContext.downloader.torrents.parse(it) }
        if (info == null) {
            lblName.text = " "
            return false
        }
        torrentBytes = bytes
        key = info.key
        lblName.text = info.name
        showSelection(TorrentSelection(info))
        // A duplicate leaves its own line (Download again included, which is not "duplicate").
        if (!checkDuplicate() && duplicateAction == null) showState(" ")
        return true
    }

    /** Downloads a .torrent link (the app's proxy settings apply), then shows it like a file. */
    private fun startLinkFetch(s: TorrentSource.Link) {
        val gen = generation
        lblName.text = " "
        showState(text("TORRENT_LINK_FETCHING"))
        Thread({
            val bytes = runCatching { TorrentLinkFetcher.fetchTorrent(s.url) }
                .onFailure { Logger.error("Torrent", "Fetching ${s.url} failed", it) }.getOrNull()
            SwingUtilities.invokeLater {
                if (gen != generation || !isDisplayable) return@invokeLater
                if (!showTorrent(bytes)) showState(text("TORRENT_LINK_FAILED"))
                updateButtons()
            }
        }, "torrent-link").apply { isDaemon = true }.start()
    }

    private fun startFetch(s: TorrentSource.Magnet) {
        val k = AppContext.downloader.torrents.magnetKey(s.uri)
        if (k == null) {
            lblName.text = " "
            return showState(text("TORRENT_NOT_RECOGNISED"))
        }
        key = k
        lblName.text = TorrentSource.displayName(s.uri) ?: k
        if (checkDuplicate()) return
        val gen = generation
        showFetching()
        stateTimer.start()
        // The session may start here (sockets, saved state): off the EDT.
        Thread({
            val f = runCatching {
                AppContext.downloader.torrents.fetchMetadata(s.uri, AppContext.tempDir) { bytes ->
                    SwingUtilities.invokeLater { metadataArrived(gen, bytes) }
                }
            }.onFailure { Logger.error("Torrent", "Metadata fetch failed to start", it) }.getOrNull()
            SwingUtilities.invokeLater {
                if (gen == generation && isDisplayable) fetch = f else f?.cancel()
                if (f == null) metadataArrived(gen, null)
            }
        }, "torrent-metadata").apply { isDaemon = true }.start()
    }

    private fun metadataArrived(gen: Int, bytes: ByteArray?) {
        if (gen != generation || !isDisplayable) return
        stateTimer.stop()
        fetch = null
        val info = bytes?.let { AppContext.downloader.torrents.parse(it) }
        if (info == null) {
            // Cancelled or failed: Download still adds the magnet, with every file.
            showState(text("TORRENT_METADATA_FAILED"))
            return
        }
        torrentBytes = bytes
        lblName.text = info.name
        showSelection(TorrentSelection(info))
        showState(" ")
        updateButtons()
    }

    private fun showFetching() {
        val nodes = AppContext.downloader.torrents.dhtNodes
        showState(String.format(text("TORRENT_GETTING_METADATA"), nodes))
    }

    /**
     * When this torrent is already in the list, picks what the main button offers instead (Show, Resume, Start
     * seeding, Download again) and says so. True when nothing more is to be fetched for it (all but Download
     * again, which adds it anew with this dialog's folder and files).
     */
    private fun checkDuplicate(): Boolean {
        val k = key ?: return false
        val id = AppContext.downloader.findTorrent(k)
        val rec = id?.let { AppContext.db.getById(it) }
        if (id == null || rec == null) {
            duplicate = false
            duplicateAction = null
            updateButtons()
            return false
        }
        val present = rec.status != RecordStatus.FINISHED || AppContext.downloader.torrentFilesPresent(id)
        val action = DuplicateAction.of(rec.status, rec.seeding, present)
        duplicateId = id
        duplicateAction = action
        duplicate = action != DuplicateAction.DOWNLOAD_AGAIN
        showState(text(when (action) {
            DuplicateAction.SHOW -> "TORRENT_DUP_RUNNING"
            DuplicateAction.RESUME -> "TORRENT_DUP_PAUSED"
            DuplicateAction.SEED -> "TORRENT_DUP_FINISHED"
            DuplicateAction.DOWNLOAD_AGAIN -> "TORRENT_DUP_MISSING"
        }))
        updateButtons()
        return duplicate
    }

    private fun showState(s: String) {
        lblState.text = s
    }

    private fun showSelection(sel: TorrentSelection?) {
        selection = sel
        foldLinks.isVisible = sel?.hasFolders == true
        tableModel.fireTableDataChanged()
        updateCount()
    }

    /** Folds or unfolds [row], keeping it the selected row so the keys go on working from there. */
    private fun fold(row: TorrentSelection.Row, expand: Boolean) {
        val sel = selection ?: return
        if (!row.isFolder || sel.isExpanded(row) == expand) return
        sel.setExpanded(row, expand)
        tableModel.fireTableDataChanged()
        select(row)
    }

    private fun select(row: TorrentSelection.Row) {
        val i = selection?.visibleRows?.indexOf(row) ?: -1
        if (i < 0) return
        table.setRowSelectionInterval(i, i)
        table.scrollRectToVisible(table.getCellRect(i, 1, true))
    }

    private fun foldAll(expand: Boolean) {
        val sel = selection ?: return
        if (expand) sel.expandAll() else sel.collapseAll()
        tableModel.fireTableDataChanged()
    }

    /** A click on a folder's arrow, or a double click anywhere on its name, folds or unfolds it. */
    private fun folderClicked(e: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(e)) return
        val viewRow = table.rowAtPoint(e.point)
        val viewCol = table.columnAtPoint(e.point)
        if (viewRow < 0 || viewCol < 0 || table.convertColumnIndexToModel(viewCol) != 1) return
        val row = selection?.visibleRows?.getOrNull(viewRow) ?: return
        if (!row.isFolder) return
        val x = e.x - table.getCellRect(viewRow, viewCol, false).x
        val arrowStart = NameLayout.indent(row.depth)
        val onArrow = x >= arrowStart - 2.px && x < arrowStart + NameLayout.ARROW_SLOT.px + 2.px
        if (onArrow && e.clickCount == 1 || e.clickCount == 2) fold(row, !selection!!.isExpanded(row))
    }

    /** Left folds the selected folder or goes up to its parent; Right unfolds it or steps into it. */
    private fun foldKey(expand: Boolean) {
        val sel = selection ?: return
        val row = sel.visibleRows.getOrNull(table.selectedRow) ?: return
        when {
            row.isFolder && sel.isExpanded(row) != expand -> fold(row, expand)
            !expand -> row.parent?.let { select(it) }
            row.isFolder -> sel.visibleRows.getOrNull(table.selectedRow + 1)?.takeIf { it.parent === row }?.let { select(it) }
        }
    }

    private fun selectAll(on: Boolean) {
        selection?.setAll(on) ?: return
        tableModel.fireTableDataChanged()
        updateCount()
    }

    private fun updateCount() {
        val sel = selection
        lblCount.text = if (sel == null) " " else String.format(
            text("TORRENT_FILE_COUNT"), sel.selectedCount, sel.fileCount, FormatHelper.formatSize(sel.selectedSize.toDouble())
        )
        updateStorage()
        updateButtons()
    }

    private fun updateStorage() {
        // The picker reports a change while it is being built, before this dialog's fields exist.
        @Suppress("SENSELESS_COMPARISON")
        if (storage == null) return
        storage.update(picker.targetFolder, selection?.selectedSize)
    }

    private fun updateButtons() {
        val sel = selection
        val action = duplicateAction
        val label = text(when (action) {
            DuplicateAction.SHOW -> "LBL_SHOW_PROGRESS"
            DuplicateAction.RESUME -> "MENU_RESUME"
            DuplicateAction.SEED -> "TORRENT_START_SEEDING"
            DuplicateAction.DOWNLOAD_AGAIN -> "TORRENT_DOWNLOAD_AGAIN"
            null -> "ND_DOWNLOAD"
        })
        if (btnDownload.text != label) {
            btnDownload.text = label
            // The three buttons share one width, worked out for the old label: work it out again.
            listOf(btnDownload, btnLater, btnCancel).forEach { it.preferredSize = null; it.minimumSize = null; it.maximumSize = null }
            sameWidth(btnDownload, btnLater, btnCancel)
            btnDownload.parent?.revalidate()
        }
        // Show / Resume / Start seeding act on the entry in the list: there is nothing to add later.
        btnLater.isVisible = !duplicate
        if (duplicate) {
            btnDownload.isEnabled = true
            return
        }
        val ok = key != null && (sel == null || sel.selectedCount > 0)
        btnDownload.isEnabled = ok
        btnLater.isEnabled = ok
    }

    private fun add(now: Boolean) {
        val k = key ?: return
        val existing = duplicateId?.let { AppContext.db.getById(it) }
        when (duplicateAction) {
            DuplicateAction.SHOW -> existing?.let { AppContext.app.showProgressWindow(it.id, it.fileName) }
            DuplicateAction.RESUME -> existing?.let { AppMenuHandler.resumeDownload(it) }
            DuplicateAction.SEED -> existing?.let { AppMenuHandler.startSeeding(it, this) }
            // Its files are gone: the old entry goes (nothing on disk to delete) and this one is added.
            DuplicateAction.DOWNLOAD_AGAIN -> existing?.let { AppContext.downloader.deleteDownload(it.id, false) }
            null -> {}
        }
        if (duplicate) return dispose()
        val sel = selection
        if (!hasRoomFor(sel)) return
        val magnet = (source as? TorrentSource.Magnet)?.uri
        val task = TorrentDownloadTaskInfo(
            id = uniqueId(),
            key = k,
            name = sel?.info?.name ?: magnet?.let { TorrentSource.displayName(it) } ?: k,
            folder = picker.targetFolder,
            magnet = magnet,
            categoryId = picker.selectedCategory?.id,
            filePriorities = sel?.priorities() ?: ByteArray(0),
            sequential = chkSequential.isSelected,
        )
        if (AppContext.downloader.startTorrentDownload(task, torrentBytes, runNow = now) == null) {
            MessageBox.show(this, title, text("TORRENT_ALREADY_ADDED"))
            return
        }
        dispose()
    }

    /**
     * False, after telling the user, when the destination volume cannot hold the selected files. A
     * torrent's sizes come from its metadata and are exact, so unlike an HTTP download (whose
     * Content-Length may be wrong) a short volume refuses the add; the dialog stays open to pick
     * another folder or fewer files. A magnet without metadata yet, or a volume that reports no
     * space, is let through: the disk-full error while downloading still catches it.
     */
    private fun hasRoomFor(sel: TorrentSelection?): Boolean {
        val needed = sel?.selectedSize?.takeIf { it > 0 } ?: return true
        val folder = picker.targetFolder
        val free = VolumeHints.spaceOf(folder)?.free ?: return true
        if (free >= needed) return true
        Logger.error("XDM", "Torrent not added: $folder has $free bytes free, selected files need $needed")
        fun size(bytes: Long) = FormatHelper.formatSize(bytes.toDouble())
        val message = text("MSG_TARGET_SPACE_LOW").replace("%folder%", folder)
            .replace("%free%", size(free)).replace("%needed%", size(needed)) + "\n" + text("TORRENT_SPACE_HINT")
        MessageBox.show(this, text("TITLE_DISK_SPACE_LOW"), message, JOptionPane.WARNING_MESSAGE)
        storage.update(folder, needed)
        return false
    }

    private inner class FilesModel : AbstractTableModel() {
        private val columns = arrayOf("", text("TORRENT_COL_NAME"), text("TORRENT_COL_SIZE"))
        private val rows get() = selection?.visibleRows.orEmpty()

        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(col: Int) = columns[col]
        override fun getColumnClass(col: Int) =
            if (col == 0) Boolean::class.javaObjectType else if (col == 1) TorrentSelection.Row::class.java else String::class.java

        override fun getValueAt(row: Int, col: Int): Any {
            val r = rows[row]
            return when (col) {
                0 -> selection?.isSelected(r) == true
                1 -> r
                else -> FormatHelper.formatSize(r.size.toDouble())
            }
        }

        override fun isCellEditable(row: Int, col: Int) = col == 0

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            val sel = selection ?: return
            sel.set(rows[row], value == true)
            // A folder changes its files, and a file its folders: repaint them all.
            fireTableDataChanged()
            updateCount()
        }
    }

    /**
     * The name column: indented by depth, a fold arrow on folders (an empty slot on files, so names line up),
     * then a folder or file icon; a partly picked folder is greyed.
     */
    private inner class NameRenderer : DefaultTableCellRenderer() {
        private val folderIcon = createIcon(RemixIcon.FOLDER_FILL, 16, Color(0xE8, 0xB3, 0x3C))
        private val fileIcon = createIcon(RemixIcon.FILE_LINE, 16, Color.GRAY)
        private val openIcon = ArrowIcon(UIManager.getIcon("Tree.expandedIcon"), folderIcon)
        private val closedIcon = ArrowIcon(UIManager.getIcon("Tree.collapsedIcon"), folderIcon)
        private val plainIcon = ArrowIcon(null, fileIcon)

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val r = value as TorrentSelection.Row
            super.getTableCellRendererComponent(table, r.name, isSelected, hasFocus, row, column)
            icon = when {
                !r.isFolder -> plainIcon
                selection?.isExpanded(r) == true -> openIcon
                else -> closedIcon
            }
            border = ScaledEmptyBorder(0, NameLayout.INDENT + NameLayout.STEP * r.depth, 0, 4)
            if (!isSelected && selection?.isPartial(r) == true) foreground = UIManager.getColor("Label.disabledForeground")
            return this
        }

        /** [arrow] centred in a fixed slot, then [icon]. */
        private inner class ArrowIcon(private val arrow: Icon?, private val icon: Icon) : Icon {
            override fun getIconWidth() = NameLayout.ARROW_SLOT.px + icon.iconWidth
            override fun getIconHeight() = maxOf(icon.iconHeight, arrow?.iconHeight ?: 0)
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                arrow?.paintIcon(c, g, x + (NameLayout.ARROW_SLOT.px - arrow.iconWidth) / 2, y + (iconHeight - arrow.iconHeight) / 2)
                icon.paintIcon(c, g, x + NameLayout.ARROW_SLOT.px, y + (iconHeight - icon.iconHeight) / 2)
            }
        }
    }

    private object NameLayout {
        /** Unscaled left padding, per-level indent and arrow slot width of the name column. */
        const val INDENT = 2
        const val STEP = 16
        const val ARROW_SLOT = 16

        /** Where a row's arrow slot starts in its cell, in pixels. */
        fun indent(depth: Int) = (INDENT + STEP * depth).px
    }

    companion object {
        private const val DIALOG_WIDTH = 640
        private const val DIALOG_HEIGHT = 520
        /** Larger is not a .torrent file anyone would make (the info dict of a huge v2 torrent is far below). */
        private const val MAX_TORRENT_FILE = 100L * 1024 * 1024
    }
}
