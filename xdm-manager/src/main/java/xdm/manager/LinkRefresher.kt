// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager

import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.Range
import xdm.core.util.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Gets an HTTP download a fresh link when the one it has expired, from the link the browser was redirected
 * from ([HttpDownloadTaskInfo.sourceUrl]): a site's download link that hands out short-lived signed file URLs
 * gives a new one each time it is asked.
 *
 * A download is refreshed again only after it made progress since its last refresh, and not twice within
 * [MIN_INTERVAL_MS]: a fresh link that is refused at once ends as the usual expired-link error instead of a
 * loop, while a long download whose links expire every few minutes keeps getting new ones. Kept in memory
 * only: after a restart the download may refresh once more.
 */
class LinkRefresher(
    private val newClient: () -> PoolingHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Mark(val downloaded: Long, val at: Long)

    private val marks = ConcurrentHashMap<Long, Mark>()

    /** Whether download [id], now at [downloaded] bytes, may be refreshed; records the attempt if so. */
    fun claim(id: Long, downloaded: Long): Boolean {
        val time = now()
        var allowed = false
        marks.compute(id) { _, last ->
            allowed = last == null || (downloaded > last.downloaded && time - last.at >= MIN_INTERVAL_MS)
            if (allowed) Mark(downloaded, time) else last
        }
        return allowed
    }

    fun forget(id: Long) {
        marks.remove(id)
    }

    /**
     * Asks [task]'s source link for the file again and returns where it leads now, or null when it does not
     * lead to the same file: not a range answer for it, a different size than [size], or a different
     * Last-Modified than [validator] (the bytes on disk would belong to another file).
     */
    fun resolve(task: HttpDownloadTaskInfo, size: Long?, validator: String?): String? {
        val source = task.sourceUrl ?: return null
        // Only the source host's own cookies: the ones the file's host was sent stay out of it.
        val headers = task.headers?.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        val client = newClient()
        try {
            val response = client.getResponse(source, headers, task.sourceCookie, Range(0, 0)).getOrElse {
                Logger.info("XDM", "Download ${task.id}: source link failed: ${it.message}")
                return null
            }
            response.use { r ->
                val url = r.finalUrl ?: source
                val total = when (r.statusCode) {
                    206 -> r.getHeader("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    200 -> r.contentLength
                    else -> null
                }
                val modified = r.getHeader("Last-Modified")
                val reason = when {
                    r.statusCode != 206 && r.statusCode != 200 -> "status ${r.statusCode}"
                    size != null && total != size -> "size $total, expected $size"
                    validator != null && modified != null && modified != validator -> "the file changed"
                    else -> null
                }
                if (reason != null) {
                    Logger.info("XDM", "Download ${task.id}: source link gives no usable link ($reason)")
                    return null
                }
                return url
            }
        } finally {
            client.close()
        }
    }

    companion object {
        /** The least time between two refreshes of one download. */
        const val MIN_INTERVAL_MS = 30_000L
    }
}
