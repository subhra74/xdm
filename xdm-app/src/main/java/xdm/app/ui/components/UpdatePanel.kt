package xdm.app.ui.components

import xdm.app.utils.RemixIcon
import xdm.core.util.Logger
import java.awt.Desktop
import java.net.URI

/**
 * A slim banner shown at the bottom of the main window when a newer release is
 * available. Hidden by default; [showUpdate] makes it visible and wires the
 * button to open the download page. Must be mutated on the EDT.
 */
class UpdatePanel : BannerPanel(RemixIcon.INSTALL_FILL, "BTN_UPDATE_NOW", "BTN_UPDATE_DISMISS") {

    private var downloadUrl: String = ""

    init {
        actionButton.apply {
            text = "Update"
            toolTipText = "Open the XDM downloads page"
            addActionListener { openDownloadPage() }
        }
    }

    /** Shows the banner with a message referencing [version] and remembers [url]. */
    fun showUpdate(version: String, url: String) {
        downloadUrl = url
        messageLabel.text = "A new version ($version) of XDM is available."
        setShown(true)
    }

    private fun openDownloadPage() {
        if (downloadUrl.isBlank()) return
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(downloadUrl))
            }
        } catch (e: Exception) {
            Logger.error("UPDATE", "Failed to open download page: $downloadUrl", e)
        }
    }
}
