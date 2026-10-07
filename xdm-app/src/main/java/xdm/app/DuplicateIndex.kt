package xdm.app

import xdm.core.util.Logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/** How sure a [DuplicateIndex.find] match is: a same URL is certain, the others are only likely. */
enum class DuplicateKind { SAME_URL, SAME_NAME_AND_SIZE, SAME_ETAG }

data class DuplicateMatch(val record: DbRecord, val kind: DuplicateKind)

/**
 * Remembers a hash of every HTTP download's URL and of its ETag (when the browser reported one), so
 * the New Download dialog can spot a duplicate without reading every `task-<id>.info`. Name and
 * size need no index: [AppDB] already holds them in memory.
 *
 * Nothing is kept in memory: `download-index.log` is append-only and [find] streams through it, so
 * the check costs a few milliseconds per click and no heap in between. [AppDB] decides which
 * entries are alive, so a deleted download needs no write at all; its entries are skipped until a
 * [find] sees that most of the file is dead and rewrites it without them.
 *
 * The file is an 8-byte header followed by fixed [RECORD_SIZE]-byte records, so a record cut short
 * by a crash is recognised by length and dropped. Appends are not synced: losing the last entry
 * only means one duplicate goes unnoticed.
 */
class DuplicateIndex(configDir: String, private val appDB: AppDB) {
    private val file = File(configDir, FILE_NAME)

    /** Registers a new download. */
    @Synchronized
    fun add(id: Long, url: String, etag: String?) = append(id, KIND_ADD, urlHash(url), etagHash(url, etag) ?: NO_ETAG)

    /** A refreshed link keeps its ETag: the file is the same, only the address changed. */
    @Synchronized
    fun updateUrl(id: Long, url: String) = append(id, KIND_URL, urlHash(url), NO_ETAG)

    /**
     * The existing download this one most likely duplicates, or null. Checked strongest first: the
     * same URL, then the same file name with the same known size, then the same strong ETag from the
     * same host. A name alone, or a size of 0 (not known yet), is never enough. The newest record
     * wins within a kind.
     */
    fun find(url: String, fileName: String?, size: Long?, etag: String?): DuplicateMatch? {
        val (byUrl, byEtag) = scan(urlHash(url), etagHash(url, etag))
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

    /**
     * Streams the file once, collecting the ids whose latest URL / ETag entry matches. Only matches
     * are held, so memory does not grow with the list. Compacts afterwards if most entries are dead.
     */
    @Synchronized
    private fun scan(url: Long, etag: Long?): Pair<Set<Long>, Set<Long>> {
        val byUrl = HashSet<Long>()
        val byEtag = HashSet<Long>()
        var dead = 0
        var total = 0
        forEachRecord { id, kind, u, e ->
            total++
            if (appDB.getById(id) == null) dead++
            if (u == url) byUrl.add(id) else byUrl.remove(id)
            if (kind == KIND_ADD && etag != null) {
                if (e == etag) byEtag.add(id) else byEtag.remove(id)
            }
        }
        if (dead >= COMPACT_MIN_DEAD && dead * 2 > total) compact()
        return byUrl to byEtag
    }

    private inline fun forEachRecord(action: (id: Long, kind: Byte, urlHash: Long, etagHash: Long) -> Unit) {
        if (!file.isFile) return
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 16 * 1024)).use { r ->
                if (r.readLong() != MAGIC) return
                // A trailing partial record (a crash mid-append) is simply never read.
                repeat(((file.length() - HEADER_SIZE) / RECORD_SIZE).toInt()) {
                    action(r.readLong(), r.readByte(), r.readLong(), r.readLong())
                }
            }
        } catch (_: EOFException) {
        } catch (e: Exception) {
            Logger.error("XDM", "Unable to read $FILE_NAME", e)
        }
    }

    private fun append(id: Long, kind: Byte, urlHash: Long, etagHash: Long) {
        try {
            RandomAccessFile(file, "rw").use { f ->
                val len = f.length()
                if (len < HEADER_SIZE || !hasMagic(f)) {
                    f.setLength(0)
                    f.writeLong(MAGIC)
                } else {
                    // Drop a partial record left by a crash, so the new one starts on a boundary.
                    f.setLength(len - (len - HEADER_SIZE) % RECORD_SIZE)
                }
                f.seek(f.length())
                f.write(
                    ByteBuffer.allocate(RECORD_SIZE)
                        .putLong(id).put(kind).putLong(urlHash).putLong(etagHash).array()
                )
            }
        } catch (e: Exception) {
            Logger.error("XDM", "Unable to write $FILE_NAME", e)
        }
    }

    private fun hasMagic(f: RandomAccessFile): Boolean {
        f.seek(0)
        return f.readLong() == MAGIC
    }

    /** Rewrites the file with only the entries of downloads that still exist, streaming both ways. */
    private fun compact() {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 16 * 1024)).use { w ->
                w.writeLong(MAGIC)
                forEachRecord { id, kind, u, e ->
                    if (appDB.getById(id) != null) {
                        w.writeLong(id); w.writeByte(kind.toInt()); w.writeLong(u); w.writeLong(e)
                    }
                }
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Logger.info("XDM", "Compacted $FILE_NAME to ${file.length()} bytes")
        } catch (e: Exception) {
            tmp.delete()
            Logger.error("XDM", "Unable to compact $FILE_NAME", e)
        }
    }

    companion object {
        const val FILE_NAME = "download-index.log"
        private const val MAGIC = 0x58444D4944583031L // "XDMIDX01"
        private const val HEADER_SIZE = 8L
        private const val RECORD_SIZE = 25 // id, kind, url hash, etag hash
        private const val KIND_ADD: Byte = 0
        private const val KIND_URL: Byte = 1
        private const val NO_ETAG = 0L
        private const val COMPACT_MIN_DEAD = 1000

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
            // 0 marks "no ETag" on disk; a real hash landing on it just goes unmatched.
            return hash("$host\n$tag").takeIf { it != NO_ETAG }
        }

        /** The first 8 bytes of SHA-256: collisions are negligible at download-list sizes. */
        private fun hash(s: String): Long =
            ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))).long
    }
}
