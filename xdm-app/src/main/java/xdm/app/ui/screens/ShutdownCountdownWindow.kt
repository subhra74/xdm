package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.mainWindowOnScreen
import xdm.app.utils.px
import xdm.app.utils.windowIconImages
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager

/**
 * Warns before XDM shuts the computer down after the queue finishes, counting down [seconds].
 * Modeless and ownerless (an independent prompt, docs/windows-and-modality.md), so the rest of XDM
 * stays usable meanwhile. Cancel, Esc or closing the window skips this shutdown only; the setting
 * stays on. At zero, or on "Shut down now", the shutdown runs off the EDT.
 */
class ShutdownCountdownWindow private constructor(private val seconds: Int) : JDialog(null as Window?) {

    private var remaining = seconds
    private var finished = false

    private val lblMessage = JLabel()
    private val progress = JProgressBar(0, seconds).apply { value = seconds }
    private val timer = Timer(1000) { tick() }

    init {
        title = text("SHUTDOWN_TITLE")
        iconImages = windowIconImages
        isAlwaysOnTop = true // ignored on native Wayland
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val btnCancel = JButton(text("ND_CANCEL")).apply { addActionListener { cancel() } }
        val btnNow = JButton(text("SHUTDOWN_NOW")).apply { addActionListener { shutDown() } }

        val icon = JLabel(UIManager.getIcon("OptionPane.warningIcon")).apply {
            verticalAlignment = JLabel.TOP
            border = ScaledEmptyBorder(0, 0, 0, 12)
        }
        val center = JPanel(BorderLayout(0, 10.px)).apply {
            add(lblMessage, BorderLayout.NORTH)
            add(progress, BorderLayout.CENTER)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8.px, 0)).apply {
            add(btnNow)
            add(btnCancel)
        }
        contentPane = JPanel(BorderLayout(0, 16.px)).apply {
            border = ScaledEmptyBorder(16, 16, 12, 16)
            add(icon, BorderLayout.WEST)
            add(center, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        getRootPane().defaultButton = btnCancel
        getRootPane().registerKeyboardAction(
            { cancel() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW
        )
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                timer.stop()
                if (!finished) {
                    finished = true
                    Logger.info("Shutdown after all downloads cancelled by the user")
                }
                if (current === this@ShutdownCountdownWindow) current = null
            }
        })

        updateMessage()
        pack()
        // Room for the longest count without the window resizing as it ticks down.
        minimumSize = size
        setLocationRelativeTo(mainWindowOnScreen())
    }

    private fun updateMessage() {
        lblMessage.text = text("SHUTDOWN_MSG").format(remaining)
        progress.value = remaining
    }

    private fun tick() {
        remaining--
        if (remaining <= 0) shutDown() else updateMessage()
    }

    private fun cancel() = dispose()

    private fun shutDown() {
        if (finished) return
        finished = true
        timer.stop()
        dispose()
        Logger.info("Shutting down after all downloads")
        Thread({ AppContext.platform.shutdownPC() }, "shutdown").apply { isDaemon = true }.start()
    }

    companion object {
        const val DEFAULT_SECONDS = 60

        /** EDT only. */
        private var current: ShutdownCountdownWindow? = null

        /** Starts the countdown unless one is already running. Any thread. */
        fun start(seconds: Int = DEFAULT_SECONDS) {
            SwingUtilities.invokeLater {
                current?.takeIf { it.isDisplayable }?.let { it.toFront(); return@invokeLater }
                val window = ShutdownCountdownWindow(seconds)
                current = window
                window.isVisible = true
                window.timer.start()
            }
        }
    }
}
