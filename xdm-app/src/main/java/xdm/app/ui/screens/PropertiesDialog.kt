package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.DbRecord
import xdm.app.I8N.text
import xdm.app.RecordStatus
import xdm.app.utils.createSVGIcon
import xdm.app.utils.setClipBoardText
import xdm.core.downloaders.DownloadType
import xdm.core.util.FormatHelper
import java.awt.*
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder

class PropertiesDialog(owner: Window?, ent: DbRecord) : JDialog(owner) {

    init {
        title = text("TITLE_PROP")
        isModal = true
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = true

        val url = when (ent.downloadType) {
            DownloadType.Http -> AppContext.taskInfoDB.getHttpTask(ent.id)?.url
            DownloadType.Hls -> AppContext.taskInfoDB.getHlsTask(ent.id)?.url
            DownloadType.Dash -> AppContext.taskInfoDB.getDashTask(ent.id)?.url
            else -> null
        } ?: ""

        val typeLabel = when (ent.downloadType) {
            DownloadType.Http -> "HTTP"
            DownloadType.Hls -> "HLS"
            DownloadType.Dash -> "DASH"
            DownloadType.Torrent -> "Torrent"
        }

        val sizeText = if (ent.size > 0) FormatHelper.formatSize(ent.size.toDouble()) else text("PROP_UNKNOWN")
        val dateText = if (ent.date > 0)
            SimpleDateFormat("dd MMM yyyy, hh:mm a").format(Date(ent.date)) else "-"
        val finished = ent.status == RecordStatus.FINISHED

        val panel = JPanel(GridBagLayout()).apply {
            border = EmptyBorder(20, 24, 20, 24)
        }

        var row = 0
        addRow(panel, row++, text("PROP_NAME"), ent.fileName, copyable = true)
        addRow(panel, row++, text("PROP_URL"), url, copyable = true)
        addRow(panel, row++, text("PROP_TYPE"), typeLabel)
        addRow(panel, row++, text("PROP_SIZE"), sizeText)
        addRow(panel, row++, text("PROP_DATE"), dateText)
        addRow(panel, row++, text("PROP_COMPLETED"), if (finished) text("MB_YES") else text("MB_NO"))

        val btnClose = JButton(text("LBL_CLOSE")).apply {
            addActionListener { dispose() }
        }
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            add(btnClose)
        }
        val gc = GridBagConstraints().apply {
            gridx = 0; gridy = row
            gridwidth = 2
            anchor = GridBagConstraints.EAST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(16, 6, 0, 6)
        }
        panel.add(buttonPanel, gc)

        contentPane.add(panel)
        pack()
        size = Dimension(500, 300)
        setLocationRelativeTo(owner)
    }

    private fun addRow(panel: JPanel, row: Int, label: String, value: String, copyable: Boolean = false) {
        val lbl = JLabel("$label:").apply {
            font = font.deriveFont(Font.BOLD)
        }
        val gcLabel = GridBagConstraints().apply {
            gridx = 0; gridy = row
            anchor = GridBagConstraints.NORTHWEST
            insets = Insets(6, 6, 6, 12)
        }
        panel.add(lbl, gcLabel)

        val valueComp: JComponent = if (copyable) {
            val field = JTextField(value).apply {
                isEditable = false
                border = null
                isOpaque = false
            }
            val copyBtn = JButton(createSVGIcon("file-line.svg", 14, Color.GRAY)).apply {
                toolTipText = text("CTX_COPY")
                margin = Insets(2, 4, 2, 4)
                addActionListener { setClipBoardText(value) }
            }
            JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                add(field, BorderLayout.CENTER)
                add(copyBtn, BorderLayout.EAST)
            }
        } else {
            JLabel(value)
        }

        val gcValue = GridBagConstraints().apply {
            gridx = 1; gridy = row
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(6, 0, 6, 6)
        }
        panel.add(valueComp, gcValue)
    }
}
