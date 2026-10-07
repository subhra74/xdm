package xdm.app.utils

import xdm.app.OS
import xdm.core.util.Logger
import java.awt.Desktop
import java.net.URI
import kotlin.concurrent.thread

/** XDM's website: download page, user guide and the help pages linked from the app. */
const val WEBSITE_URL = "https://xtremedownloadmanager.com"
const val WEBSITE_HELP_URL = "$WEBSITE_URL/help/"

fun validateURL(url: String): Boolean {
    try {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith(
                "https://",
                ignoreCase = true
            ) || url.startsWith("ftp://", ignoreCase = true)
        ) {
            URI.create(url)
            return true
        }
        return false
    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
}

/**
 * Opens [url] in the default browser. Runs on its own thread: `Desktop.browse` can block for a moment
 * (and on some Linux desktops much longer). Falls back to `xdg-open` where AWT has no BROWSE action.
 */
fun openInDefaultBrowser(url: String) {
    thread(isDaemon = true, name = "open-url") {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return@thread
            }
            if (detectOS() == OS.Linux) {
                ProcessBuilder("xdg-open", url).start()
            }
        } catch (e: Exception) {
            Logger.error("XDM", "Failed to open $url", e)
        }
    }
}
