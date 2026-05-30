package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.components.AppMenuHandler
import xdm.app.ui.components.CircularProgress
import xdm.app.ui.components.SegmentPanel
import xdm.app.utils.gbAdd
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.SegmentProgress
import xdm.core.util.FormatHelper.formatSize
import xdm.core.util.FormatHelper.toLongEta
import java.awt.Color
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Taskbar
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*

class ProgressWindow(val id: Long) : JFrame() {
    fun showError(error: DownloadError) {
        lblStat4.text = ""
        lblStat.text = text("MSG_FAILED")
        lblStat3.text = mapErrorMessage(error)
        btnPauseResume.isEnabled = false
        btnHide.isVisible = false
    }

    fun updateProgress(
        fileName: String?,
        downloaded: Long,
        size: Long,
        speed: Float,
        eta: Long,
        prg: Int,
        segData: Collection<SegmentProgress>
    ) {
        lblStat.text = text("DWN_TITLE")
        lblFileName.text = fileName
        title = "[ $prg% ] $fileName"
        textBuf.clear()
        if (prg > 0) {
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
        textBuf.append(" / ")
        if (size > 0) {
            textBuf.append(formatSize(size.toDouble()))
        } else {
            textBuf.append("---")
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
        segPanel.setValues(segData)
        if (Taskbar.isTaskbarSupported()) {
            val taskbar: Taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW)) {
                taskbar.setWindowProgressValue(this, prg)
            }
        }
    }

    private val textBuf = StringBuilder(100)
    private val btnHide = JButton(text("DWN_HIDE")).apply { addActionListener { dispose() } }
    private val btnPauseResume = JButton(text("MENU_PAUSE")).apply { addActionListener { pauseDownload() } }
    val prg = CircularProgress().apply {
        value = 0
        preferredSize = Dimension(80, 80)
        minimumSize = Dimension(80, 80)
    }
    val segPanel = SegmentPanel().apply {
        preferredSize = Dimension(80, 5)
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

    init {
        title = text("DWN_TITLE")
        size = Dimension(400, 260)
        setLocationRelativeTo(null)

        val b1 = Box.createHorizontalBox().apply {
            border = BorderFactory.createEmptyBorder(10, 15, 10, 15)
            add(Box.createHorizontalGlue())
            add(btnHide)
            add(Box.createRigidArea(Dimension(10, 10)))
            add(btnPauseResume)
            isOpaque = true
            background = UIManager.getColor("Table.background")
        }

        val gridBagLayout = GridBagLayout().apply {
            columnWidths = intArrayOf(0, 0, 0, 0, 0, 0, 0)
            rowHeights = intArrayOf(0, 0, 0, 0, 0, 0, 0)
            columnWeights = doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            rowWeights = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        }
        contentPane.layout = gridBagLayout

        gbAdd(
            prg, contentPane, padding = Insets(20, 15, 0, 0),
            rowSpan = 5, alignment = GridBagConstraints.NORTHWEST
        )
        gbAdd(
            lblFileName,
            contentPane,
            gridX = 1,
            padding = Insets(20, 10, 0, 0),
            alignment = GridBagConstraints.SOUTHWEST,
            colSpan = 2,
        )
        gbAdd(
            lblStat,
            contentPane,
            gridX = 1,
            gridY = 1,
            padding = Insets(5, 10, 15, 15),
            alignment = GridBagConstraints.NORTHWEST,
            colSpan = 2,
        )
        gbAdd(
            lblStat1,
            contentPane,
            gridX = 1,
            gridY = 2,
            padding = Insets(15, 10, 5, 5),
            alignment = GridBagConstraints.WEST,
        )
        gbAdd(
            lblStat2,
            contentPane,
            gridX = 2,
            gridY = 2,
            padding = Insets(15, 10, 5, 15),
            alignment = GridBagConstraints.EAST,
        )
        gbAdd(
            segPanel,
            contentPane,
            gridX = 1,
            gridY = 3,
            padding = Insets(5, 10, 5, 15),
            alignment = GridBagConstraints.WEST,
            colSpan = 2,
            horizontalFill = true,
        )
        gbAdd(
            lblStat3,
            contentPane,
            gridX = 1,
            gridY = 4,
            padding = Insets(5, 10, 5, 5),
            alignment = GridBagConstraints.WEST,
        )
        gbAdd(
            lblStat4,
            contentPane,
            gridX = 2,
            gridY = 4,
            padding = Insets(5, 10, 5, 15),
            alignment = GridBagConstraints.EAST,
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
        rootPane.defaultButton = btnPauseResume
        addWindowListener(
            object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent) {
                    btnPauseResume.requestFocusInWindow()
                }

                override fun windowClosed(e: WindowEvent) {
                    System.gc()
                }
            })
    }

    private fun pauseDownload() {
        synchronized(AppContext.db) {
            AppContext.db.getById(id)?.let { e ->
                AppMenuHandler.pauseDownload(e)
            }
        }
    }

    private fun mapErrorMessage(error: DownloadError): String {
        return when (error) {
            DownloadError.InvalidResponse -> text("ERR_INVALID_RESP")
            DownloadError.NetworkError -> text("ERR_CONN_FAILED")
            DownloadError.DiskSpaceError -> text("ERR_DISK_FAILED")
            DownloadError.ResumeNotSupported -> text("ERR_NO_RESUME")
            DownloadError.SessionExpired -> text("ERR_SESSION_FAILED")
            else -> text("ERR_INTERNAL")
        }
    }
}