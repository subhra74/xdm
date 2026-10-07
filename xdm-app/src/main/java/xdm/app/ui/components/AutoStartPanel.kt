package xdm.app.ui.components

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.AppLauncher
import xdm.app.utils.AutoStart
import xdm.app.utils.RemixIcon

/**
 * Banner at the bottom of the main window while XDM is not set to start at login. Windows only: there
 * the MSI owns the login entry (PACKAGING.md §5.4) and the app never writes it on its own, so this is
 * how a user gets it back after Task Manager, an antivirus, XDM 8's uninstall or a SYSTEM install left
 * it off. The button is the user's request to write it. Dismissing hides it for this session.
 * Must be used on the EDT.
 */
class AutoStartPanel : BannerPanel(RemixIcon.TOGGLE_FILL, "BTN_AUTOSTART_ENABLE", "BTN_AUTOSTART_DISMISS") {

    /** Dev runs would register `java -jar`, as on first run elsewhere. */
    private val applies = AutoStart.isInstallerOwned && AppLauncher.isPackaged
    private var dismissed = false

    init {
        messageLabel.text = I8N.text("MSG_AUTOSTART_OFF")
        actionButton.apply {
            text = I8N.text("MSG_AUTOSTART_ENABLE")
            addActionListener { enableAutoStart() }
        }
        refresh()
    }

    /**
     * Re-reads the login entry; called when the main window is activated, so a change made in the
     * settings or in Task Manager shows up. Two registry reads, nothing is written.
     */
    fun refresh() {
        setShown(applies && !dismissed && !AutoStart.isEnabled())
    }

    private fun enableAutoStart() {
        val config = AppContext.config
        config.runOnStartup = AutoStart.setEnabled(true)
        config.save()
        refresh()
    }

    override fun dismissed() {
        dismissed = true
        refresh()
    }
}
