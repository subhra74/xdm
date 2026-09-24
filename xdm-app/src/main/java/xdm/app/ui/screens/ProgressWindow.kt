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
    private companion object {
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
        val message = mapErrorMessage(error)
        // Wrap so longer messages (e.g. the TLS hint) fit the fixed-width window instead of being cut off.
        val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        lblStat3.text = "<html><body style='width:300px'>$escaped</body></html>"
        lblStat3.toolTipText = message
        btnPauseResume.text = text("LBL_CLOSE")
        btnHide.isVisible = false
    }

    /**
     * The publish phase: the file is complete and is being copied into the destination folder.
     * Download progress events have stopped by now, so this window is driven separately or it
     * would sit frozen at 100%. Speed and ETA are blank because neither applies to a local copy.
     */
    fun showPublishing(prg: Int) {
        lblStat.text = text("STAT_PUBLISHING")
        title = "[ $prg% ] " + text("STAT_PUBLISHING")
        lblStat1.text = text("STAT_PUBLISHING") + " " + prg + "%"
        lblStat3.text = ""
        lblStat4.text = ""
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

                /** Closing the window must also stop the updates; see [hideWindow]. */
                override fun windowClosing(e: WindowEvent) {
                    hideWindow()
                }

                override fun windowClosed(e: WindowEvent) {
                    System.gc()
                }
            })
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

    private fun mapErrorMessage(error: DownloadError): String {
        return when (error) {
            DownloadError.InvalidResponse -> text("ERR_INVALID_RESP")
            DownloadError.NetworkError -> text("ERR_CONN_FAILED")
            DownloadError.DiskSpaceError -> text("ERR_DISK_FAILED")
            DownloadError.ResumeNotSupported -> text("ERR_NO_RESUME")
            DownloadError.SessionExpired -> text("ERR_SESSION_FAILED")
            DownloadError.TlsError -> text("ERR_TLS")
            DownloadError.DecryptionError -> text("ERR_DECRYPT")
            DownloadError.OutputWriteError -> text("ERR_OUTPUT_WRITE")
            else -> text("ERR_INTERNAL")
        }
    }
}