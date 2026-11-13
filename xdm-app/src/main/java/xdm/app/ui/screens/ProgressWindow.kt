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
 * [Less]                                 [Hide] [Pause]
 *  #  Status        Downloaded     (a row per connection, numbered 1..n)
 * ```
 *
 * More/Less shows or hides the table below the buttons, adding [MORE_HEIGHT] px; the choice is remembered
 * ([xdm.app.IAppConfig.progressMore]). The table has a row per connection slot ([SegmentTableModel]), so it
 * never has more rows than the most segments live at once; the bar above shows all of them.
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
        btnHide.isVisible = false
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
        btnPauseResume.isEnabled = phase != MovePhase.FINALIZING
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
        files: BatchFileCounts? = null,
    ) {
        // Muxing reports progress through the same call, with [prg] as the assembling percent and no segments.
        val assembling = synchronized(AppContext.db) { AppContext.db.getById(id)?.status == RecordStatus.ASSEMBLING }
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
        if (speed > 0) {
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
        // The bar and the table keep the last download state while assembling.
        if (!assembling) {
            segPanel.setValues(segData)
            segModel.update(segData)
        }
        windowProgressTaskbar?.setWindowProgressValue(this, prg)
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

    private val btnHide = JButton(text("DWN_HIDE")).apply { addActionListener { hideWindow() } }
    private val btnMore = JButton().apply { addActionListener { setMore(!tableHolder.isVisible, remember = true) } }
    private val segModel = SegmentTableModel()
    private val tableHolder = JPanel(BorderLayout()).apply {
        border = ScaledEmptyBorder(0, 10, 10, 10)
        add(JScrollPane(segmentTable()))
        // Not the table's own preferred height (hundreds of px): GridBag would squeeze the labels to fit it.
        preferredSize = Dimension(10.px, (MORE_HEIGHT - 10).px)
        minimumSize = Dimension(10.px, 40.px)
    }
    private val btnPauseResume = JButton(text("MENU_PAUSE")).apply { addActionListener { pauseDownload() } }
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
            add(btnHide)
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

    private fun pauseDownload() {
        if (isError) {
            dispose()
            return
        }
        synchronized(AppContext.db) {
            AppContext.db.getById(id)?.let { e ->
                AppMenuHandler.pauseDownload(e)
            }
        }
    }

    /** `Files: 1,234 / 5,000 · 3 failed`, the failed part only when there are failures. */
    private fun batchFilesText(files: BatchFileCounts): String {
        val counts = text("BATCH_FILES_PROGRESS").format("%,d".format(files.done), "%,d".format(files.total))
        return if (files.failed > 0) "$counts · ${text("BATCH_FILES_FAILED").format("%,d".format(files.failed))}" else counts
    }
}

/**
 * One row per connection slot, numbered 1 to the most segments that were live at once. A segment that
 * stops being live keeps its row, shown finished (or failed), until a newly started segment takes the
 * row over; so with 8 connections the table holds 8 rows to the end, the last ones finished.
 */
internal class SegmentTableModel : AbstractTableModel() {
    private class Slot(val id: Long, var status: ChunkStatus, var bytes: Long, var live: Boolean)

    private val columns = arrayOf("#", text("TW_STATUS"), text("TW_DOWNLOADED"))
    private val slots = ArrayList<Slot>()

    fun update(segments: Collection<SegmentProgress>) {
        val byId = segments.associateBy { it.id }
        for (slot in slots) {
            val s = byId[slot.id]
            if (s != null) slot.bytes = s.bytes
            slot.live = s?.live == true
            // Gone from the list (a part merged into its neighbour) counts as done too.
            slot.status = if (s == null || (!s.live && s.status != ChunkStatus.Failed)) ChunkStatus.Finished else s.status
        }
        val shown = slots.filter { it.live }.mapTo(HashSet()) { it.id }
        for (s in segments) {
            if (!s.live || !shown.add(s.id)) continue
            val slot = Slot(s.id, s.status, s.bytes, live = true)
            val free = slots.indexOfFirst { !it.live }
            if (free >= 0) slots[free] = slot else slots += slot
        }
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
                    else -> "STAT_FINISHED"
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
        else -> text("ERR_INTERNAL")
    }
}

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
    }
    return "$WEBSITE_URL/help/errors/$slug/"
}
