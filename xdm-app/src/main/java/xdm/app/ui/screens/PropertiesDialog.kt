package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.DbRecord
import xdm.app.I8N.text
import xdm.app.RecordStatus
import xdm.app.ui.components.MessageBox
import xdm.app.ui.screens.settings.settingsAccentColor
import xdm.app.ui.screens.settings.settingsIconButton
import xdm.app.utils.RemixIcon
import xdm.app.utils.gbAdd
import xdm.app.utils.openFolderExternal
import xdm.app.utils.openWebPage
import xdm.app.utils.setClipBoardText
import xdm.core.downloaders.DownloadType
import xdm.core.network.http.HeaderMap
import xdm.core.util.FormatHelper
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel

/**
 * Read-only summary of one download: where it came from, where it is going and the request it
 * was captured with.
 *
 * Everything on show comes from the stored task info, which differs per [DownloadType] — and is
 * missing entirely for a type the engine does not implement yet. [details] flattens all of that
 * into one shape, and rows with nothing to say are simply left out, so an unsupported or
 * half-recorded download still opens with whatever the list row itself knows.
 */
class PropertiesDialog(private val owner: Window?, ent: DbRecord) : JDialog(owner) {

    /** The parts of a task info the dialog shows, gathered per download type. */
    private data class Details(
        val url: String?,
        val folder: String?,
        val fileName: String?,
        val origin: String?,
        val headers: HeaderMap?,
        val cookie: String?,
    )

    /** Header rows shown in the table, one row per value, with the cookie folded in as one row. */
    private val headerRows = mutableListOf<Pair<String, String>>()

    init {
        title = text("TITLE_PROP")
        isModal = true
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = true

        val details = details(ent)
        headerRows.addAll(headerRows(details))

        val typeLabel = when (ent.downloadType) {
            DownloadType.Http -> "HTTP"
            DownloadType.Hls -> "HLS"
            DownloadType.Dash -> "DASH"
            DownloadType.Torrent -> "Torrent"
        }
        val sizeText = if (ent.size > 0) FormatHelper.formatSize(ent.size.toDouble()) else text("PROP_UNKNOWN")
        val dateText = if (ent.date > 0)
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(ent.date)) else "-"

        val panel = JPanel(GridBagLayout()).apply {
            border = EmptyBorder(12, 18, 8, 18)
        }

        var row = 0
        addRow(panel, row++, text("PROP_NAME"), details.fileName ?: ent.fileName, copyable = true)
        details.folder?.let { folder ->
            addRow(panel, row++, text("PROP_FOLDER"), folder, folderOf = folder to details.fileName)
        }
        details.url?.let { addRow(panel, row++, text("PROP_URL"), it, copyable = true) }
        details.origin?.let { addRow(panel, row++, text("PROP_ORIGIN"), it, webPage = true) }
        // The referer usually repeats the origin; only worth its own row when it differs.
        referer(details)?.takeIf { it != details.origin }
            ?.let { addRow(panel, row++, text("PROP_REFERER"), it, webPage = true) }
        // The four scalars are short enough to sit two to a line, which keeps the whole dialog
        // inside one screenful without shrinking the header table.
        addPairRow(panel, row++, text("PROP_TYPE") to typeLabel, text("PROP_SIZE") to sizeText)
        addPairRow(
            panel, row++,
            text("PROP_DATE") to dateText,
            text("PROP_STATUS") to statusText(ent.status)
        )

        gbAdd(
            JLabel(text("PROP_HEADERS")).apply { font = font.deriveFont(Font.BOLD) }, panel,
            gridX = 0, gridY = row++, colSpan = COLUMNS, weightX = 1.0,
            padding = Insets(8, 6, 3, 6),
            horizontalFill = true
        )
        // The table takes the leftover height, so growing the dialog shows more headers rather
        // than more empty space between the rows above.
        panel.add(headersView(), GridBagConstraints().apply {
            gridx = 0; gridy = row
            gridwidth = COLUMNS
            weightx = 1.0; weighty = 1.0
            fill = GridBagConstraints.BOTH
            insets = Insets(0, 6, 0, 6)
        })

        contentPane.add(panel, BorderLayout.CENTER)
        contentPane.add(buttonBar(), BorderLayout.SOUTH)

        // The table's preferred height is fixed, so the packed height only varies with how many
        // rows the download actually has -- it stays well under the cap, which is here to keep a
        // stray long value from ever pushing the dialog past a screenful.
        pack()
        size = Dimension(
            minOf(maxOf(620, width), 720),
            minOf(maxOf(height, 240), 480)
        )
        setLocationRelativeTo(owner)
    }

    /**
     * Reads the stored task info for [ent]. Returns an all-null [Details] when the type keeps no
     * task info (or the file is gone), which leaves every optional row out of the dialog.
     */
    private fun details(ent: DbRecord): Details {
        val db = AppContext.taskInfoDB
        return when (ent.downloadType) {
            DownloadType.Http -> db.getHttpTask(ent.id)?.let {
                Details(it.url, folderOf(it.userSelectedDownloadFolder, it.defaultDownloadFolder),
                    it.fileName, it.origin, it.headers, it.cookie)
            }

            DownloadType.Hls -> db.getHlsTask(ent.id)?.let {
                Details(it.url, folderOf(it.userSelectedDownloadFolder, it.defaultDownloadFolder),
                    it.fileName, it.origin, it.headers, it.cookie)
            }

            DownloadType.Dash -> db.getDashTask(ent.id)?.let {
                Details(it.url, folderOf(it.userSelectedDownloadFolder, it.defaultDownloadFolder),
                    it.fileName, it.origin, it.headers, it.cookie)
            }

            // No task info is written for these yet; the record's own fields still show.
            DownloadType.Torrent -> null
        } ?: Details(null, null, null, null, null, null)
    }

    private fun folderOf(userSelected: String?, default: String): String? =
        (userSelected ?: default).takeIf { it.isNotBlank() }

    /**
     * Flattens the headers into display rows. Cookies are pulled out of the map and joined with
     * the task's own cookie field into a single `Cookie` row, matching what the engine sends.
     */
    private fun headerRows(details: Details): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        val cookies = mutableListOf<String>()
        details.headers?.forEach { (name, values) ->
            if (name.equals("Cookie", ignoreCase = true)) {
                cookies.addAll(values.filter { it.isNotBlank() })
            } else {
                values.forEach { rows.add(name to it) }
            }
        }
        details.cookie?.takeIf { it.isNotBlank() }?.let { cookies.add(it) }
        if (cookies.isNotEmpty()) rows.add("Cookie" to cookies.joinToString("; "))
        return rows
    }

    /** The `Referer` header, shown as its own row because it says which page the link came from. */
    private fun referer(details: Details): String? = details.headers
        ?.entries?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
        ?.value?.firstOrNull { it.isNotBlank() }

    private fun statusText(status: RecordStatus): String = when (status) {
        RecordStatus.DOWNLOADING -> text("STAT_DOWNLOADING")
        RecordStatus.FINISHED -> text("STAT_FINISHED")
        RecordStatus.PAUSED -> text("STAT_PAUSED")
        RecordStatus.ASSEMBLING -> text("STAT_ASSEMBLING")
        RecordStatus.PUBLISHING -> text("STAT_PUBLISHING")
        RecordStatus.ERROR -> text("MSG_FAILED")
        RecordStatus.READY -> text("MSG_WAIT")
    }

    private fun headersView(): JComponent {
        if (headerRows.isEmpty()) {
            return JPanel(BorderLayout()).apply {
                border = EmptyBorder(4, 0, 4, 0)
                add(JLabel(text("PROP_NO_HEADERS")).apply {
                    foreground = UIManager.getColor("Label.disabledForeground") ?: foreground
                }, BorderLayout.NORTH)
            }
        }
        val table = JTable(HeaderTableModel()).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = ROW_HEIGHT - 1
            autoCreateRowSorter = true
            // Header names are short and values are not, so the value column takes the surplus.
            columnModel.getColumn(0).preferredWidth = 140
            columnModel.getColumn(1).preferredWidth = 420
            // Sizing the viewport in whole rows keeps the table from opening on a half-cut one.
            preferredScrollableViewportSize = Dimension(420, rowHeight * VISIBLE_HEADER_ROWS)
        }
        return JScrollPane(table)
    }

    private fun buttonBar(): JPanel = JPanel().apply {
        background = UIManager.getColor("Table.background")
        border = EmptyBorder(7, 14, 7, 14)
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        val btnClose = JButton(text("LBL_CLOSE")).apply { addActionListener { dispose() } }
        this@PropertiesDialog.rootPane.defaultButton = btnClose
        add(Box.createHorizontalGlue())
        add(btnClose)
    }

    /**
     * One full-width label/value row, its value spanning the pair columns. [copyable] adds a copy
     * button, [webPage] a copy button on a value that opens in the browser when clicked, and
     * [folderOf] (folder to file name) a folder button on a value that reveals the file.
     */
    private fun addRow(
        panel: JPanel,
        row: Int,
        label: String,
        value: String,
        copyable: Boolean = false,
        webPage: Boolean = false,
        folderOf: Pair<String, String?>? = null,
    ) {
        addLabel(panel, row, column = 0, label = label)

        val valueComp: JComponent = when {
            folderOf != null -> {
                val (folder, fileName) = folderOf
                linkedValue(value, text("PROP_OPEN_FOLDER"), RemixIcon.FOLDER_LINE) {
                    revealFolder(folder, fileName)
                }
            }

            webPage -> linkedValue(value, text("PROP_OPEN_PAGE"), RemixIcon.GLOBAL_LINE) {
                openWebPage(value)
            }

            copyable -> JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                add(readOnlyField(value), BorderLayout.CENTER)
                add(copyButton(value), BorderLayout.EAST)
            }

            else -> JLabel(value)
        }

        addValue(panel, row, column = 1, comp = valueComp, colSpan = COLUMNS - 1, weight = 1.0)
    }

    /** Two short label/value pairs sharing one row, each taking half the width. */
    private fun addPairRow(panel: JPanel, row: Int, left: Pair<String, String>, right: Pair<String, String>) {
        addLabel(panel, row, column = 0, label = left.first)
        addValue(panel, row, column = 1, comp = JLabel(left.second), colSpan = 1, weight = 0.5)
        addLabel(panel, row, column = 2, label = right.first)
        addValue(panel, row, column = 3, comp = JLabel(right.second), colSpan = 1, weight = 0.5)
    }

    private fun addLabel(panel: JPanel, row: Int, column: Int, label: String) = gbAdd(
        JLabel("$label:").apply { font = font.deriveFont(Font.BOLD) }, panel,
        gridX = column, gridY = row,
        alignment = GridBagConstraints.WEST,
        // The second pair needs room to read as a separate field rather than a wrapped value.
        padding = Insets(0, if (column == 0) 6 else 18, 0, 12)
    )

    private fun addValue(panel: JPanel, row: Int, column: Int, comp: JComponent, colSpan: Int, weight: Double) {
        // Rows with icon buttons would otherwise stand taller than the plain label rows, which
        // reads as uneven spacing down the column. Pinning every value to one height instead
        // leaves a single gap between all of them.
        comp.preferredSize = Dimension(comp.preferredSize.width, ROW_HEIGHT)
        comp.minimumSize = Dimension(0, ROW_HEIGHT)
        gbAdd(
            comp, panel,
            gridX = column, gridY = row, colSpan = colSpan, weightX = weight,
            padding = Insets(ROW_GAP, 0, ROW_GAP, 6),
            horizontalFill = true
        )
    }

    /** A value whose text and trailing icon button both trigger [action], plus a copy button. */
    private fun linkedValue(value: String, tooltip: String, icon: RemixIcon, action: () -> Unit): JComponent {
        val field = readOnlyField(value).apply {
            foreground = settingsAccentColor()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = tooltip
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = action()
            })
        }
        val buttons = Box.createHorizontalBox().apply {
            add(iconButton(icon, tooltip, action))
            add(copyButton(value))
        }
        return JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(field, BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
        }
    }

    private fun readOnlyField(value: String) = JTextField(value).apply {
        isEditable = false
        border = null
        isOpaque = false
        caretPosition = 0
    }

    private fun copyButton(value: String) =
        iconButton(RemixIcon.FILE_COPY_LINE, text("CTX_COPY")) { setClipBoardText(value) }

    /**
     * Same styling as the settings pages' icon buttons, but sized to [ROW_HEIGHT] so a row with
     * buttons is no taller than one without.
     */
    private fun iconButton(icon: RemixIcon, tooltip: String, action: () -> Unit): JButton =
        settingsIconButton(icon, tooltip, action).apply {
            val size = Dimension(ROW_HEIGHT, ROW_HEIGHT)
            preferredSize = size
            maximumSize = size
            minimumSize = size
        }

    /** Opens the download's folder, selecting the file when it is still there. */
    private fun revealFolder(folder: String, fileName: String?) {
        try {
            val exists = fileName != null && File(folder, fileName).exists()
            openFolderExternal(if (exists) fileName else null, folder)
        } catch (e: Exception) {
            Logger.error(e)
            MessageBox.show(owner, text("ERR_MSG_FILE_NOT_FOUND"), text("ERR_MSG_FILE_NOT_FOUND_MSG"))
        }
    }

    private companion object {
        /** Height every value row is pinned to, buttons included. */
        const val ROW_HEIGHT = 22

        /** Vertical space between two rows, as insets above and below each value. */
        const val ROW_GAP = 3

        /** Grid columns: a label/value pair, twice. Full-width values span the last three. */
        const val COLUMNS = 4

        /** How many header rows the table shows before it has to be scrolled. */
        const val VISIBLE_HEADER_ROWS = 5
    }

    private inner class HeaderTableModel : AbstractTableModel() {
        override fun getRowCount() = headerRows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) =
            if (column == 0) text("REF_HDR_NAME") else text("REF_HDR_VALUE")

        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val (name, value) = headerRows[rowIndex]
            return if (columnIndex == 0) name else value
        }
    }
}
