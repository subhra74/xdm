package xdm.app.ui.screens

import xdm.app.BatchRequest
import xdm.app.BatchRequestItem
import xdm.app.I8N.text
import xdm.app.ui.components.MessageBox
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.px
import xdm.app.utils.sameWidth
import xdm.app.utils.scaledSize
import xdm.app.utils.validateURL
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Window
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * First step of "Batch download" from the menu: the user types or pastes links, one per line. OK
 * opens the [BatchDownloadDialog] with the valid ones.
 */
class BatchUrlDialog(private val owner: Window?) : JDialog(owner) {
    private val txtUrls = JTextArea()

    init {
        title = text("MENU_BATCH_DOWNLOAD")
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isModal = true
        isResizable = true

        val content = JPanel(BorderLayout(0, 6.px)).apply {
            border = ScaledEmptyBorder(10, 10, 10, 10)
            add(JLabel(text("BATCH_URLS_HINT")), BorderLayout.NORTH)
            add(JScrollPane(txtUrls), BorderLayout.CENTER)
        }
        contentPane.add(content, BorderLayout.CENTER)
        contentPane.add(buttonPanel(), BorderLayout.SOUTH)
        size = scaledSize(560, 380)
        setLocationRelativeTo(owner)
    }

    private fun buttonPanel(): JPanel {
        val btnOk = JButton(text("BTN_OK")).apply { addActionListener { onOk() } }
        val btnCancel = JButton(text("ND_CANCEL")).apply { addActionListener { dispose() } }
        sameWidth(btnOk, btnCancel)
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = ScaledEmptyBorder(0, 10, 10, 10)
            add(Box.createHorizontalGlue())
            add(btnCancel)
            add(Box.createRigidArea(scaledSize(10, 0)))
            add(btnOk)
        }
    }

    private fun onOk() {
        val urls = parseUrls(txtUrls.text)
        if (urls.isEmpty()) {
            MessageBox.show(this, text("MENU_BATCH_DOWNLOAD"), text("BATCH_URLS_NONE"))
            return
        }
        dispose()
        BatchDownloadDialog(owner, BatchRequest(items = urls.map { BatchRequestItem(it) }, fromBrowser = false))
            .isVisible = true
    }

    companion object {
        /** The valid links in [text], one per line, each once, in order. */
        fun parseUrls(text: String): List<String> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && validateURL(it) }.distinct()
    }
}
