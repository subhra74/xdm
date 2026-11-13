package xdm.app.utils

import xdm.app.AppContext
import xdm.app.I8N
import xdm.core.util.Logger
import java.awt.BorderLayout
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.Timer
import kotlin.system.exitProcess

/**
 * Exit from the UI (the Exit items, the macOS Quit). With the torrent session running, its resume data and state
 * are saved and trackers are told XDM stops (about 1-3 s); a small "Closing torrents…" window shows when that
 * takes longer than half a second. Without a session XDM exits at once. The shutdown hook still covers the ways
 * out that do not come through here (logoff, a killed process); it finds the session already closed.
 */
object AppExit {
    @Volatile
    private var exiting = false

    fun exit() {
        if (exiting) return
        exiting = true
        val dm = AppContext.downloader
        if (!dm.torrents.started) exitProcess(0)
        // Swing is fine here, unlike in the shutdown hook: the window shows only if closing is not quick.
        val timer = Timer(500) { closingWindow().isVisible = true }.apply {
            isRepeats = false
            start()
        }
        Thread({
            runCatching { dm.shutdown(timeoutMs = 10_000) }.onFailure { Logger.error("Torrent", "Closing on exit", it) }
            timer.stop()
            exitProcess(0)
        }, "torrent-exit-ui").start()
    }

    private fun closingWindow() = JFrame("XDM").apply {
        iconImages = windowIconImages
        defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        isResizable = false
        contentPane = JPanel(BorderLayout(0, 10.px)).apply {
            border = ScaledEmptyBorder(16, 20, 16, 20)
            add(JLabel(I8N.text("TORRENT_CLOSING")), BorderLayout.NORTH)
            add(JProgressBar().apply { isIndeterminate = true })
        }
        pack()
        setSize(maxOf(width, 300.px), height)
        setLocationRelativeTo(null)
    }
}
