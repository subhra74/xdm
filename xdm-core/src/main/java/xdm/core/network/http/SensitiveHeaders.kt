package xdm.core.network.http

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps credential-bearing request headers (a browser-captured `Authorization: Basic ...`) out of
 * the files a download writes to disk. The `.info` / `.state` writers [strip] them, parking the
 * values here under the download id, and the readers [restore] them, so a download paused and
 * resumed in the same run still authenticates. After a restart they are gone: the server answers
 * 401 and the user has to start the download again from the browser.
 */
object SensitiveHeaders {
    private val NAMES = setOf("authorization")

    private val byId = ConcurrentHashMap<Long, HeaderMap>()

    fun isSensitive(name: String) = name.lowercase() in NAMES

    /**
     * [headers] without the sensitive entries, which become what is kept in memory for [id]. The
     * caller always holds the full headers (every read goes through [restore]), so a save without
     * them means the download no longer has them, e.g. after Refresh link: forget the old ones.
     */
    fun strip(id: Long, headers: HeaderMap?): HeaderMap? {
        val (secret, rest) = headers.orEmpty().entries.partition { isSensitive(it.key) }
        if (secret.isEmpty()) {
            byId.remove(id)
            return headers
        }
        byId[id] = secret.associate { it.key to it.value }
        return rest.associate { it.key to it.value }
    }

    /** [headers] (as read from disk) with the sensitive entries remembered for [id] put back. */
    fun restore(id: Long, headers: HeaderMap?): HeaderMap? {
        // A file written before this existed may still carry them: take those as the remembered ones.
        val (legacy, rest) = headers.orEmpty().entries.partition { isSensitive(it.key) }
        if (legacy.isNotEmpty()) byId[id] = legacy.associate { it.key to it.value }
        val secret = byId[id] ?: return if (legacy.isEmpty()) headers else rest.associate { it.key to it.value }
        return LinkedHashMap<String, List<String>>().apply {
            rest.forEach { put(it.key, it.value) }
            putAll(secret)
        }
    }

    fun forget(id: Long) {
        byId.remove(id)
    }
}
