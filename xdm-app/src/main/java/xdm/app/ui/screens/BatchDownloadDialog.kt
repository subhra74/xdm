package xdm.app.ui.screens

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.AppContext
import xdm.app.BatchRequest
import xdm.app.BatchRequestItem
import xdm.app.I8N.text
import xdm.app.ui.components.CappedComboBox
import xdm.app.ui.components.MessageBox
import xdm.app.utils.BatchFolderState
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.batchFolderState
import xdm.app.utils.chooseFile
import xdm.app.utils.rememberHandPickedFolder
import xdm.app.resolvedNoneFolder
import xdm.app.utils.createIcon
import xdm.app.utils.defaultBatchName
import xdm.app.utils.dialogHeight
import xdm.app.utils.px
import xdm.app.utils.sameWidth
import xdm.app.utils.sanitizeBatchName
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import xdm.app.utils.suggestBatchName
import xdm.app.utils.uniqueFileNames
import xdm.app.utils.windowIconImages
import xdm.core.downloaders.BatchDownloadTaskInfo
import xdm.core.downloaders.BatchItem
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.integration.BatchVideoGrouper
import xdm.integration.SplitVideo
import xdm.integration.VideoHelper
import xdm.core.util.CoreUtils.uniqueId
import xdm.core.util.FileUtils
import xdm.core.util.FormatHelper
import xdm.core.util.getFileNameWithoutExtension
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 *
 * Links from the browser may include videos played as separate video and audio tracks (Facebook,
 * Instagram, bilibili; [BatchVideoGrouper]). With "Combine video and audio" ticked, each such video is
 * one row and one merged download; unticked, its tracks are ordinary rows. The tracks are probed in
 * the background (or taken from what the capture side already read), and "Find missing" adds tracks
 * the page's list left out but XDM saw the browser fetch. These controls share the count row under the
 * table, so the dialog keeps its height.
 *
 * An independent frame whoever opens it (docs/windows-and-modality.md): it has its own taskbar button
 * and stays when the main window goes to the tray. [near] is where it opens; null centres it.
 */
class BatchDownloadDialog(near: Window?, private val request: BatchRequest) : JFrame() {
    val btnOk = JButton(text("ND_DOWNLOAD"))

    private class BatchEntry(
        val item: BatchRequestItem,
        var fileName: String,
        var selected: Boolean = true,
        /** The user typed this name: the server must not replace it. */
        var nameEdited: Boolean = false,
        /** A video played as separate tracks, merged on download; null for an ordinary link. */
        val video: SplitVideo? = null,
    )

    private val grouping = if (request.fromBrowser) {
        BatchVideoGrouper.group(request.items.map { it.url }, request.items.map { it.cookieGroup })
    } else {
        BatchVideoGrouper.Grouping(request.items.indices.toList(), emptyList())
    }
    private val hasVideos = grouping.videos.isNotEmpty()

    /** The links as sent, for "Combine video and audio" unticked; tracks read in pieces are one row per file. */
    private val linkEntries: List<BatchEntry> = run {
        val plain = grouping.plain.map { request.items[it] }
        val tracks = grouping.videos.flatMap { v ->
            v.trackUrls().map { BatchRequestItem(url = it, cookieGroup = v.cookieGroup) }
        }
        val items = plain + tracks
        val names = uniqueFileNames(items.map { initialName(it) })
        items.mapIndexed { i, item -> BatchEntry(item, names[i]) }
    }

    /** The other links, then one row per video; the ordinary links are the same entries as in [linkEntries]. */
    private val combinedEntries: List<BatchEntry> = run {
        val plain = linkEntries.take(grouping.plain.size)
        // A generic name: the page title is the site's ("Instagram"), not the video's.
        val base = text("BATCH_VIDEO_NAME")
        val names = uniqueFileNames(plain.map { it.fileName } + grouping.videos.map { "$base.mp4" })
            .drop(plain.size)
        plain + grouping.videos.mapIndexed { i, v ->
            BatchEntry(BatchRequestItem(url = "", cookieGroup = v.cookieGroup), names[i], video = v)
        }
    }

    private var entries: List<BatchEntry> = if (hasVideos) combinedEntries else linkEntries
    private val combine: Boolean get() = entries === combinedEntries

    private val tableModel = BatchTableModel()
    private val lblCount = JLabel()

    private val chkCombine = JCheckBox(text("BATCH_COMBINE").format(grouping.videos.size)).apply {
        isSelected = true
        isVisible = hasVideos
        margin = Insets(0, 0, 0, 0)
    }
    private val lblMissing = JLabel()

    /** "Find missing" found nothing more: the link stays hidden and the hint says what to do instead. */
    private var findExhausted = false
    private val lnkFindMissing = JLabel(text("BATCH_FIND_MISSING")).apply {
        foreground = UIManager.getColor("Component.linkColor") ?: Color(0x2F, 0x6F, 0xD0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    /** Reads the videos' track heads; a few at a time, stopped when the dialog closes. */
    private val probePool: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "batch-probe").apply { isDaemon = true }
    }

    private val radioBatch = JRadioButton(text("BATCH_MODE_ONE"))
    private val radioEach = JRadioButton(text("BATCH_MODE_EACH"))
    private val lblName = JLabel(text("BATCH_NAME"))
    private val txtName = JTextField(defaultBatchName(request.pageTitle, request.pageUrl)).apply {
        putClientProperty(FlatClientProperties.STYLE, "arc: 10")
        columns = 10 // a long page title must not widen the dialog; the row stretches the field
    }

    /**
     * Save in, for both modes: the None folder first, then the folder history and the default folder.
     * One download per link is always None (no category); docs/download-categories.md §4.4.
     */
    private val folderModel = DefaultComboBoxModel<String>()
    private val cmbSaveIn = CappedComboBox(folderModel)

    /** Set once the user picks or browses to a folder, so only a hand-chosen folder is remembered. */
    private var folderHandPicked = false
    private var fillingFolders = false

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

        override fun getValueAt(row: Int, col: Int): Any {
            val entry = entries[row]
            entry.video?.let { return videoCell(it, col) ?: entry.selected }
            return when (col) {
                0 -> entry.selected
                1 -> entry.fileName
                2 -> entry.item.knownSize?.let { FormatHelper.formatSize(it.toDouble()) } ?: "–"
                3 -> entry.item.url
                else -> ""
            }
        }

        private fun videoCell(video: SplitVideo, col: Int): Any? {
            val pick = video.pick()
            return when (col) {
                1 -> entries.first { it.video === video }.fileName
                2 -> if (pick.size > 0) FormatHelper.formatSize(pick.size.toDouble()) else if (video.probing) "…" else "–"
                3 -> when {
                    pick.complete -> text("BATCH_VIDEO_MERGED").format(VideoHelper.splitTrackQuality(pick.video!!) ?: "")
                        .trimEnd(' ', '·')
                    video.probing -> text("BATCH_VIDEO_CHECKING")
                    pick.video != null -> text("BATCH_VIDEO_NO_AUDIO")
                    pick.audio != null -> text("BATCH_VIDEO_NO_VIDEO")
                    else -> text("BATCH_VIDEO_UNREADABLE")
                }
                else -> null
            }
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
        iconImages = windowIconImages
        defaultCloseOperation = DISPOSE_ON_CLOSE
        initUI()
        fillFolders()
        val asOne = if (request.fromBrowser) AppContext.config.batchAsOneFromBrowser
        else AppContext.config.batchAsOneFromClipboard
        (if (asOne) radioBatch else radioEach).isSelected = true
        applyMode()
        updateCount()
        isResizable = true
        // From the browser it must not open behind the browser window.
        isAlwaysOnTop = request.fromBrowser
        size = Dimension(DIALOG_WIDTH.px, dialogHeight(DIALOG_HEIGHT))
        setLocationRelativeTo(near)

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
                probePool.shutdownNow()
            }
        })
        if (hasVideos) {
            useKnownTracks(addMissing = false)
            grouping.videos.forEach { startProbes(it) }
        }
    }

    // ---- videos played as separate tracks ----

    /**
     * Takes what the capture side already read about these videos' tracks, so they need no probe. With
     * [addMissing], also adds the tracks it saw that the page's list left out. Returns how many changed.
     */
    private fun useKnownTracks(addMissing: Boolean): Int {
        val keys = grouping.videos.flatMapTo(HashSet()) { it.trackKeys }
        val known = VideoHelper.knownSplitTracks(keys)
        var changed = 0
        for (video in grouping.videos) {
            val listed = video.trackKeys
            for (track in known[video.assetKey].orEmpty()) {
                if (!addMissing && track.url.trackKey !in listed) continue
                if (video.addKnown(track.url, track.probe)) changed++
            }
        }
        return changed
    }

    private fun startProbes(video: SplitVideo) {
        if (!combine) return
        val cookie = request.cookies.getOrNull(video.cookieGroup)
        for (track in video.nextProbes()) {
            probePool.submit {
                val probe = VideoHelper.probeSplitTrack(track.url.url, request.headers, cookie)
                video.probed(track, probe)
                SwingUtilities.invokeLater { onVideoChanged(video) }
            }
        }
    }

    /** A probe finished: try the next track if that one failed (or a better one is waiting), then repaint. */
    private fun onVideoChanged(video: SplitVideo) {
        if (!isDisplayable) return
        startProbes(video)
        val row = entries.indexOfFirst { it.video === video }
        if (row >= 0) tableModel.fireTableRowsUpdated(row, row)
        updateCount()
    }

    private fun findMissing() {
        val added = useKnownTracks(addMissing = true)
        grouping.videos.forEach { startProbes(it) }
        tableModel.fireTableDataChanged()
        if (added == 0) findExhausted = true
        updateCount()
    }

    private fun setCombine(on: Boolean) {
        entries = if (on) combinedEntries else linkEntries
        tableModel.fireTableDataChanged()
        if (on) grouping.videos.forEach { startProbes(it) }
        updateCount()
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
        // The count and the video controls share one row, so the dialog does not grow.
        val videoControls = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(chkCombine)
            add(Box.createRigidArea(scaledSize(12, 0)))
            add(lblMissing)
            add(Box.createRigidArea(scaledSize(6, 0)))
            add(lnkFindMissing)
        }
        chkCombine.addActionListener { setCombine(chkCombine.isSelected) }
        lnkFindMissing.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = findMissing()
        })
        val countRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(lblCount, BorderLayout.WEST)
            add(videoControls, BorderLayout.EAST)
        }
        val center = JPanel(BorderLayout(0, 4.px)).apply {
            border = ScaledEmptyBorder(0, 10, 6, 10)
            add(JScrollPane(table))
            add(countRow, BorderLayout.SOUTH)
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
        cmbSaveIn.addActionListener {
            if (!fillingFolders) folderHandPicked = true
            validateInput()
        }

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

    private fun fillFolders() {
        fillingFolders = true
        try {
            val config = AppContext.config
            (listOf(config.resolvedNoneFolder) + config.folderHistory + config.defaultDownloadFolder)
                .map { File(it).absolutePath }.distinct().forEach { folderModel.addElement(it) }
            cmbSaveIn.selectedIndex = 0
        } finally {
            fillingFolders = false
        }
    }

    private fun browse() {
        val selected = chooseFile(this, directoriesOnly = true, currentDir = File(selectedFolder())) ?: return
        val path = selected.absolutePath
        val idx = folderModel.getIndexOf(path).takeIf { it >= 0 } ?: run {
            folderModel.insertElementAt(path, 0)
            0
        }
        cmbSaveIn.selectedIndex = idx
        folderHandPicked = true
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
        validateInput()
        revalidate()
    }

    private fun selectedFolder(): String =
        cmbSaveIn.selectedItem?.toString() ?: AppContext.config.resolvedNoneFolder

    /** Shows where the batch goes and whether that folder can be used; enables Download accordingly. */
    private fun validateInput() {
        // A video still being read cannot be downloaded yet: which tracks it gets is not known.
        val anySelected = entries.any { it.selected } && entries.none { it.selected && it.video?.probing == true }
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
        val incomplete = if (combine) grouping.videos.count { !it.probing && !it.pick().complete } else 0
        lblMissing.isVisible = incomplete > 0
        lnkFindMissing.isVisible = incomplete > 0 && !findExhausted
        lblMissing.text = text("BATCH_MISSING").format(incomplete)
        lblMissing.toolTipText = if (findExhausted) text("BATCH_MISSING_HINT") else null
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
        val items = selected.mapIndexedNotNull { i, e ->
            val video = e.video ?: return@mapIndexedNotNull BatchItem(
                url = e.item.url,
                fileName = names[i],
                cookieGroup = e.item.cookieGroup,
                knownSize = e.item.knownSize,
                nameFromPage = e.item.fileName != null || e.nameEdited,
            )
            val pick = video.pick()
            val single = pick.video ?: pick.audio ?: return@mapIndexedNotNull null
            BatchItem(
                url = single.url,
                fileName = if (pick.video == null) withExtension(names[i], "m4a") else names[i],
                cookieGroup = video.cookieGroup,
                knownSize = pick.size.takeIf { it > 0 },
                nameFromPage = true,
                audioUrl = pick.audio?.url?.takeIf { pick.complete },
            )
        }
        if (items.isEmpty()) return
        val task = BatchDownloadTaskInfo(
            id = uniqueId(),
            name = name,
            folder = parent,
            headers = request.headers,
            origin = request.pageUrl,
            maxPiece = AppContext.config.maxSegments,
            cookies = request.cookies,
            items = items,
            tempDir = AppContext.config.tempFolder.takeIf { items.any { it.merged } },
        )
        if (!AppContext.downloader.startBatchDownload(task)) {
            MessageBox.show(this, text("MENU_BATCH_DOWNLOAD"), text("BATCH_FOLDER_ERROR").format(task.batchFolder))
            validateInput()
            return
        }
        // A batch has no category: its folder joins the history but does not become the None folder.
        if (folderHandPicked) rememberHandPickedFolder(parent, underNone = false)
        rememberMode()
        dispose()
    }

    private fun downloadEach() {
        val folder = selectedFolder()
        // One download per link is a None download: a hand-picked folder becomes the None folder.
        if (folderHandPicked) rememberHandPickedFolder(folder, underNone = true)
        rememberMode()
        entries.filter { it.selected }.forEach { e ->
            e.video?.let { video ->
                downloadVideo(video, e.fileName, folder)
                return@forEach
            }
            AppContext.downloader.startHttpDownload(
                HttpDownloadTaskInfo(
                    id = uniqueId(),
                    url = e.item.url,
                    fileName = e.fileName,
                    respectFileName = e.item.fileName != null || e.nameEdited,
                    cookie = request.cookies.getOrNull(e.item.cookieGroup),
                    headers = request.headers,
                    origin = request.pageUrl,
                    categoryId = null,
                    targetFolder = folder,
                    maxPiece = AppContext.config.maxSegments,
                    authInfo = null,
                    knownFileSize = e.item.knownSize,
                )
            )
        }
        dispose()
    }

    /** One download for a video: merged when both tracks were found, else the one track as it is. */
    private fun downloadVideo(video: SplitVideo, fileName: String, folder: String) {
        val pick = video.pick()
        val cookie = request.cookies.getOrNull(video.cookieGroup)
        if (pick.complete) {
            VideoHelper.splitTrackTask(
                uniqueId(), pick.video!!, pick.audio!!, fileName, request.pageUrl, request.headers, cookie, null, folder,
            )?.let { AppContext.downloader.startDashDownload(it) }
            return
        }
        val single = pick.video ?: pick.audio ?: return
        AppContext.downloader.startHttpDownload(
            HttpDownloadTaskInfo(
                id = uniqueId(),
                url = single.url,
                fileName = if (pick.video == null) withExtension(fileName, "m4a") else fileName,
                respectFileName = true,
                cookie = cookie,
                headers = request.headers,
                origin = request.pageUrl,
                categoryId = null,
                targetFolder = folder,
                maxPiece = AppContext.config.maxSegments,
                authInfo = null,
                knownFileSize = single.probe.totalSize,
            )
        )
    }

    private fun withExtension(name: String, ext: String) =
        getFileNameWithoutExtension(name) + "." + ext

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
