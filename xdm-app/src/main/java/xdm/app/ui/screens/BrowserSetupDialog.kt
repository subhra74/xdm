package xdm.app.ui.screens

import xdm.app.I8N
import xdm.app.ui.components.BrowserExtensionPanel
import xdm.app.ui.screens.settings.settingsButton
import xdm.app.ui.screens.settings.settingsCard
import xdm.app.ui.screens.settings.settingsTitle
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.px
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JDialog
import javax.swing.JPanel

/** Shown on first run: points the user at the browser extension, which is how downloads reach XDM. */
class BrowserSetupDialog(owner: Window?) : JDialog(owner) {

    private val btnDone = settingsButton(I8N.text("BROWSER_SETUP_DONE")) { dispose() }

    init {
        title = I8N.text("BROWSER_SETUP_TITLE")
        //isModal = true
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val panel = JPanel().apply {
            layout = BorderLayout(10.px, 5.px)
            border = ScaledEmptyBorder(15, 20, 15, 20)

            add(settingsTitle(I8N.text("BROWSER_SETUP_TITLE")).apply {
                font = font.deriveFont(15f.px)
                // settingsTitle's own 18px bottom gap is sized for a settings page; too much here.
                border = ScaledEmptyBorder(0, 2, 6, 0)
            }, BorderLayout.NORTH)
            // Same card as the settings Browsers section, so the list sits on it instead of standing apart.
            add(settingsCard(BrowserExtensionPanel().apply { border = ScaledEmptyBorder(14, 0, 16, 0) }))

            add(Box.createHorizontalBox().apply {
                alignmentX = Component.LEFT_ALIGNMENT
                border = ScaledEmptyBorder(12, 0, 0, 0)
                add(Box.createHorizontalGlue())
                add(btnDone)
            }, BorderLayout.SOUTH)
        }

        contentPane.add(panel)
        rootPane.defaultButton = btnDone
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                btnDone.requestFocusInWindow()
            }
        })
        pack()
        minimumSize = size
        // Same height as the main window.
        setSize(maxOf(width, 500.px), maxOf(height, 500.px))
        setLocationRelativeTo(owner)
    }
}
