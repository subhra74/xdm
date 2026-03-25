package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.SwingConstants

class RefreshLinkWindow(val id: Long) : JDialog() {
    init {
        size = Dimension(250, 100)
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isAlwaysOnTop = true
        setLocationRelativeTo(null)
        title = text("MENU_REFRESH_LINK")
        contentPane.add(JLabel(text("REF_WAITING_FOR_LINK")).apply {
            horizontalAlignment = SwingConstants.CENTER
            horizontalTextPosition = SwingConstants.CENTER
        })
    }
}