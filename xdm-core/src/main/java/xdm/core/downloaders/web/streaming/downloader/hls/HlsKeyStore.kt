package xdm.core.downloaders.web.streaming.downloader.hls

import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import xdm.core.util.readLongString
import xdm.core.util.writeLongString
import java.io.File
import java.io.IOException

/**
 * AES-128 keys of an HLS download, stored in an owner-only `<id>.keys` file next to `<id>.state`.
 *
 * Kept out of `.state` on purpose: the state file is rewritten every few seconds while the download
 * runs, while keys are written once after the playlist is parsed. Keys are needed only until every
 * segment is decrypted, so the file is deleted then. Without them a paused or restarted download
 * could not be decrypted, and the key URL may have expired by the time it resumes.
 */
object HlsKeyStore {
    private fun fileName(id: Long) = "$id.keys"

    fun save(id: Long, configDir: String, keys: Map<String, ByteArray>): Result<Unit> =
        AtomicIO.writeTransacted(fileName(id), configDir, ownerOnly = true) { w ->
            w.writeInt(keys.size)
            for ((url, key) in keys) {
                w.writeLongString(url)
                w.writeInt(key.size)
                w.write(key)
            }
        }

    /** Keys by key URL; empty if the file is missing or unreadable. */
    fun load(id: Long, configDir: String): Map<String, ByteArray> =
        AtomicIO.readTransacted(fileName(id), configDir) { r ->
            val keys = HashMap<String, ByteArray>()
            repeat(r.readInt()) {
                val url = r.readLongString()
                val size = r.readInt()
                if (size < 0 || size > 1024) throw IOException("Invalid key size: $size")
                keys[url] = ByteArray(size).also { r.readFully(it) }
            }
            keys as Map<String, ByteArray>
        }.onFailure { Logger.error("XDM", "Unable to load HLS keys for $id", it) }
            .getOrDefault(emptyMap())

    fun delete(id: Long, configDir: String) {
        val name = fileName(id)
        listOf(name, "$name.bak1", "$name.bak2").forEach { File(configDir, it).delete() }
    }
}

/** An encrypted segment could not be decrypted: key missing or wrong, or corrupt data. */
class DecryptionException(message: String, cause: Throwable? = null) : IOException(message, cause)
