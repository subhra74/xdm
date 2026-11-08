package xdm.integration

import xdm.app.utils.Browser

/**
 * Tells from a request's headers whether the browser extension talking to XDM is older than the
 * oldest version XDM still works well with, and in which browser it runs.
 *
 * Current extensions name themselves in [HEADER] as `<family>/<version>` (`chrome/3.5`,
 * `firefox/1.1`). Extensions from before that header existed send nothing, so a request from an
 * extension origin without it is taken as too old. Requests from anything that is not an extension
 * (local tools, no extension `Origin`, no header) are never reported.
 */
internal object ExtensionVersionCheck {
    const val HEADER = "X-XDM-Extension"

    /** Oldest versions that need no update; also the first ones to send [HEADER]. */
    private const val MIN_CHROME_VERSION = "3.5"
    private const val MIN_FIREFOX_VERSION = "1.1"

    /**
     * The browser running an outdated extension, or null when the extension is current or the
     * request did not come from one. Chromium-based browsers share the Chrome extension; Edge is
     * told apart by its user agent so the update opens Edge's own page.
     */
    fun outdatedBrowser(header: String?, origin: String?, userAgent: String?): Browser? {
        val firefox: Boolean
        if (!header.isNullOrBlank()) {
            val family = header.substringBefore('/').trim().lowercase()
            val version = header.substringAfter('/', "").trim()
            firefox = when (family) {
                "firefox" -> true
                "chrome" -> false
                else -> return null
            }
            if (!isOlder(version, if (firefox) MIN_FIREFOX_VERSION else MIN_CHROME_VERSION)) {
                return null
            }
        } else {
            val scheme = origin?.substringBefore("://")?.lowercase() ?: return null
            firefox = when (scheme) {
                "moz-extension" -> true
                "chrome-extension" -> false
                else -> return null
            }
        }
        return when {
            firefox -> Browser.FIREFOX
            userAgent?.contains("Edg/") == true -> Browser.EDGE
            else -> Browser.CHROME
        }
    }

    /**
     * Whether dotted [version] is lower than [minimum], comparing each part as a number (missing
     * parts count as 0). A version that does not parse counts as older.
     */
    fun isOlder(version: String, minimum: String): Boolean {
        val a = parts(version) ?: return true
        val b = parts(minimum) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x < y
        }
        return false
    }

    private fun parts(version: String): List<Int>? =
        version.takeIf { it.isNotBlank() }?.split('.')?.map { it.trim().toIntOrNull() ?: return null }
}
