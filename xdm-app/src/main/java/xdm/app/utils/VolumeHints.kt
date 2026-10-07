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
     * The file store holding [path]. Category folders are created lazily, so the path itself
     * usually does not exist yet; walk up to the nearest ancestor that does.
     */
    private fun storeOf(path: String): Any? {
        var f: File? = File(path).absoluteFile
        while (f != null && !f.exists()) f = f.parentFile
        if (f == null) return null
        return runCatching { Files.getFileStore(f.toPath()) }.getOrNull()
    }
}
