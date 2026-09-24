package xdm.core.util

import xdm.core.downloaders.DownloadError
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.use

object FileUtils {
    private val invalidChars = setOf('/', '\\', '"', '?', '*', '<', '>', ':', '|')

    // Windows reserved device names (case-insensitive, matched on the base name).
    private val reservedNames = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    // Keep the whole name within common filesystem limits (255 bytes on most),
    // leaving headroom for temp suffixes and "_N" dedupe counters.
    private const val MAX_FILE_NAME_LENGTH = 200

    fun sanitizeFileName(name: String?): String? {
        if (name == null) return null
        val arr = name.toCharArray()
        for (i in arr.indices) {
            // Replace explicit invalid chars plus any control characters (incl. newlines).
            if (invalidChars.contains(arr[i]) || arr[i].code < 0x20) {
                arr[i] = '_'
            }
        }
        // Trailing dots/spaces are invalid on Windows and stripped silently there.
        var result = String(arr).trim().trimEnd('.', ' ')
        if (result.isEmpty()) return "_"

        // Avoid reserved device names (matched against the base, ignoring extension).
        val ext = getExtension(result) ?: ""
        val base = getFileNameWithoutExtension(result)
        if (reservedNames.contains(base.uppercase())) {
            result = "_$base$ext"
        }

        // Enforce a length cap while preserving the extension.
        if (result.length > MAX_FILE_NAME_LENGTH) {
            val cappedExt = if (ext.length <= 20) ext else ""
            val keep = (MAX_FILE_NAME_LENGTH - cappedExt.length).coerceAtLeast(1)
            result = getFileNameWithoutExtension(result).take(keep).trimEnd('.', ' ') + cappedExt
            if (result.isEmpty()) result = "_"
        }
        return result
    }

    fun getUniqueFileName(folder: String?, fileName: String): String {
        var f = File(folder, fileName)
        var ext = getExtension(fileName)
        val name = getFileNameWithoutExtension(fileName)
        if (ext == null) {
            ext = ""
        }
        var c = 0
        while (f.exists()) {
            c++
            f = File(folder, name + "_" + c + ext)
        }
        return if (c == 0) fileName else name + "_" + c + ext
    }

    fun getFileName(uri: String?): String {
        try {
            if (uri == null) return "FILE"
            if (uri == "/" || uri.isEmpty()) {
                return "FILE"
            }
            val x = uri.lastIndexOf("/")
            var path: String = uri
            if (x > -1) {
                path = uri.substring(x)
            }
            val qindex = path.indexOf("?")
            if (qindex > -1) {
                path = path.substring(0, qindex)
            }
            // Drop any URL fragment so it doesn't leak into the name/extension
            // (e.g. "video.mp4#t=10" -> extension ".mp4#t=10").
            val hindex = path.indexOf("#")
            if (hindex > -1) {
                path = path.substring(0, hindex)
            }
            path = decodeFileName(path)
            if (path.isEmpty()) return "FILE"
            if (path == "/") return "FILE"
            return sanitizeFileName(path) ?: "FILE"
        } catch (e: Exception) {
            Logger.info(e)
            return "FILE"
        }
    }

    fun decodeFileName(encoded: String): String {
        var str: String
        try {
            str = URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            val builder = StringBuilder()
            val ch = encoded.toCharArray()
            var i = 0
            while (i < ch.size) {
                if (ch[i] == '%' && i + 2 < ch.size) {
                    val c = (ch[i + 1].toString() + "" + ch[i + 2]).toInt(16)
                    builder.append(c.toChar())
                    i += 2
                    continue
                }
                builder.append(ch[i])
                i++
            }
            str = builder.toString()
        }
        val builder = StringBuilder()
        for (c in str.toCharArray()) {
            if (c == '/' || c == '\\' || c == '"' || c == '?' || c == '*' || c == '<' || c == '>' || c == ':') continue
            builder.append(c)
        }
        return builder.toString()
    }

    /**
     * Marks [file] hidden where the platform needs an attribute for it. A leading dot is enough on
     * Unix but means nothing on Windows, so the streaming part file would otherwise be plainly
     * visible in the user's folder while it is being muxed. Failures are ignored: the attribute is
     * cosmetic, and not every file system supports it.
     */
    fun hideFile(file: File) {
        runCatching { Files.setAttribute(file.toPath(), "dos:hidden", true) }
    }

    /**
     * Moves [src] to [dst] without ever overwriting an existing file or leaving a partial [dst].
     *
     * Tries an atomic rename first. Across file systems (where a rename is impossible) it checks free
     * space, copies to a scratch file next to [dst], flushes it to disk, renames it into place on the
     * destination volume, then deletes [src]. On any failure [src] is left untouched and the scratch
     * file is removed, so the download stays resumable and nothing partial is ever visible under the
     * real name.
     *
     * [progress] is called with the number of bytes copied so far, and may return false to cancel;
     * a cancelled copy leaves [src] intact and returns [DownloadError.Cancelled]. It is not called
     * for the rename path, which moves no bytes.
     *
     * The scratch file is named from [id] rather than from [dst], so two downloads resolving to the
     * same destination name cannot collide on it.
     *
     * Returns null on success, [DownloadError.DiskSpaceError] if the destination lacks space, or
     * [DownloadError.OutputWriteError] for any other failure (including [dst] already existing).
     */
    fun moveFile(
        src: File,
        dst: File,
        ops: MoveOps = MoveOps.Default,
        id: Long = 0,
        replaceExisting: Boolean = false,
        progress: ((Long) -> Boolean)? = null,
    ): DownloadError? {
        if (!src.isFile) {
            Logger.error("XDM", "Move failed, source missing: $src")
            return DownloadError.OutputWriteError
        }
        if (dst.exists() && !replaceExisting) {
            Logger.error("XDM", "Move failed, destination exists: $dst")
            return DownloadError.OutputWriteError
        }
        try {
            ops.atomicMove(src.toPath(), dst.toPath(), replaceExisting)
            return null
        } catch (_: AtomicMoveNotSupportedException) {
            Logger.info("XDM", "Atomic move not possible, copying $src -> $dst")
        } catch (e: Exception) {
            Logger.error("XDM", "Move failed: $src -> $dst", e)
            return DownloadError.OutputWriteError
        }

        val folder = dst.absoluteFile.parentFile
        if (ops.usableSpace(folder) < src.length()) {
            Logger.error("XDM", "Not enough space in $folder for ${src.length()} bytes")
            return DownloadError.DiskSpaceError
        }
        val part = File(folder, "${dst.name}.$id.part")
        return try {
            ops.copy(src.toPath(), part.toPath(), progress)
            if (dst.exists() && !replaceExisting) throw FileAlreadyExistsException(dst.path)
            ops.atomicMove(part.toPath(), dst.toPath(), replaceExisting)
            if (!src.delete()) Logger.error("XDM", "Copied, but could not delete source $src")
            null
        } catch (_: CopyCancelledException) {
            Logger.info("XDM", "Copy cancelled: $src -> $dst")
            part.delete()
            DownloadError.Cancelled
        } catch (e: Exception) {
            Logger.error("XDM", "Copy failed: $src -> $dst", e)
            part.delete()
            DownloadError.OutputWriteError
        }
    }

    fun deleteFolder(folder: String): Result<Unit> {
        return deleteFolder(Paths.get(folder))
    }

    fun deleteFolder(folder: Path): Result<Unit> {
        return runCatching {
            Files.walk(folder).sorted(Comparator.reverseOrder()).use { files ->
                files.forEach { f: Path ->
                    try {
                        Files.delete(f)
                        Logger.info("XDM", "Successfully delete file : $f")
                    } catch (e: IOException) {
                        Logger.error("XDM", "Error deleting temp file: $f ", e)
                    }
                }
            }
        }
    }
}

/** Thrown by [MoveOps.copy] when the progress callback asks to stop. */
class CopyCancelledException : IOException("Copy cancelled")

/** File-system operations used by [FileUtils.moveFile]; replaceable in tests. */
interface MoveOps {
    /** Atomic rename; throws [java.nio.file.AtomicMoveNotSupportedException] across file systems. */
    fun atomicMove(src: Path, dst: Path, replaceExisting: Boolean = false)

    /**
     * Copies [src] to [dst] and flushes it to stable storage before returning. [progress] receives
     * the running byte count and may return false to abort with [CopyCancelledException].
     */
    fun copy(src: Path, dst: Path, progress: ((Long) -> Boolean)? = null)
    fun usableSpace(folder: File): Long

    companion object Default : MoveOps {
        private const val COPY_BUFFER = 1 shl 20

        override fun atomicMove(src: Path, dst: Path, replaceExisting: Boolean) {
            // ATOMIC_MOVE replaces an existing target as part of the same operation, so an
            // overwrite never leaves a window where neither file is present.
            if (replaceExisting) {
                Files.move(
                    src, dst,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                Files.move(src, dst, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
        }

        /**
         * Hand-rolled rather than [Files.copy] so the copy can report progress, be cancelled, and —
         * the part that matters for correctness — be forced to disk before it is renamed into place.
         * Without the force, a power loss can publish a truncated file under the real name, which is
         * worse than failing outright because the user believes the download succeeded.
         */
        override fun copy(src: Path, dst: Path, progress: ((Long) -> Boolean)?) {
            java.io.FileInputStream(src.toFile()).use { input ->
                java.io.FileOutputStream(dst.toFile()).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (progress != null && !progress(copied)) throw CopyCancelledException()
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        }

        override fun usableSpace(folder: File): Long = folder.usableSpace
    }
}
