package xdm.app.utils

import xdm.app.OS
import xdm.core.util.Logger
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** XDM's website: download page, user guide and the help pages linked from the app. */
const val WEBSITE_URL = "https://xtremedownloadmanager.com"
const val WEBSITE_HELP_URL = "$WEBSITE_URL/help/"

/** Compares `?v=<version>` with the latest GitHub release (docs/update-checker.html on GitHub Pages). */
const val UPDATE_CHECK_PAGE_URL = "https://subhra74.github.io/xdm/update-checker.html"

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
 * (and on some Linux desktops much longer). On Linux falls back to `xdg-open` when AWT has no BROWSE
 * action (JBR under XWayland on KDE reports none) or `browse` fails.
 */
fun openInDefaultBrowser(url: String) {
    thread(isDaemon = true, name = "open-url") {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return@thread
            }
        } catch (e: Exception) {
            if (detectOS() != OS.Linux) {
                Logger.error("XDM", "Failed to open $url", e)
                return@thread
            }
            Logger.error("XDM", "Desktop.browse failed for $url, trying xdg-open", e)
        }
        if (detectOS() == OS.Linux) xdgOpen(url)
    }
}

/**
 * Runs `xdg-open` and logs a failing exit. On KDE/GNOME it hands off and exits at once, but its generic
 * path (`$BROWSER`, unknown desktop) runs a not-yet-running browser in the foreground, so the wait is
 * bounded: still running after the timeout means the browser started, and the process is left alone.
 */
private fun xdgOpen(url: String) {
    try {
        val p = ProcessBuilder("xdg-open", url)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() != 0) {
            Logger.error("XDM", "xdg-open exited with ${p.exitValue()} for $url")
        }
    } catch (e: Exception) {
        Logger.error("XDM", "Failed to open $url", e)
    }
}
