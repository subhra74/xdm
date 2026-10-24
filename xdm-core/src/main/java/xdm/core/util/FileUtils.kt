// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.util

import xdm.core.downloaders.DownloadError
import xdm.core.storage.CrossDeviceException
import xdm.core.storage.PlatformFiles
import xdm.core.storage.TargetExistsException
import xdm.core.storage.uniqueFileName
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder

object FileUtils {
    /**
     * Makes [file] readable only by the current user (`rwx------` for a folder, `rw-------` for a
     * file). POSIX only; on Windows the user profile's inherited ACL already does this. Best effort:
     * a failure is logged and otherwise ignored.
     */
    fun restrictToOwner(file: File) = PlatformFiles.ops.restrictToOwner(file)

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

    fun getUniqueFileName(folder: String?, fileName: String): String =
        uniqueFileName(fileName) { File(folder, it).exists() }

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
     * Moves [src] to [dst] without ever overwriting an existing file or leaving a partial [dst].
     *
     * Flushes [src] to disk, then tries an atomic rename. Without the flush a same-volume rename can
     * publish a file whose data was still in the OS cache, so a power loss leaves zeros under the real
     * name. A failed flush fails the move. Across file systems (where a rename is impossible) it checks free
     * space, copies to a scratch file next to [dst], flushes it to disk, renames it into place on the
     * destination volume, then deletes [src]. On any failure [src] is left untouched and the scratch
     * file is removed, so the download stays resumable and nothing partial is ever visible under the
     * real name.
     *
     * [progress] is called with the number of bytes copied so far, and may return false to cancel;
     * a cancelled copy leaves [src] intact and returns [DownloadError.Cancelled]. It is not called
     * for the rename path, which moves no bytes.
     *
     * [phase] is told when each slow step starts (see [MovePhase]) and may return false to cancel
     * before that step runs, with the same result as a cancelled copy. [MovePhase.FINALIZING] is the
     * last point a cross-volume move can be cancelled; once it is accepted the move runs to the end.
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
        phase: ((MovePhase) -> Boolean)? = null,
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
        if (phase != null && !phase(MovePhase.PREPARING)) return DownloadError.Cancelled
        try {
            ops.force(src)
        } catch (e: Exception) {
            Logger.error("XDM", "Could not flush $src before moving it", e)
            return DownloadError.OutputWriteError
        }
        try {
            ops.atomicMove(src, dst, replaceExisting)
            syncDirectory(dst.absoluteFile.parentFile, ops)
            return null
        } catch (_: CrossDeviceException) {
            Logger.info("XDM", "Atomic move not possible, copying $src -> $dst")
        } catch (e: Exception) {
            Logger.error("XDM", "Move failed: $src -> $dst", e)
            return DownloadError.OutputWriteError
        }

        copyFile(src, dst, ops, id, replaceExisting, phase, progress)?.let { return it }
        if (!src.delete()) Logger.error("XDM", "Copied, but could not delete source $src")
        return null
    }

    /**
     * Copies [src] to [dst] durably and leaves [src] alone: checks free space for the whole file, copies
     * into the scratch file `<dst>.<id>.part`, forces it to disk and renames it into place, so [dst] only
     * ever appears complete. [phase] and [progress] work as in [moveFile]; a cancel or failure deletes
     * the scratch file. Returns null on success, [DownloadError.Cancelled], [DownloadError.DiskSpaceError]
     * or [DownloadError.OutputWriteError].
     */
    fun copyFile(
        src: File,
        dst: File,
        ops: MoveOps = MoveOps.Default,
        id: Long = 0,
        replaceExisting: Boolean = false,
        phase: ((MovePhase) -> Boolean)? = null,
        progress: ((Long) -> Boolean)? = null,
    ): DownloadError? {
        val folder = dst.absoluteFile.parentFile
        if (ops.usableSpace(folder) < src.length()) {
            Logger.error("XDM", "Not enough space in $folder for ${src.length()} bytes")
            return DownloadError.DiskSpaceError
        }
        if (phase != null && !phase(MovePhase.COPYING)) return DownloadError.Cancelled
        val part = File(folder, "${dst.name}.$id.part")
        return try {
            ops.copy(src, part, progress)
            if (phase != null && !phase(MovePhase.FINALIZING)) throw CopyCancelledException()
            ops.force(part)
            if (dst.exists() && !replaceExisting) throw TargetExistsException(dst.path)
            ops.atomicMove(part, dst, replaceExisting)
            syncDirectory(folder, ops)
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

    /**
     * Best effort: flushes [dir]'s entries so a rename into it survives a power loss. On Linux a
     * rename is only durable once its folder is synced; NTFS journals renames, and Java cannot open
     * a folder there, so Windows is skipped. Never throws: by the time it runs the rename has
     * happened and cannot be undone, so a failure is only logged.
     */
    fun syncDirectory(dir: File, ops: MoveOps = MoveOps.Default) {
        if (PlatformUtils.isWindows) return
        try {
            ops.forceDirectory(dir)
        } catch (e: Exception) {
            Logger.error("XDM", "Could not flush folder $dir", e)
        }
    }

    /** Deletes [folder] and everything under it without following symbolic links. */
    fun deleteFolder(folder: String): Result<Unit> = deleteFolder(File(folder))

    fun deleteFolder(folder: File): Result<Unit> = PlatformFiles.ops.deleteTree(folder)
}

/** Thrown by [MoveOps.copy] when the progress callback asks to stop. */
class CopyCancelledException : IOException("Copy cancelled")

/** The slow steps of [FileUtils.moveFile], in order. A same-volume move only goes through [PREPARING]. */
enum class MovePhase {
    /** Flushing the finished file to disk; cannot be interrupted, but a cancel is honoured right after. */
    PREPARING,

    /** Copying to the other volume, reported through the progress callback; cancellable throughout. */
    COPYING,

    /** Flushing the copy, renaming it into place and deleting the source. Not cancellable. */
    FINALIZING,
}

/** File-system operations used by [FileUtils.moveFile]; replaceable in tests. */
interface MoveOps {
    /** Atomic rename; throws [CrossDeviceException] across file systems. */
    fun atomicMove(src: File, dst: File, replaceExisting: Boolean = false)

    /**
     * Copies [src] to [dst]. [progress] receives the running byte count and may return false to abort
     * with [CopyCancelledException]. Does not flush: [FileUtils.moveFile] forces the copy itself, so it
     * can report that step separately.
     */
    fun copy(src: File, dst: File, progress: ((Long) -> Boolean)? = null)
    fun usableSpace(folder: File): Long

    /**
     * Flushes [file]'s data and metadata to stable storage. Opened for writing because Windows needs
     * write access to flush; metadata included because a sparse file allocates clusters as data arrives.
     */
    fun force(file: File) {
        // "rw" would create a missing file, where flushing one that is not there must fail.
        if (!file.isFile) throw FileNotFoundException(file.path)
        RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    /** Flushes [dir]'s entries (Unix only; see [FileUtils.syncDirectory]). */
    fun forceDirectory(dir: File) = PlatformFiles.ops.forceDirectory(dir)

    companion object Default : MoveOps {
        private const val COPY_BUFFER = 1 shl 20

        override fun atomicMove(src: File, dst: File, replaceExisting: Boolean) =
            PlatformFiles.ops.moveAtomic(src, dst, replaceExisting)

        /**
         * Hand-rolled rather than `Files.copy` so the copy can report progress and be cancelled. The
         * caller forces the result to disk before renaming it into place: without that, a power loss
         * can publish a truncated file under the real name, which is worse than failing outright
         * because the user believes the download succeeded.
         */
        override fun copy(src: File, dst: File, progress: ((Long) -> Boolean)?) {
            java.io.FileInputStream(src).use { input ->
                java.io.FileOutputStream(dst).use { output ->
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
                }
            }
        }

        override fun usableSpace(folder: File): Long = folder.usableSpace
    }
}
