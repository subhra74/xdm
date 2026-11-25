package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.components.AppMenuHandler
import xdm.app.ui.components.CircularProgress
import xdm.app.ui.components.SegmentPanel
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.WEBSITE_URL
import xdm.app.utils.padColumns
import xdm.app.utils.gbAdd
import xdm.app.utils.openInDefaultBrowser
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import xdm.app.utils.windowIconImages
import xdm.app.utils.px
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.SegmentProgress
import xdm.core.downloaders.web.SlotProgress
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.util.FormatHelper.formatSize
import xdm.core.util.FormatHelper.toLongEta
import xdm.core.util.MovePhase
import xdm.manager.BatchFileCounts
import xdm.manager.RecordStatus
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Taskbar
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * The progress window of an HTTP, HLS/DASH or batch download (400 × 260):
 *
 * ```
 * ( 46% )  ubuntu-24.04-desktop.iso
 *          Downloading
 *          Download 46%            68.9 MB / 150.0 MB
 *          ▮▮▮▮▯▮▮▯▯▮▯▯   (segments)
 *          Speed 4.2 MB/s                 19 sec left
 * [Less]                               [Cancel] [Pause]
 *  #  Status        Downloaded     (a row per connection, numbered 1..n)
 * ```
 *
 * More/Less shows or hides the table below the buttons, adding [MORE_HEIGHT] px; the choice is remembered
 * ([xdm.app.IAppConfig.progressMore]). The table has a row per connection slot, which the download's progress
 * tracker keeps ([xdm.core.downloaders.web.ConnectionSlots]): never more rows than the most segments live at
 * once, each with the bytes of every segment that used it; the bar above shows all the segments.
 *
 * Pause keeps the window open: the button turns into a disabled Resume until the download has really
 * paused ([showPaused], from the paused event), then Resume starts it again in the same window ([showStarted]).
 * Cancel stops the download and closes the window at once.
 */
class ProgressWindow(val id: Long) : JFrame() {
    private companion object {
        const val BASE_HEIGHT = 260
        const val MORE_HEIGHT = 150

        /**
         * Resolved once: `isTaskbarSupported`/`getTaskbar`/`isSupported` used to run on every progress
         * update (once a second per download), and showed up in profiles as `CTaskbarPeer.isSupported`.
         * Null when this platform has no window progress indicator.
         */
        val windowProgressTaskbar: Taskbar? by lazy {
            runCatching {
                if (!Taskbar.isTaskbarSupported()) return@runCatching null
                Taskbar.getTaskbar().takeIf { it.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW) }
            }.getOrNull()
        }
    }

    /** Where the download stands as far as the Pause/Resume button is concerned. */
    private enum class RunState { RUNNING, PAUSING, PAUSED, RESUMING }

    private var runState = RunState.RUNNING

    /** Pause was clicked here and the paused event has not arrived yet: that event keeps this window open. */
    val isPausePending: Boolean get() = runState == RunState.PAUSING

    /** Paused, or resumed but not started yet (queued): the window has no running download. */
    val isPaused: Boolean get() = runState == RunState.PAUSED || runState == RunState.RESUMING

    private var isError: Boolean = false
    fun showError(error: DownloadError) {
        this.isError = true
        lblStat4.text = ""
        lblStat.text = text("MSG_FAILED")
        val message = downloadErrorText(error)
        // Messages are kept short (the help link explains the details), but still wrap rather than being cut off.
        // The message takes both columns: the size label next to it would otherwise squeeze it.
        val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        lblStat3.text = "<html><body style='width:220px'>$escaped</body></html>"
        lblStat4.isVisible = false
        (contentPane.layout as GridBagLayout).let { gl ->
            gl.setConstraints(lblStat3, gl.getConstraints(lblStat3).apply { gridwidth = 2 })
        }
        helpUrl = downloadErrorHelpUrl(error)
        lblHelp.isVisible = helpUrl != null
        lblStat3.toolTipText = message
        btnPauseResume.text = text("LBL_CLOSE")
        // It may have been disabled while finalizing, and Close must always work.
        btnPauseResume.isEnabled = true
        btnCancel.isVisible = false
        // Long messages need more lines than the progress layout has room for: grow, never shrink.
        validate()
        val needed = preferredSize.height
        if (needed > height) setSize(width, needed)
    }

    /**
     * The publish phase: the file is complete and is being moved into the destination folder.
     * Download progress events have stopped by now, so this window is driven separately or it
     * would sit frozen at 100%. Speed and ETA are blank because neither applies to a local copy.
     * Pause is disabled while finalizing, which cannot be undone (see DownloadManager.stopDownload).
     */
    fun showMoving(phase: MovePhase, prg: Int) {
        val status = moveText(phase, prg)
        lblStat.text = text("STAT_PUBLISHING")
        title = "[ $prg% ] $status"
        lblStat1.text = status
        lblStat3.text = ""
        lblStat4.text = ""
        btnPauseResume.isEnabled = runState == RunState.RUNNING && phase != MovePhase.FINALIZING
        this.prg.value = prg
        windowProgressTaskbar?.setWindowProgressValue(this, prg)
    }

    fun updateProgress(
        fileName: String?,
        downloaded: Long,
        size: Long,
        speed: Float,
        eta: Long,
        prg: Int,
        segData: Collection<SegmentProgress>,
        slots: List<SlotProgress>,
        files: BatchFileCounts? = null,
    ) {
        // A late update from the stopped session must not overwrite the paused state.
        if (isPaused) return
        // Muxing reports progress through the same call, with [prg] as the assembling percent and no segments.
        val record = synchronized(AppContext.db) { AppContext.db.getById(id) }
        val assembling = record?.status == RecordStatus.ASSEMBLING
        // Every connection is waiting on a busy server: say when the next attempt is due.
        val retryAt = record?.retryAt?.takeIf { it > System.currentTimeMillis() && !assembling }
        lblStat.text = files?.let { batchFilesText(it) } ?: text(if (assembling) "STAT_ASSEMBLING" else "DWN_TITLE")
        lblFileName.text = fileName
        title = "[ $prg% ] $fileName"
        textBuf.clear()
        if (assembling) {
            textBuf.append(text("STAT_ASSEMBLING") + " " + prg + "%")
        } else if (prg > 0) {
            textBuf.append(text("DWN_DOWNLOAD") + " " + prg + "%")
        } else {
            textBuf.append(text("DWN_TITLE"))
        }
        lblStat1.text = textBuf.toString()
        textBuf.clear()
        if (downloaded > 0) {
            textBuf.append(formatSize(downloaded.toDouble()))
        } else {
            textBuf.append("---")
        }
        // A batch total only counts the sizes known so far, so it can fall behind what is downloaded
        if (files == null || size <= 0 || downloaded <= size) {
            textBuf.append(" / ")
            if (size > 0) {
                textBuf.append(formatSize(size.toDouble()))
            } else {
                textBuf.append("---")
            }
        }
        lblStat2.text = textBuf.toString()
        if (retryAt != null) {
            lblStat3.text = retryAtText(retryAt)
        } else if (speed > 0) {
            lblStat3.text = "Speed " + formatSize(speed.toDouble()) + "/s"
        } else {
            lblStat3.text = "Speed ---"
        }
        if (eta > 0) {
            lblStat4.text = toLongEta(eta) + " left"
        } else {
            lblStat4.text = "---"
        }
        this.prg.value = prg
        // Not decided by [assembling]: that is the record's status now, and the download's last update (all
        // slots finished) often reaches this thread after the status has already turned to assembling.
        showSegments(segData, slots)
        windowProgressTaskbar?.setWindowProgressValue(this, prg)
    }

    /** The download has really paused (its paused event): Resume is ready. */
    fun showPaused() {
        runState = RunState.PAUSED
        lblStat.text = text("STAT_PAUSED")
        lblStat1.text = text("STAT_PAUSED")
        lblStat3.text = "Speed ---"
        lblStat4.text = "---"
        btnPauseResume.text = text("MENU_RESUME")
        btnPauseResume.isEnabled = true
    }

    /** A download session started in this window (a resume, or the first start): the button pauses again. */
    fun showStarted() {
        if (isError) return
        runState = RunState.RUNNING
        btnPauseResume.text = text("MENU_PAUSE")
        btnPauseResume.isEnabled = true
    }

    /** The bar and the table; an update without them (assembling, muxing) keeps the last ones. */
    fun showSegments(segData: Collection<SegmentProgress>, slots: List<SlotProgress>) {
        if (segData.isNotEmpty()) segPanel.setValues(segData)
        segModel.update(slots)
    }

    private val textBuf = StringBuilder(100)
    /**
     * Closes this window *and* drops it from [xdm.app.AppInstance]'s map, so the per-second progress
     * updates stop. Plain `dispose()` left the window in that map, and it kept formatting labels and
     * calling the taskbar for the rest of the download.
     */
    private fun hideWindow() {
        AppContext.app.hideProgressWindow(id)
        dispose() // no-op if hideProgressWindow already disposed it; needed when it is no longer mapped (error state)
    }

    private val btnMore = JButton().apply { addActionListener { setMore(!tableHolder.isVisible, remember = true) } }
    private val segModel = SegmentTableModel()
    private val tableHolder = JPanel(BorderLayout()).apply {
        border = ScaledEmptyBorder(0, 10, 10, 10)
        add(JScrollPane(segmentTable()))
        // Not the table's own preferred height (hundreds of px): GridBag would squeeze the labels to fit it.
        preferredSize = Dimension(10.px, (MORE_HEIGHT - 10).px)
        minimumSize = Dimension(10.px, 40.px)
    }
    private val btnPauseResume = JButton(text("MENU_PAUSE")).apply { addActionListener { pauseOrResume() } }
    private val btnCancel = JButton(text("ND_CANCEL")).apply { addActionListener { cancelDownload() } }
    val prg = CircularProgress().apply {
        value = 0
        preferredSize = scaledSize(80, 80)
        minimumSize = scaledSize(80, 80)
    }
    val segPanel = SegmentPanel().apply {
        preferredSize = scaledSize(80, 5)
    }
    val lblFileName = JLabel("---")
    val lblStat = JLabel(text("DWN_TITLE")).apply {
        font = this.font.deriveFont(font.size.toFloat() - 1)
        foreground = Color.GRAY
    }
    val lblStat1 = JLabel("---")
    val lblStat2 = JLabel("---")
    val lblStat3 = JLabel("---")
    val lblStat4 = JLabel("---")

    /** The website page for the current error; set by [showError]. */
    private var helpUrl: String? = null
    /** A small link under the short error text; the details live on the XDM website. */
    private val lblHelp = JLabel(text("ERR_HELP_LINK")).apply {
        isVisible = false
        font = font.deriveFont(font.size2D - 1f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = UIManager.getColor("ProgressBar.foreground")
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                helpUrl?.let { openInDefaultBrowser(it) }
            }
        })
    }

    init {
        title = text("DWN_TITLE")
        // A top-level frame of its own, with its own taskbar button: without this it shows Java's icon.
        iconImages = windowIconImages
        size = scaledSize(400, BASE_HEIGHT)

        val b1 = Box.createHorizontalBox().apply {
            border = ScaledEmptyBorder(10, 15, 10, 15)
            add(btnMore)
            add(Box.createHorizontalGlue())
            add(btnCancel)
            add(Box.createRigidArea(scaledSize(10, 10)))
            add(btnPauseResume)
            // No band of its own: the buttons and the table sit on the window's background.
        }

        val gridBagLayout = GridBagLayout().apply {
            columnWidths = intArrayOf(0, 0, 0, 0, 0, 0, 0)
            rowHeights = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
            columnWeights = doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            // Row 5 (the help link) takes the spare height, or row 7 (the table) while it is shown: see setMore.
            rowWeights = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
        contentPane.layout = gridBagLayout

        gbAdd(
            prg, contentPane, padding = scaledInsets(20, 15, 0, 0),
            rowSpan = 5, alignment = GridBagConstraints.NORTHWEST
        )
        gbAdd(
            lblFileName,
            contentPane,
            gridX = 1,
            padding = scaledInsets(20, 10, 0, 15),
            alignment = GridBagConstraints.SOUTHWEST,
            colSpan = 2,
        )
        gbAdd(
            lblStat,
            contentPane,
            gridX = 1,
            gridY = 1,
            padding = scaledInsets(5, 10, 7, 15),
            alignment = GridBagConstraints.NORTHWEST,
            colSpan = 2,
        )
        gbAdd(
            lblStat1,
            contentPane,
            gridX = 1,
            gridY = 2,
            padding = scaledInsets(9, 10, 5, 5),
            alignment = GridBagConstraints.WEST,
        )
        gbAdd(
            lblStat2,
            contentPane,
            gridX = 2,
            gridY = 2,
            padding = scaledInsets(9, 10, 5, 15),
            alignment = GridBagConstraints.EAST,
        )
        gbAdd(
            segPanel,
            contentPane,
            gridX = 1,
            gridY = 3,
            padding = scaledInsets(5, 10, 5, 15),
            alignment = GridBagConstraints.WEST,
            colSpan = 2,
            horizontalFill = true,
        )
        gbAdd(
            lblStat3,
            contentPane,
            gridX = 1,
            gridY = 4,
            padding = scaledInsets(5, 10, 5, 5),
            alignment = GridBagConstraints.WEST,
        )
        gbAdd(
            lblStat4,
            contentPane,
            gridX = 2,
            gridY = 4,
            padding = scaledInsets(5, 10, 5, 15),
            alignment = GridBagConstraints.EAST,
        )
        gbAdd(
            lblHelp,
            contentPane,
            gridX = 1,
            gridY = 5,
            padding = scaledInsets(5, 10, 5, 15),
            alignment = GridBagConstraints.NORTHWEST,
            colSpan = 2,
        )
        gbAdd(
            b1,
            contentPane,
            gridX = 0,
            gridY = 6,
            alignment = GridBagConstraints.WEST,
            colSpan = 3,
            horizontalFill = true,
        )
        contentPane.add(tableHolder, GridBagConstraints().apply {
            gridx = 0; gridy = 7; gridwidth = 3; fill = GridBagConstraints.BOTH
        })
        setMore(AppContext.config.progressMore, remember = false)
        rootPane.defaultButton = btnPauseResume
        addWindowListener(
            object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent) {
                    btnPauseResume.requestFocusInWindow()
                }

                /** Closing the window must also stop the updates; see [hideWindow]. */
                override fun windowClosing(e: WindowEvent) {
                    hideWindow()
                }

                override fun windowClosed(e: WindowEvent) {
                    System.gc()
                }
            })
        setLocationRelativeTo(null)
    }

    /** Shows or hides the segment table, growing or shrinking the window by its [MORE_HEIGHT] px. */
    private fun setMore(on: Boolean, remember: Boolean) {
        tableHolder.isVisible = on
        btnMore.text = text(if (on) "TW_LESS" else "TW_MORE")
        (contentPane.layout as GridBagLayout).rowWeights.let {
            it[5] = if (on) 0.0 else 1.0
            it[7] = if (on) 1.0 else 0.0
        }
        // Relative, so a window an error message made taller keeps its extra lines.
        setSize(width, if (on) height + MORE_HEIGHT.px else maxOf(height - MORE_HEIGHT.px, BASE_HEIGHT.px))
        validate()
        if (remember) AppContext.config.progressMore = on
    }

    private fun segmentTable() = JTable(segModel).apply {
        rowHeight = 22.px
        fillsViewportHeight = true
        tableHeader.reorderingAllowed = false
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        intArrayOf(40, 110, 200).forEachIndexed { i, w -> columnModel.getColumn(i).preferredWidth = w.px }
        columnModel.getColumn(0).cellRenderer = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.RIGHT }
        padColumns()
    }

    private fun pauseOrResume() {
        if (isError) {
            dispose()
            return
        }
        when (runState) {
            RunState.RUNNING -> {
                // Disabled until the paused event arrives (AppInstance.progressSessionEnded → showPaused).
                runState = RunState.PAUSING
                btnPauseResume.text = text("MENU_RESUME")
                btnPauseResume.isEnabled = false
                record()?.let { AppMenuHandler.pauseDownload(it) }
            }
            RunState.PAUSED -> {
                // Disabled until the session starts (AppInstance.showProgressWindow → showStarted); it may wait in the queue.
                runState = RunState.RESUMING
                btnPauseResume.isEnabled = false
                record()?.let { AppMenuHandler.resumeDownload(it) }
                // The session will not show a progress window, so this one would never hear that it started.
                if (!AppContext.config.showDownloadProgressWindow) hideWindow()
            }
            RunState.PAUSING, RunState.RESUMING -> {}
        }
    }

    /** Stops the download (unless it is already paused) and closes the window without waiting. */
    private fun cancelDownload() {
        if (runState == RunState.RUNNING || runState == RunState.RESUMING) record()?.let { AppMenuHandler.pauseDownload(it) }
        hideWindow()
    }

    private fun record() = synchronized(AppContext.db) { AppContext.db.getById(id) }

    /** `Files: 1,234 / 5,000 · 3 failed`, the failed part only when there are failures. */
    private fun batchFilesText(files: BatchFileCounts): String {
        val counts = text("BATCH_FILES_PROGRESS").format("%,d".format(files.done), "%,d".format(files.total))
        return if (files.failed > 0) "$counts · ${text("BATCH_FILES_FAILED").format("%,d".format(files.failed))}" else counts
    }
}

/**
 * The connection slots the download's tracker keeps ([xdm.core.downloaders.web.ConnectionSlots]), one row each,
 * numbered 1..n. An update without slots (assembling, stopping, a download type without them) keeps the rows.
 */
internal class SegmentTableModel : AbstractTableModel() {
    private val columns = arrayOf("#", text("TW_STATUS"), text("TW_DOWNLOADED"))
    private var slots: List<SlotProgress> = emptyList()

    fun update(slots: List<SlotProgress>) {
        if (slots.isEmpty()) return
        this.slots = slots
        fireTableDataChanged()
    }

    /** The status each row shows, for tests. */
    internal val statuses: List<ChunkStatus> get() = slots.map { it.status }

    override fun getRowCount() = slots.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(c: Int) = columns[c]
    override fun getValueAt(r: Int, c: Int): Any {
        val s = slots[r]
        return when (c) {
            0 -> (r + 1).toString()
            1 -> text(
                when (s.status) {
                    ChunkStatus.Ready -> "SEG_CONNECTING"
                    ChunkStatus.Downloading -> "STAT_DOWNLOADING"
                    ChunkStatus.Failed -> "STAT_FAILED"
                    else -> "SEG_COMPLETED"
                }
            )
            else -> formatSize(s.bytes.toDouble())
        }
    }
}

/** The message shown for a failed download (or, in a batch, a failed file). */
fun downloadErrorText(error: DownloadError): String {
    return when (error) {
        DownloadError.InvalidResponse -> text("ERR_INVALID_RESP")
        DownloadError.NetworkError -> text("ERR_CONN_FAILED")
        DownloadError.DiskSpaceError -> text("ERR_DISK_FAILED")
        DownloadError.ResumeNotSupported -> text("ERR_NO_RESUME")
        DownloadError.SessionExpired -> text("ERR_SESSION_FAILED")
        DownloadError.LinkExpired -> text("ERR_LINK_EXPIRED")
        DownloadError.TlsError -> text("ERR_TLS")
        DownloadError.DecryptionError -> text("ERR_DECRYPT")
        DownloadError.OutputWriteError -> text("ERR_OUTPUT_WRITE")
        DownloadError.MuxError -> text("ERR_MUX")
        DownloadError.ServerBusy -> text("ERR_SERVER_BUSY")
        else -> text("ERR_INTERNAL")
    }
}

/** "Server busy, retrying at 14:32:05": a download whose every connection waits on a busy server. */
fun retryAtText(at: Long): String =
    String.format(text("STAT_RETRY_AT"), java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at)))

/**
 * The website page explaining [error] and how to fix it, or null when there is nothing to fix
 * ([DownloadError.Cancelled] is the user's own action). Slugs match the pages under `/help/errors/`.
 */
fun downloadErrorHelpUrl(error: DownloadError): String? {
    val slug = when (error) {
        DownloadError.NetworkError -> "network-error"
        DownloadError.InvalidResponse -> "invalid-response"
        DownloadError.SessionExpired -> "session-expired"
        DownloadError.LinkExpired -> "link-expired"
        DownloadError.DiskSpaceError -> "disk-error"
        DownloadError.InternalError -> "internal-error"
        DownloadError.ResumeNotSupported -> "resume-not-supported"
        DownloadError.MuxError -> "video-merge-failed"
        DownloadError.TlsError -> "tls-error"
        DownloadError.DecryptionError -> "decryption-error"
        DownloadError.OutputWriteError -> "cannot-save-file"
        DownloadError.Cancelled -> return null
        DownloadError.ServerBusy -> "server-busy"
    }
    return "$WEBSITE_URL/help/errors/$slug/"
}
