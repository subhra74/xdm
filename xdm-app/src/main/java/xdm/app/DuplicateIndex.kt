package xdm.app

import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/** How sure a [DuplicateIndex.find] match is: a same URL is certain, the others are only likely. */
enum class DuplicateKind { SAME_URL, SAME_NAME_AND_SIZE, SAME_ETAG }

data class DuplicateMatch(val record: DbRecord, val kind: DuplicateKind)

/**
 * Remembers a hash of every HTTP download's URL and of its ETag (when the browser reported one) in
 * `download-index.dat`, so the New Download dialog can spot a duplicate without reading every
 * `task-<id>.info`. Name and size need no index: [AppDB] already holds them in memory.
 *
 * Loaded on first use. Entries of downloads that are gone are dropped then, and [remove] only
 * forgets in memory: the file is rewritten by the next [add]/[updateUrl], so clearing a long list
 * does not rewrite it once per download. A leftover entry is harmless since [find] ignores ids
 * that have no record.
 */
class DuplicateIndex(private val configDir: String, private val appDB: AppDB) {
    private class Entry(var urlHash: Long, val etagHash: Long?)

    private val entries = LinkedHashMap<Long, Entry>()
    private var loaded = false

    @Synchronized
    fun add(id: Long, url: String, etag: String?) {
        ensureLoaded()
        entries[id] = Entry(urlHash(url), etagHash(url, etag))
        save()
    }

    /** A refreshed link keeps its ETag entry: the file is the same, only the address changed. */
    @Synchronized
    fun updateUrl(id: Long, url: String) {
        ensureLoaded()
        val entry = entries[id] ?: return
        entry.urlHash = urlHash(url)
        save()
    }

    @Synchronized
    fun remove(id: Long) {
        if (loaded) entries.remove(id)
    }

    /**
     * The existing download this one most likely duplicates, or null. Checked strongest first: the
     * same URL, then the same file name with the same known size, then the same strong ETag from the
     * same host. A name alone, or a size of 0 (not known yet), is never enough. The newest record
     * wins within a kind.
     */
    fun find(url: String, fileName: String?, size: Long?, etag: String?): DuplicateMatch? {
        val (byUrl, byEtag) = synchronized(this) {
            ensureLoaded()
            val u = urlHash(url)
            val e = etagHash(url, etag)
            entries.filterValues { it.urlHash == u }.keys to
                    (e?.let { h -> entries.filterValues { it.etagHash == h }.keys } ?: emptySet())
        }
        fun newest(ids: Collection<Long>) = ids.mapNotNull { appDB.getById(it) }.maxByOrNull { it.date }

        newest(byUrl)?.let { return DuplicateMatch(it, DuplicateKind.SAME_URL) }
        if (!fileName.isNullOrBlank() && size != null && size > 0) {
            appDB.findAll { it.size == size && it.fileName.equals(fileName, ignoreCase = true) }
                .maxByOrNull { it.date }
                ?.let { return DuplicateMatch(it, DuplicateKind.SAME_NAME_AND_SIZE) }
        }
        newest(byEtag)?.let { return DuplicateMatch(it, DuplicateKind.SAME_ETAG) }
        return null
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!AtomicIO.exists(FILE_NAME, configDir)) return
        AtomicIO.readTransacted(FILE_NAME, configDir) { r ->
            if (r.readInt() != VERSION) return@readTransacted
            repeat(r.readInt()) {
                val id = r.readLong()
                val urlHash = r.readLong()
                val etagHash = if (r.readBoolean()) r.readLong() else null
                if (appDB.getById(id) != null) entries[id] = Entry(urlHash, etagHash)
            }
        }.onFailure { Logger.error("XDM", "Unable to read $FILE_NAME", it) }
    }

    private fun save() {
        AtomicIO.writeTransacted(FILE_NAME, configDir) { w ->
            w.writeInt(VERSION)
            w.writeInt(entries.size)
            for ((id, e) in entries) {
                w.writeLong(id)
                w.writeLong(e.urlHash)
                w.writeBoolean(e.etagHash != null)
                e.etagHash?.let { w.writeLong(it) }
            }
        }.onFailure { Logger.error("XDM", "Unable to save $FILE_NAME", it) }
    }

    companion object {
        const val FILE_NAME = "download-index.dat"
        private const val VERSION = 1

        /**
         * Trimmed, scheme and host lower-cased, fragment dropped. The query is kept: it often picks
         * the file. A URL that does not parse is only trimmed.
         */
        fun normalizeUrl(url: String): String {
            val trimmed = url.trim()
            return try {
                val u = URI(trimmed)
                if (u.scheme == null || u.rawAuthority == null) return trimmed.substringBefore('#')
                buildString {
                    append(u.scheme.lowercase(Locale.ROOT)).append("://")
                    u.rawUserInfo?.let { append(it).append('@') }
                    append(u.host?.lowercase(Locale.ROOT) ?: u.rawAuthority.lowercase(Locale.ROOT))
                    if (u.host != null && u.port != -1) append(':').append(u.port)
                    append(u.rawPath.ifEmpty { "/" })
                    u.rawQuery?.let { append('?').append(it) }
                }
            } catch (_: Exception) {
                trimmed.substringBefore('#')
            }
        }

        fun urlHash(url: String): Long = hash(normalizeUrl(url))

        /**
         * Keyed by host as well, since two servers can hand out the same ETag for unrelated files.
         * Weak ETags (`W/"..."`) only promise equivalent content, so they are not used.
         */
        fun etagHash(url: String, etag: String?): Long? {
            val tag = etag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/", ignoreCase = true) }
                ?: return null
            val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
            return hash("$host\n$tag")
        }

        /** The first 8 bytes of SHA-256: collisions are negligible at download-list sizes. */
        private fun hash(s: String): Long =
            ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))).long
    }
}
