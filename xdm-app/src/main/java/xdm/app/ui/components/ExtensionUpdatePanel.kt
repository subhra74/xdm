package xdm.app.ui.components

import xdm.app.I8N
import xdm.app.utils.Browser
import xdm.app.utils.BrowserLauncher
import xdm.app.utils.RemixIcon
import xdm.app.utils.openInDefaultBrowser
import kotlin.concurrent.thread

/**
 * A slim banner at the bottom of the main window saying the XDM extension in [browser] is out of
 * date. The button opens XDM's install page for it in that browser (the page links the store, which
 * only installs into the browser it is opened in), or in the default browser when that one cannot be
 * launched. Hidden by default. Must be mutated on the EDT.
 */
class ExtensionUpdatePanel(private val browser: Browser) :
    BannerPanel(RemixIcon.INSTALL_FILL, "BTN_EXTENSION_UPDATE", "BTN_EXTENSION_UPDATE_DISMISS") {

    private val url = BrowserExtensionPanel.extensionPageUrl(
        when (browser) {
            Browser.FIREFOX -> "firefox"
            Browser.EDGE -> "edge"
            else -> "chrome"
        }
    )

    init {
        messageLabel.text = String.format(I8N.text("EXT_UPDATE_MSG"), browser.displayName)
        actionButton.apply {
            text = I8N.text("EXT_UPDATE_BTN")
            toolTipText = String.format(I8N.text("EXT_UPDATE_TIP"), browser.displayName)
            addActionListener { openExtensionPage() }
        }
    }

    fun showBanner() = setShown(true)

    private fun openExtensionPage() {
        // Launching may wait on a helper process (macOS `open`), so keep it off the EDT.
        thread(isDaemon = true, name = "browser-launch") {
            if (!BrowserLauncher.launch(browser, url)) {
                openInDefaultBrowser(url)
            }
        }
    }
}
