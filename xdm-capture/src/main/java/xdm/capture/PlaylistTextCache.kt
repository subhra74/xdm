// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The text of HLS media playlists the page's player received, as the browser sent it with the capture
 * (`manifestBody`). Some sites sign a playlist link for one request, so XDM's own fetch of it when the
 * download starts gets a 403; the download then uses this text instead.
 *
 * Bounded by entry count and by total bytes, least recently used dropped first, and kept as UTF-8 bytes
 * (half the size of a `String`): a media playlist is usually a few KB to a few tens of KB, a long one up
 * to the 1 MB the browser side sends at most. Keyed by (tab, URL without its fragment): a master's
 * variant links can carry a `#…` fragment that the request the browser reports does not.
 */
class PlaylistTextCache(private val maxEntries: Int = 64, private val maxBytes: Long = 2L * 1024 * 1024) {
    private val map = LinkedHashMap<Pair<String, String>, ByteArray>(16, 0.75f, true)
    private var bytes = 0L

    fun put(tabId: String?, url: String, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        if (data.size > maxBytes) return
        val key = key(tabId, url)
        synchronized(this) {
            map.put(key, data)?.let { bytes -= it.size }
            bytes += data.size
            val it = map.entries.iterator()
            while ((map.size > maxEntries || bytes > maxBytes) && it.hasNext()) {
                val eldest = it.next()
                if (eldest.key == key) continue
                bytes -= eldest.value.size
                it.remove()
            }
        }
    }

    /** The text for each of [urls] that is known, keyed by the URL as given. */
    fun get(tabId: String?, urls: Collection<String>): Map<String, ByteArray> = synchronized(this) {
        val result = LinkedHashMap<String, ByteArray>()
        for (url in urls) map[key(tabId, url)]?.let { result[url] = it }
        result
    }

    fun clear() = synchronized(this) {
        map.clear()
        bytes = 0
    }

    private fun key(tabId: String?, url: String): Pair<String, String> {
        val normal = url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()
            ?: url.substringBefore('#')
        return (tabId ?: "0") to normal
    }
}
