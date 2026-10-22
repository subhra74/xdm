package xdm.integration

/**
 * Decides whether a `/clear-tab` really means the tab moved to another page.
 *
 * The extension sends `/clear-tab` whenever Chrome reports the tab as `loading`, and Chrome does that
 * for iframe loads too: on Facebook that is dozens of times a second while a video plays, and every
 * one used to wipe the tab's captures (and the split-track detector's state), so the list kept
 * emptying and refilling. The message carries the tab's URL, so a clear for the page the tab is
 * already on is ignored. The cost: a reload of the same URL no longer clears the tab either; the
 * tracker drops the duplicates that reload would capture again (see CapturedVideoTracker).
 */
class ClearTabFilter(private val maxTabs: Int = 256) {
    /** Tab id -> the page URL of the last clear that went through. */
    private val pages = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > maxTabs
    }

    /** True if the tab's captures should be cleared: its page changed, or the message names no page. */
    @Synchronized
    fun shouldClear(tabId: String, tabUrl: String?): Boolean {
        if (tabUrl.isNullOrEmpty()) return true
        return pages.put(tabId, tabUrl) != tabUrl
    }
}
