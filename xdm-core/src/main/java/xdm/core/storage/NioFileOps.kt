// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.storage

import xdm.core.util.Logger
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet

/**
 * [PlatformFileOps] on `java.nio.file`: the desktop (and the JVM tests). The only place in xdm-core that
 * may use `java.nio.file`; see [PlatformFileOps].
 */
object NioFileOps : PlatformFileOps {
    private val ownerOnlyPermissions: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

    private val hasPosix: Boolean get() = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    override fun moveAtomic(src: File, dst: File, replace: Boolean) {
        // ATOMIC_MOVE replaces an existing target on POSIX even without REPLACE_EXISTING.
        if (!replace && dst.exists()) throw TargetExistsException(dst.path)
        translated(src, dst) {
            // ATOMIC_MOVE replaces an existing target as part of the same operation, so an
            // overwrite never leaves a window where neither file is present.
            if (replace) {
                Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    override fun rename(src: File, dst: File, replace: Boolean) {
        try {
            moveAtomic(src, dst, replace)
        } catch (e: CrossDeviceException) {
            // Same folder, so only on exotic file systems: a plain rename still moves no bytes.
            translated(src, dst) {
                if (replace) Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                else Files.move(src.toPath(), dst.toPath())
            }
        }
    }

    override fun forceDirectory(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    override fun createOwnerOnly(file: File) {
        if (!hasPosix) return
        Files.deleteIfExists(file.toPath())
        Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(ownerOnlyPermissions))
    }

    override fun restrictToOwner(file: File) {
        if (!hasPosix) return
        runCatching {
            val perms = if (file.isDirectory) "rwx------" else "rw-------"
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms))
        }.onFailure { Logger.error("XDM", "Unable to restrict $file to its owner", it) }
    }

    /**
     * SPARSE matters on NTFS and is ignored elsewhere. Chunks write far into a preallocated file, and NTFS
     * zero-fills everything below a write past the valid data length, so a normal file gets written almost
     * twice. A sparse file skips the zero-fill.
     *
     * The length comes from writing the last byte, not from `RandomAccessFile.setLength`: NIO has no call
     * that grows a file (`truncate` only shrinks), and the JetBrains Runtime the Linux bundle ships
     * implements `setLength` by writing zeros up to the new length. A write past the end leaves a hole on
     * ext4, btrfs and APFS, and on NTFS once the file is sparse.
     */
    override fun createSparse(file: File, length: Long) {
        if (file.exists()) return
        try {
            Files.newByteChannel(
                file.toPath(),
                EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)
            ).use { if (length > 0) it.position(length - 1).write(ByteBuffer.wrap(ByteArray(1))) }
        } catch (e: IOException) {
            Logger.info("XDM", "Could not create sparse file, using a normal one: $e")
        }
    }

    override fun deleteTree(dir: File): Result<Unit> = runCatching {
        // Files.walk does not follow symbolic links, so a linked folder is unlinked, not emptied.
        Files.walk(dir.toPath()).sorted(Comparator.reverseOrder()).use { files ->
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

    /** Runs [move], reporting the nio exceptions as core's own. */
    private inline fun translated(src: File, dst: File, move: () -> Unit) {
        try {
            move()
        } catch (e: AtomicMoveNotSupportedException) {
            throw CrossDeviceException(src.path, dst.path).apply { initCause(e) }
        } catch (e: FileAlreadyExistsException) {
            throw TargetExistsException(dst.path).apply { initCause(e) }
        }
    }
}
