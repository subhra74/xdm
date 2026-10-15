package xdm.app.utils

import java.io.File
import java.nio.file.Files

/**
 * Tells whether a destination folder sits on the same volume as the temp folder. Downloads are
 * written to temp first and moved at the end, so a shared volume makes that move a metadata-only
 * rename and a different one makes it a full byte copy. See FILE_PLACEMENT.md.
 */
object VolumeHints {

    /**
     * True when [folder] is on a different volume from [tempFolder] and we are confident about it.
     *
     * Returns false whenever the answer is not clear — a path that does not exist yet and has no
     * existing ancestor, a network share whose file store does not compare meaningfully, or any
     * I/O error. A warning that is wrong about the user's own disk layout is worse than no warning,
     * so ambiguity is silence.
     */
    fun isDifferentVolume(folder: String?, tempFolder: String?): Boolean {
        if (folder.isNullOrBlank() || tempFolder.isNullOrBlank()) return false
        return runCatching {
            val a = storeOf(folder) ?: return false
            val b = storeOf(tempFolder) ?: return false
            a != b
        }.getOrDefault(false)
    }

    /**
     * Free (usable) and total bytes of the volume holding [path], or null when that cannot be told:
     * no existing ancestor, or a volume that reports nothing (some network shares report 0).
     */
    fun spaceOf(path: String?): Space? {
        if (path.isNullOrBlank()) return null
        val f = existingAncestor(path) ?: return null
        return runCatching {
            val total = f.totalSpace
            if (total <= 0) null else Space(free = f.usableSpace, total = total)
        }.getOrNull()
    }

    /**
     * Whether [free] bytes is too little for temp data: less than [needed] when the download's size
     * is known, otherwise less than [LOW_SPACE_BYTES]. An unknown size gets a fixed floor rather
     * than silence, since the dialog cannot tell how big the file will be.
     */
    fun isLow(free: Long, needed: Long?): Boolean =
        if (needed != null && needed > 0) free < needed else free < LOW_SPACE_BYTES

    /** Below this much free space the temp volume counts as low when the download's size is unknown. */
    const val LOW_SPACE_BYTES = 1L shl 30

    data class Space(val free: Long, val total: Long)

    /**
     * The file store holding [path]. Category folders are created lazily, so the path itself
     * usually does not exist yet; walk up to the nearest ancestor that does.
     */
    private fun storeOf(path: String): Any? {
        val f = existingAncestor(path) ?: return null
        return runCatching { Files.getFileStore(f.toPath()) }.getOrNull()
    }

    private fun existingAncestor(path: String): File? {
        var f: File? = File(path).absoluteFile
        while (f != null && !f.exists()) f = f.parentFile
        return f
    }
}
