package xdm.core.util

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Crash-safe storage for the app's small binary files (config, download lists, schedule, `.state`,
 * `.info`, `.keys`).
 *
 * A save writes `<name>.bak1`, appends [FOOTER] once the writer has finished, syncs it, moves the
 * current `<name>` to `<name>.bak2`, then moves `<name>.bak1` into place. A read uses the newest
 * copy that ends with the footer (so a copy cut short by a crash or a failed writer is skipped), and
 * hands the reader only that copy's bytes, so the reader runs once, on complete data.
 */
object AtomicIO {
    /** Marks a completely written file. Not a checksum: it does not detect corruption inside a file. */
    @PublishedApi
    internal val FOOTER = "XDM-END!".toByteArray(Charsets.US_ASCII)

    /**
     * Writes [fileName] via a temp file and rename. With [ownerOnly], the file is created readable and
     * writable by the owner only on POSIX systems (the rename keeps the permissions); use it for files
     * holding cookies, headers or keys. On Windows the per-user profile folder already restricts access.
     *
     * Returns failure if the writer throws or the file could not be moved into place; the previous
     * version then stays readable.
     */
    inline fun writeTransacted(
        fileName: String,
        folder: String,
        ownerOnly: Boolean = false,
        writer: (out: DataOutputStream) -> Unit
    ): Result<Unit> {
        val tmp1 = File(folder, "$fileName.bak1")
        return runCatching {
            try {
                if (ownerOnly) createOwnerOnly(tmp1)
                FileOutputStream(tmp1).use { fs ->
                    val out = DataOutputStream(BufferedOutputStream(fs, 64 * 1024))
                    writer.invoke(out)
                    out.write(FOOTER)
                    out.flush()
                    fs.fd.sync()
                }
            } catch (e: Throwable) {
                tmp1.delete()
                throw e
            }
            commit(tmp1, File(folder, fileName), File(folder, "$fileName.bak2"))
        }
    }

    /** Keeps the current file as `.bak2` (best effort), then atomically moves the new file into place. */
    @PublishedApi
    internal fun commit(tmp: File, finalFile: File, backup: File) {
        if (finalFile.isFile) {
            runCatching { Files.move(finalFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                .onFailure { Logger.error("XDM", "Unable to keep backup $backup", it) }
        }
        try {
            Files.move(tmp.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private val ownerOnlyPermissions: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rw-------")

    @PublishedApi
    internal fun createOwnerOnly(file: File) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return
        Files.deleteIfExists(file.toPath())
        Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(ownerOnlyPermissions))
    }

    inline fun <T> readTransacted(
        fileName: String,
        folder: String,
        reader: (out: DataInputStream) -> T
    ): Result<T> {
        return runCatching {
            DataInputStream(ByteArrayInputStream(readComplete(folder, fileName))).use(reader)
        }
    }

    /**
     * The contents (without footer) of the newest completely written copy, checked in the order
     * `<name>`, `<name>.bak1` (complete only if a crash happened between the two moves), `<name>.bak2`.
     * If no copy has the footer, the files predate it: the first existing one is returned as is.
     */
    @PublishedApi
    internal fun readComplete(folder: String, fileName: String): ByteArray {
        val candidates = listOf(fileName, "$fileName.bak1", "$fileName.bak2")
            .map { File(folder, it) }
            .filter { it.isFile }
        if (candidates.isEmpty()) throw FileNotFoundException(File(folder, fileName).path)

        for (file in candidates) {
            val bytes = file.readBytes()
            if (hasFooter(bytes)) {
                if (file.name != fileName) Logger.info("XDM", "Using ${file.name}: $fileName is missing or incomplete")
                return bytes.copyOf(bytes.size - FOOTER.size)
            }
        }
        return candidates.first().readBytes()
    }

    private fun hasFooter(bytes: ByteArray): Boolean {
        if (bytes.size < FOOTER.size) return false
        val start = bytes.size - FOOTER.size
        return FOOTER.indices.all { bytes[start + it] == FOOTER[it] }
    }
}
