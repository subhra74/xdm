package xdm.core.util

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

object AtomicIO {
    /**
     * Writes [fileName] via a temp file and rename. With [ownerOnly], the file is created readable and
     * writable by the owner only on POSIX systems (the rename keeps the permissions); use it for files
     * holding cookies, headers or keys. On Windows the per-user profile folder already restricts access.
     */
    inline fun writeTransacted(
        fileName: String,
        folder: String,
        ownerOnly: Boolean = false,
        writer: (out: DataOutputStream) -> Unit
    ): Result<Unit> {
        val tmp1 = File(folder, "$fileName.bak1")
        val finalFile = File(folder, fileName)
        val tmp3 = File(folder, "$fileName.bak2")
        return runCatching {
            if (ownerOnly) createOwnerOnly(tmp1)
            FileOutputStream(tmp1).use { fs ->
                DataOutputStream(fs).use { ds ->
                    writer.invoke(ds)
                    fs.fd.sync()
                }
            }

            // Very unlikely
            if (tmp3.exists()) {
                tmp3.delete()
            }
            if (finalFile.exists()) {
                finalFile.renameTo(tmp3)
            }
            tmp1.renameTo(finalFile)
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
        val tmp1 = File(folder, "$fileName.bak1")
        val finalFile = File(folder, fileName)
        val tmp3 = File(folder, "$fileName.bak2")
        return runCatching {
            val fileToRead = if (tmp1.exists()) tmp1 else if (finalFile.exists()) finalFile else tmp3
            DataInputStream(FileInputStream(fileToRead)).use(reader)
        }
    }
}