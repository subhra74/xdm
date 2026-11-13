package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.torrent.PeerText
import xdm.app.torrent.PieceBar
import xdm.app.torrent.TorrentFiles
import xdm.app.torrent.TorrentRowText
import xdm.app.torrent.TorrentSelection
import xdm.app.ui.components.AppMenuHandler
import xdm.app.ui.components.AppTheme
import xdm.app.ui.components.CircularProgress
import xdm.app.ui.components.canPause
import xdm.app.ui.components.canResume
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.padColumns
import xdm.app.utils.createIcon
import xdm.app.utils.px
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import xdm.app.utils.windowIconImages
import xdm.core.downloaders.TorrentDownloadTaskInfo
import xdm.core.util.FormatHelper.formatDateShort
import xdm.core.util.FormatHelper.formatSize
import xdm.core.util.FormatHelper.toLongEta
import xdm.core.util.Logger
import xdm.manager.DbRecord
import xdm.manager.RecordStatus
import xdm.manager.torrent.FilePriority
import xdm.manager.torrent.PieceMap
import xdm.manager.torrent.TorrentDetails
import xdm.manager.torrent.TorrentInfo
import xdm.manager.torrent.TorrentPeer
import xdm.manager.torrent.TorrentTracker
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/*
 * The two torrent windows. Both show the same tabs (TorrentTabs) and read the engine through a TorrentPoller:
 *
 * - TorrentProgressWindow: the progress window while a torrent downloads or seeds, laid out and sized like the
 *   HTTP one (400 × 260), with More on the button row adding 150 px for the tabs;
 * - TorrentPropertiesWindow: Properties, the size of the Properties dialog, the tabs always shown.
 */

/**
 * Reads a torrent's live details (and, until it has them, its saved metadata) off the EDT about once a second,
 * and hands them to [onData] on the EDT. Live details exist only while the torrent is in the session.
 */
class TorrentPoller(private val id: Long, private val onData: (TorrentDetails?, TorrentInfo?) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "torrent-window").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private val timer = Timer(1000) { refresh() }

    /** Whether the torrent's files are on disk; checked every few polls (it reads the file system). */
    @Volatile
    var filesPresent: Boolean = true
        private set
    private var polls = 0

    /** Whether the saved metadata is still wanted (the file list is not known yet). */
    @Volatile
    var wantSaved = true

    fun start() {
        refresh()
        timer.start()
    }

    fun stop() {
        timer.stop()
        worker.shutdownNow()
    }

    /** Runs [task] on the poller's thread (engine calls stay off the EDT). */
    fun run(task: () -> Unit) {
        runCatching { worker.execute(task) }
    }

    fun refresh() {
        if (!busy.compareAndSet(false, true)) return
        runCatching {
            worker.execute {
                val details = runCatching { AppContext.downloader.torrentDetails(id) }.getOrNull()
                if (polls++ % 5 == 0) filesPresent = runCatching { AppContext.downloader.torrentFilesPresent(id) }.getOrDefault(true)
                val saved = if (wantSaved) runCatching { AppContext.downloader.torrentFiles(id) }.getOrNull() else null
                SwingUtilities.invokeLater {
                    busy.set(false)
                    onData(details, saved)
                }
            }
        }.onFailure { busy.set(false) }
    }
}

/**
 * General, Files, Peers, Trackers. [compact] (the progress window's 150 px) keeps only the General rows the
 * window's own lines do not show already.
 */
class TorrentTabs(private val id: Long, private val compact: Boolean, private val poller: TorrentPoller) : JTabbedPane() {
    private val task: TorrentDownloadTaskInfo? = AppContext.taskInfoDB.getTorrentTask(id)
    private val general = GeneralPanel()
    private val filesModel = FilesModel()
    private val peersModel = PeersModel()
    private val trackersModel = TrackersModel()
    private var files: TorrentFiles? = null

    init {
        putClientProperty("JTabbedPane.tabHeight", if (compact) 26.px else null)
        addTab(text("TW_GENERAL"), JScrollPane(general).apply { border = null })
        addTab(text("TW_FILES"), JScrollPane(filesTable()))
        addTab(text("TW_PEERS"), JScrollPane(table(peersModel, intArrayOf(150, 120, 50, 60, 80, 80, 80, 80, 110))))
        addTab(text("TW_TRACKERS"), JPanel(BorderLayout(0, 6.px)).apply {
            isOpaque = false
            add(JScrollPane(table(trackersModel, intArrayOf(300, 100, 60, 60, 90, 200))))
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(JButton(text("TW_ADD_TRACKERS")).apply { addActionListener { addTrackers() } })
                add(Box.createHorizontalGlue())
            }, BorderLayout.SOUTH)
        })
    }

    fun show(rec: DbRecord, d: TorrentDetails?, saved: TorrentInfo?) {
        if (files == null) (d?.info ?: saved)?.let {
            files = TorrentFiles(it, task?.filePriorities ?: ByteArray(0))
            poller.wantSaved = false
        }
        files?.let { f ->
            when {
                d?.info != null -> f.updateProgress(d.info)
                rec.status == RecordStatus.FINISHED -> f.markComplete()
                else -> f.updateProgress(null)
            }
        }
        filesModel.fireTableDataChanged()
        peersModel.peers = d?.peers.orEmpty().sortedByDescending { it.downloadRate + it.uploadRate }
        trackersModel.update(d?.trackers.orEmpty(), d?.peers.orEmpty(), d != null)
        general.show(rec, d, files?.info)
    }

    /** Asks for trackers (one per line, blank line = next tier) and adds them while the torrent runs. */
    private fun addTrackers() {
        val area = javax.swing.JTextArea(6, 40)
        val content = JPanel(BorderLayout(0, 6.px)).apply {
            add(JLabel(text("TW_ADD_TRACKERS_HINT")), BorderLayout.NORTH)
            add(JScrollPane(area))
        }
        val owner = SwingUtilities.getWindowAncestor(this)
        val ok = javax.swing.JOptionPane.showConfirmDialog(owner, content, text("TW_ADD_TRACKERS"),
            javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE)
        if (ok != javax.swing.JOptionPane.OK_OPTION || area.text.isBlank()) return
        val textIn = area.text
        poller.run {
            val added = runCatching { AppContext.downloader.addTorrentTrackers(id, textIn) }.getOrDefault(false)
            if (!added) xdm.app.ui.components.MessageBox.show(owner, text("TW_ADD_TRACKERS"), text("TW_ADD_TRACKERS_FAILED"))
            else poller.refresh()
        }
    }

    /** [renderers] sets the table's own cell renderers, before every column is padded. */
    private fun table(model: AbstractTableModel, widths: IntArray, renderers: JTable.() -> Unit = {}) = JTable(model).apply {
        widths.forEachIndexed { i, w -> columnModel.getColumn(i).preferredWidth = w.px }
        rowHeight = 22.px
        fillsViewportHeight = true
        tableHeader.reorderingAllowed = false
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        renderers()
        padColumns()
    }

    private fun filesTable() = table(filesModel, intArrayOf(360, 90, 70, 90)) {
        columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            private val folderIcon = createIcon(RemixIcon.FOLDER_FILL, 16, Color(0xE8, 0xB3, 0x3C))
            private val fileIcon = createIcon(RemixIcon.FILE_LINE, 16, Color.GRAY)
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int,
            ): Component {
                val r = value as TorrentSelection.Row
                super.getTableCellRendererComponent(t, r.name, sel, focus, row, col)
                icon = if (r.isFolder) folderIcon else fileIcon
                border = ScaledEmptyBorder(0, 4 + 16 * r.depth, 0, 4)
                return this
            }
        }
        columnModel.getColumn(3).cellEditor = DefaultCellEditor(JComboBox(TorrentFiles.LEVELS.map { priorityName(it) }.toTypedArray()))
    }

    private fun priorityName(p: Byte) = text(
        when (p) {
            FilePriority.SKIP -> "TW_PRIO_SKIP"
            FilePriority.LOW -> "TW_PRIO_LOW"
            FilePriority.HIGH -> "TW_PRIO_HIGH"
            else -> "TW_PRIO_NORMAL"
        }
    )

    private fun setPriority(row: TorrentSelection.Row, name: String) {
        val f = files ?: return
        val p = TorrentFiles.LEVELS.firstOrNull { priorityName(it) == name } ?: return
        if (f.priority(row) == p) return
        f.setPriority(row, p)
        filesModel.fireTableDataChanged()
        val all = f.priorities()
        poller.run {
            runCatching { AppContext.downloader.setTorrentFilePriorities(id, all) }
                .onFailure { Logger.error("Torrent", "Priorities of $id", it) }
        }
    }

    /** As wide as the tab, so a long value (the save path) is cut short with its full text in the tooltip. */
    private inner class GeneralPanel : JPanel(GridBagLayout()), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16.px
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = 64.px
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false

        private val values = LinkedHashMap<String, JLabel>()

        init {
            border = if (compact) ScaledEmptyBorder(4, 6, 4, 6) else ScaledEmptyBorder(10, 6, 10, 6)
            val keys = if (compact) listOf("TW_UPLOADED", "TW_AVAILABILITY", "TW_PIECES", "TW_SAVE_PATH", "TW_HASH")
            else listOf(
                "TW_DOWNLOADED", "TW_SPEED", "TW_TIME_LEFT", "TW_UPLOADED", "TW_PEERS_ROW", "TW_AVAILABILITY",
                "TW_PIECES", "TW_SAVE_PATH", "TW_HASH", "TW_ADDED", "TW_COMPLETED", "TW_COMMENT", "TW_CREATED_BY",
            )
            val gap = if (compact) 1 else 3
            keys.forEachIndexed { row, key ->
                add(JLabel(text(key)).apply { foreground = UIManager.getColor("Label.disabledForeground") },
                    GridBagConstraints().apply {
                        gridx = 0; gridy = row; anchor = GridBagConstraints.NORTHWEST; insets = scaledInsets(gap, 4, gap, 14)
                    })
                val v = JLabel(" ")
                v.minimumSize = Dimension(0, v.preferredSize.height)
                values[key] = v
                add(v, GridBagConstraints().apply {
                    gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.NORTHWEST; insets = scaledInsets(gap, 0, gap, 4)
                })
            }
            add(Box.createVerticalGlue(), GridBagConstraints().apply { gridy = keys.size; weighty = 1.0 })
        }

        /** Rows this panel does not have (compact) are skipped. */
        private fun set(key: String, value: String?) {
            values[key]?.apply {
                text = value?.takeIf { it.isNotBlank() } ?: "—"
                toolTipText = value?.takeIf { it.isNotBlank() }
            }
        }

        fun show(rec: DbRecord, d: TorrentDetails?, info: TorrentInfo?) {
            val s = d?.status
            val wanted = s?.totalWanted ?: rec.size
            val done = s?.totalWantedDone ?: if (rec.status == RecordStatus.FINISHED) rec.size else rec.downloaded
            set("TW_DOWNLOADED", if (wanted > 0) "${size(done)} / ${size(wanted)}" else size(done))
            set("TW_SPEED", if (s != null) "↓ ${size(s.downloadRate.toLong())}/s   ↑ ${size(s.uploadRate.toLong())}/s" else null)
            set("TW_TIME_LEFT", if (s != null && rec.status == RecordStatus.DOWNLOADING && rec.eta > 0) toLongEta(rec.eta) else null)
            set("TW_UPLOADED", s?.let {
                "${size(it.allTimeUpload)}   (" + String.format(Locale.ROOT, text("TORRENT_RATIO"), ratio(it.allTimeUpload, it.allTimeDownload)) + ")"
            })
            set("TW_PEERS_ROW", s?.let {
                val swarm = if (it.incomplete >= 0 || it.complete >= 0)
                    "   ·   " + String.format(text("TW_SWARM"), maxOf(it.complete, 0), maxOf(it.incomplete, 0)) else ""
                String.format(text("TW_CONNECTED"), it.peers, it.seeds) + swarm
            })
            set("TW_AVAILABILITY", s?.availabilityMilli?.takeIf { it >= 0 }?.let { String.format(Locale.ROOT, "%.2f", it / 1000f) })
            set("TW_PIECES", info?.let {
                val have = s?.piecesDone ?: if (rec.status == RecordStatus.FINISHED) it.numPieces else null
                (if (have != null) "$have / " else "") + "${it.numPieces} × ${size(it.pieceLength.toLong())}"
            })
            set("TW_SAVE_PATH", task?.folder)
            set("TW_HASH", task?.key)
            set("TW_ADDED", formatDateShort(rec.date))
            set("TW_COMPLETED", s?.completedTime?.takeIf { it > 0 }?.let { formatDateShort(it * 1000) })
            set("TW_COMMENT", info?.comment)
            set("TW_CREATED_BY", info?.creator)
        }
    }

    private inner class FilesModel : AbstractTableModel() {
        private val columns = arrayOf(text("TORRENT_COL_NAME"), text("TORRENT_COL_SIZE"), text("TW_PROGRESS"), text("TW_PRIORITY"))
        private val rows get() = files?.rows.orEmpty()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(c: Int) = columns[c]
        override fun getColumnClass(c: Int) = if (c == 0) TorrentSelection.Row::class.java else String::class.java
        override fun isCellEditable(r: Int, c: Int) = c == 3
        override fun getValueAt(r: Int, c: Int): Any {
            val row = rows[r]
            val f = files!!
            return when (c) {
                0 -> row
                1 -> size(row.size)
                2 -> f.progress(row)?.let { "$it%" } ?: "—"
                else -> f.priority(row)?.let { priorityName(it) } ?: text("TW_PRIO_MIXED")
            }
        }

        override fun setValueAt(value: Any?, r: Int, c: Int) {
            if (c == 3 && value is String) setPriority(rows[r], value)
        }
    }

    private inner class PeersModel : AbstractTableModel() {
        private val columns = arrayOf(
            text("TW_ADDRESS"), text("TW_CLIENT"), text("TW_FLAGS"), text("TW_PROGRESS"), "↓", "↑",
            text("TW_DOWNLOADED"), text("TW_UPLOADED"), text("TW_SOURCE"),
        )
        var peers: List<TorrentPeer> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount() = peers.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(c: Int) = columns[c]
        override fun getValueAt(r: Int, c: Int): Any {
            val p = peers[r]
            return when (c) {
                0 -> if (':' in p.ip) "[${p.ip}]:${p.port}" else "${p.ip}:${p.port}"
                1 -> p.client
                2 -> PeerText.flags(p)
                3 -> "${p.progressPpm / 10_000}%"
                4 -> "${size(p.downloadRate.toLong())}/s"
                5 -> "${size(p.uploadRate.toLong())}/s"
                6 -> size(p.totalDownload)
                7 -> size(p.totalUpload)
                else -> PeerText.sources(p)
            }
        }
    }

    private inner class TrackersModel : AbstractTableModel() {
        private val columns = arrayOf(text("TW_URL"), text("TW_STATUS"), text("TW_SEEDS"), text("TW_PEERS"), text("TW_NEXT"), text("TW_MESSAGE"))
        private var rows: List<Array<String>> = emptyList()

        fun update(trackers: List<TorrentTracker>, peers: List<TorrentPeer>, running: Boolean) {
            val now = System.currentTimeMillis() / 1000
            val list = ArrayList<Array<String>>()
            if (running) {
                // Discovery other than trackers: how many connected peers each one found.
                for ((name, src) in listOf("DHT" to TorrentPeer.SRC_DHT, "PeX" to TorrentPeer.SRC_PEX, "LSD" to TorrentPeer.SRC_LSD)) {
                    list += arrayOf("** $name **", text("TW_TRK_WORKING"), "", PeerText.countBySource(peers, src).toString(), "", "")
                }
            }
            for (t in trackers) {
                list += arrayOf(
                    t.url, trackerStatus(t.status),
                    t.seeds.takeIf { it >= 0 }?.toString() ?: "", t.peers.takeIf { it >= 0 }?.toString() ?: "",
                    if (t.nextAnnounce > now) toLongEta(t.nextAnnounce - now) else "", t.message,
                )
            }
            rows = list
            fireTableDataChanged()
        }

        private fun trackerStatus(s: Int) = text(
            when (s) {
                TorrentTracker.WORKING -> "TW_TRK_WORKING"
                TorrentTracker.UPDATING -> "TW_TRK_UPDATING"
                TorrentTracker.ERROR -> "TW_TRK_ERROR"
                else -> "TW_TRK_NOT_CONTACTED"
            }
        )

        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(c: Int) = columns[c]
        override fun getValueAt(r: Int, c: Int): Any = rows[r][c]
    }
}

/** libtorrent's piece bitfield squeezed into the bar's width; full once finished, empty when unknown. */
class PieceBarView(barHeight: Int) : JComponent() {
    var pieces: PieceMap? = null
        set(value) {
            field = value
            repaint()
        }
    var complete = false
        set(value) {
            field = value
            repaint()
        }

    init {
        preferredSize = Dimension(80.px, barHeight.px)
        minimumSize = Dimension(10, barHeight.px)
        maximumSize = Dimension(Int.MAX_VALUE, barHeight.px)
    }

    override fun paintComponent(g: Graphics) {
        val bg = UIManager.getColor("ProgressBar.background") ?: Color.DARK_GRAY
        val fg = AppTheme.accent
        val g2 = g.create() as java.awt.Graphics2D
        try {
            // Rounded ends like the progress bars: everything is drawn inside a pill-shaped clip.
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            val shape = java.awt.geom.RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), height.toFloat(), height.toFloat())
            g2.color = bg
            g2.fill(shape)
            g2.clip(shape)
            val p = pieces
            if (p == null) {
                if (complete) {
                    g2.color = fg
                    g2.fill(shape)
                }
                return
            }
            val cols = PieceBar.columns(p, width)
            for (x in cols.indices) {
                if (cols[x] <= 0f) continue
                g2.color = blend(bg, fg, cols[x])
                g2.drawLine(x, 0, x, height - 1)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun blend(a: Color, b: Color, t: Float) = Color(
        (a.red + (b.red - a.red) * t).toInt(), (a.green + (b.green - a.green) * t).toInt(),
        (a.blue + (b.blue - a.blue) * t).toInt(),
    )
}

/** The status line of a torrent: Downloading 30%, Getting metadata, Checking 40%, Seeding, Paused, Failed... */
fun torrentStatusText(rec: DbRecord): String = when {
    TorrentRowText.isSeeding(rec) -> text("TORRENT_SEEDING")
    rec.status == RecordStatus.FINISHED -> text("TW_FINISHED")
    rec.status == RecordStatus.ERROR -> text("STAT_FAILED")
    rec.status == RecordStatus.PAUSED -> String.format("%s %d%%", text("STAT_PAUSED"), rec.progress)
    rec.status == RecordStatus.READY -> text("MSG_WAIT")
    else -> TorrentRowText.progressText(rec) ?: String.format("%s %d%%", text("STAT_DOWNLOADING"), rec.progress)
}

private fun size(bytes: Long) = formatSize(bytes.coerceAtLeast(0).toDouble())

private fun ratio(up: Long, down: Long) = if (down > 0) up.toFloat() / down else 0f

/**
 * Properties of a torrent: name, status and piece map over the tabs; Pause/Resume and Start/Stop seeding.
 * The size of the Properties dialog. An independent frame, one per torrent.
 */
class TorrentPropertiesWindow(near: Window?, private val id: Long) : JFrame() {
    private val lblName = JLabel(" ").apply { font = font.deriveFont(Font.BOLD, font.size2D + 2f) }
    private val lblStatus = JLabel(" ")
    private val pieceBar = PieceBarView(10)
    private val poller = TorrentPoller(id) { d, saved -> show(d, saved) }
    private val tabs = TorrentTabs(id, compact = false, poller)
    private val btnPause = JButton(text("MENU_PAUSE"))
    private val btnResume = JButton(text("MENU_RESUME"))
    private val btnSeed = JButton(text("TORRENT_START_SEEDING"))

    init {
        title = AppContext.taskInfoDB.getTorrentTask(id)?.name ?: ""
        iconImages = windowIconImages
        defaultCloseOperation = DISPOSE_ON_CLOSE
        layout = BorderLayout()
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = ScaledEmptyBorder(12, 14, 8, 14)
            listOf<JComponent>(lblName, lblStatus).forEach { it.alignmentX = Component.LEFT_ALIGNMENT; add(it) }
            add(Box.createRigidArea(scaledSize(0, 8)))
            pieceBar.alignmentX = Component.LEFT_ALIGNMENT
            add(pieceBar)
        }, BorderLayout.NORTH)
        add(tabs.apply { border = ScaledEmptyBorder(0, 10, 0, 10) })
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = ScaledEmptyBorder(8, 14, 12, 14)
            add(btnSeed)
            add(Box.createHorizontalGlue())
            add(btnPause)
            add(btnResume)
            add(Box.createRigidArea(scaledSize(10, 0)))
            add(JButton(text("TW_CLOSE")).apply { addActionListener { dispose() } })
        }, BorderLayout.SOUTH)
        btnPause.addActionListener { record()?.let { AppMenuHandler.pauseDownload(it) } }
        btnResume.addActionListener { record()?.let { AppMenuHandler.resumeDownload(it) } }
        btnSeed.addActionListener {
            val rec = record() ?: return@addActionListener
            if (rec.seeding) AppContext.downloader.stopSeeding(id) else AppMenuHandler.startSeeding(rec, this)
        }
        size = scaledSize(750, 500)
        setLocationRelativeTo(near)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) = poller.stop()
        })
        poller.start()
    }

    private fun record(): DbRecord? = AppContext.db.getById(id)

    private fun show(d: TorrentDetails?, saved: TorrentInfo?) {
        if (!isDisplayable) return
        val rec = record() ?: return dispose()
        lblName.text = rec.fileName
        title = rec.fileName
        lblStatus.text = torrentStatusText(rec)
        pieceBar.pieces = d?.pieces
        pieceBar.complete = rec.status == RecordStatus.FINISHED
        tabs.show(rec, d, saved)
        btnPause.isVisible = rec.canPause
        btnResume.isVisible = rec.canResume
        // Start seeding only with the files on disk; a missing download says so instead.
        btnSeed.isVisible = rec.status == RecordStatus.FINISHED &&
            (rec.seeding || (AppContext.downloader.torrents.available && poller.filesPresent))
        btnSeed.text = text(if (rec.seeding) "TORRENT_STOP_SEEDING" else "TORRENT_START_SEEDING")
        if (rec.status == RecordStatus.FINISHED && !rec.seeding && !poller.filesPresent) {
            lblStatus.text = text("TW_FINISHED") + " · " + text("TORRENT_FILES_MISSING")
        }
    }
}

/**
 * The progress window of a downloading or seeding torrent, laid out and sized like the HTTP one (400 × 260):
 *
 * ```
 * ( 30% )  ubuntu-24.04-desktop-amd64.iso
 *          Downloading 30%
 *          1.2 GB / 4.0 GB          ↓ 3.1 MB/s  ↑ 210 KB/s
 *          ▮▮▮▯▮▮▯▯▯▯▮▯▯   (piece map)
 *          12 peers (3 seeds)                     5m left
 * [More]                                    [Hide] [Pause]
 * ```
 *
 * More adds 150 px with the tabs (compact General); shown by default, and remembered
 * ([xdm.app.IAppConfig.torrentProgressMore]).
 * Seeding: Stop seeding in place of Pause, the ratio in place of the time left. Failed: Close.
 */
class TorrentProgressWindow(private val id: Long) : JFrame() {
    private val prg = CircularProgress().apply {
        value = 0
        preferredSize = scaledSize(80, 80)
        minimumSize = scaledSize(80, 80)
    }
    private val lblFileName = JLabel("---")
    private val lblStat = JLabel(" ").apply {
        font = font.deriveFont(font.size.toFloat() - 1)
        foreground = Color.GRAY
    }
    private val lblSize = JLabel("---")
    private val lblSpeed = JLabel("---")
    private val pieceBar = PieceBarView(5)
    private val lblPeers = JLabel("---")
    private val lblRight = JLabel("---")
    private val poller = TorrentPoller(id) { d, saved -> show(d, saved) }
    private val tabs = TorrentTabs(id, compact = true, poller)
    private val tabsHolder = JPanel(BorderLayout()).apply {
        border = ScaledEmptyBorder(8, 10, 0, 10)
        add(tabs)
    }
    private val btnMore = JButton()
    private val btnHide = JButton(text("DWN_HIDE")).apply { addActionListener { dispose() } }
    private val btnAction = JButton(text("MENU_PAUSE")).apply { addActionListener { action() } }

    init {
        title = text("DWN_TITLE")
        iconImages = windowIconImages
        defaultCloseOperation = DISPOSE_ON_CLOSE
        val top = JPanel(GridBagLayout())
        fun at(c: Component, x: Int, y: Int, insets: java.awt.Insets, anchor: Int, span: Int = 1, rows: Int = 1, fill: Boolean = false) =
            top.add(c, GridBagConstraints().apply {
                gridx = x; gridy = y; gridwidth = span; gridheight = rows; this.insets = insets; this.anchor = anchor
                if (fill) { this.fill = GridBagConstraints.HORIZONTAL }
                weightx = if (x == 1) 1.0 else 0.0
            })
        at(prg, 0, 0, scaledInsets(20, 15, 0, 0), GridBagConstraints.NORTHWEST, rows = 5)
        at(lblFileName, 1, 0, scaledInsets(20, 10, 0, 15), GridBagConstraints.SOUTHWEST, span = 2, fill = true)
        at(lblStat, 1, 1, scaledInsets(5, 10, 7, 15), GridBagConstraints.NORTHWEST, span = 2)
        at(lblSize, 1, 2, scaledInsets(9, 10, 5, 5), GridBagConstraints.WEST)
        at(lblSpeed, 2, 2, scaledInsets(9, 10, 5, 15), GridBagConstraints.EAST)
        at(pieceBar, 1, 3, scaledInsets(5, 10, 5, 15), GridBagConstraints.WEST, span = 2, fill = true)
        at(lblPeers, 1, 4, scaledInsets(5, 10, 5, 5), GridBagConstraints.WEST)
        at(lblRight, 2, 4, scaledInsets(5, 10, 5, 15), GridBagConstraints.EAST)
        // A long name must not widen the grid: its preferred width would exceed the window, and GridBag then
        // drops every cell to its minimum (the name vanished, the peers line was cut). Small preferred width,
        // filled to the row: a long name is cut with "...", the full one in the tooltip.
        lblFileName.preferredSize = Dimension(10, lblFileName.preferredSize.height)
        lblFileName.minimumSize = Dimension(0, lblFileName.preferredSize.height)

        val buttons = Box.createHorizontalBox().apply {
            border = ScaledEmptyBorder(10, 15, 10, 15)
            add(btnMore)
            add(Box.createHorizontalGlue())
            add(btnHide)
            add(Box.createRigidArea(scaledSize(10, 10)))
            add(btnAction)
            // No band of its own: the buttons and the table sit on the window's background.
        }
        layout = BorderLayout()
        add(top, BorderLayout.NORTH)
        add(tabsHolder)
        add(buttons, BorderLayout.SOUTH)
        btnMore.addActionListener { setMore(!tabsHolder.isVisible, remember = true) }
        rootPane.defaultButton = btnAction

        size = scaledSize(400, BASE_HEIGHT)
        setMore(AppContext.config.torrentProgressMore, remember = false)
        setLocationRelativeTo(null)
        addWindowListener(object : WindowAdapter() {
            // The default button (Pause / Stop seeding) has the focus, not More, the first in the row.
            override fun windowOpened(e: WindowEvent) {
                btnAction.requestFocusInWindow()
            }

            override fun windowClosed(e: WindowEvent) = poller.stop()
        })
        poller.start()
    }

    /** Shows or hides the tabs, growing or shrinking the window by their 150 px. */
    private fun setMore(on: Boolean, remember: Boolean) {
        tabsHolder.isVisible = on
        btnMore.text = text(if (on) "TW_LESS" else "TW_MORE")
        setSize(width, (if (on) BASE_HEIGHT + MORE_HEIGHT else BASE_HEIGHT).px)
        validate()
        if (remember) AppContext.config.torrentProgressMore = on
    }

    private fun record(): DbRecord? = AppContext.db.getById(id)

    private fun action() {
        val rec = record() ?: return dispose()
        when {
            rec.status == RecordStatus.ERROR || (rec.status == RecordStatus.FINISHED && !rec.seeding) -> dispose()
            rec.seeding -> AppContext.downloader.stopSeeding(id)
            rec.canPause -> AppMenuHandler.pauseDownload(rec)
            rec.canResume -> AppMenuHandler.resumeDownload(rec)
        }
    }

    private fun show(d: TorrentDetails?, saved: TorrentInfo?) {
        if (!isDisplayable) return
        val rec = record() ?: return dispose()
        val s = d?.status
        val seeding = TorrentRowText.isSeeding(rec)
        val pct = if (rec.status == RecordStatus.FINISHED) 100 else rec.progress
        title = "[ $pct% ] ${rec.fileName}"
        lblFileName.text = rec.fileName
        lblFileName.toolTipText = rec.fileName
        lblStat.text = torrentStatusText(rec)
        prg.value = pct

        val wanted = s?.totalWanted ?: rec.torrent?.wanted?.takeIf { it > 0 } ?: rec.size
        val done = s?.totalWantedDone ?: if (rec.status == RecordStatus.FINISHED) rec.size else rec.downloaded
        lblSize.text = if (wanted > 0) "${size(done)} / ${size(wanted)}" else size(done)
        val down = s?.downloadRate?.toLong() ?: rec.speed.toLong()
        val up = s?.uploadRate?.toLong() ?: rec.torrent?.uploadSpeed ?: 0
        lblSpeed.text = if (s != null || rec.torrent != null) "↓ ${size(down)}/s   ↑ ${size(up)}/s" else "---"
        pieceBar.pieces = d?.pieces
        pieceBar.complete = rec.status == RecordStatus.FINISHED
        val peers = s?.peers ?: rec.torrent?.peers
        val seeds = s?.seeds ?: rec.torrent?.seeds
        lblPeers.text = if (peers != null) String.format(text("TW_PEERS_SEEDS"), peers, seeds ?: 0) else "---"
        lblRight.text = when {
            seeding -> String.format(Locale.ROOT, text("TORRENT_RATIO"),
                s?.let { ratio(it.allTimeUpload, it.allTimeDownload) } ?: rec.torrent?.ratio ?: 0f)
            rec.status == RecordStatus.DOWNLOADING && rec.eta > 0 -> toLongEta(rec.eta) + " left"
            else -> "---"
        }
        tabs.show(rec, d, saved)

        btnAction.text = text(
            when {
                rec.status == RecordStatus.ERROR || (rec.status == RecordStatus.FINISHED && !seeding) -> "LBL_CLOSE"
                seeding -> "TORRENT_STOP_SEEDING"
                rec.canPause -> "MENU_PAUSE"
                else -> "MENU_RESUME"
            }
        )
        btnHide.isVisible = btnAction.text != text("LBL_CLOSE")
    }

    private companion object {
        const val BASE_HEIGHT = 260
        const val MORE_HEIGHT = 150
    }
}
