// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.storage

import xdm.core.util.Logger
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

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

    /**
     * An [AsynchronousFileChannel], each write waited for. Not a shared `FileChannel`: on Windows its
     * positional writes take a lock (the JDK saves and restores the file pointer around each one), so the
     * chunks write one at a time, measured 14–28% slower on an HDD than a handle per chunk. Overlapped
     * writes on Windows, and `pwrite` from the JDK's pool elsewhere, need no lock.
     *
     * The file is grown by writing its last byte, never `RandomAccessFile.setLength` (see [createSparse]).
     */
    override fun openPositional(file: File, length: Long?): PositionalOutput {
        val channel = AsynchronousFileChannel.open(file.toPath(), StandardOpenOption.WRITE)
        try {
            if (length != null) {
                val size = channel.size()
                if (size > length) channel.truncate(length)
                else if (size < length) awaitWrite(channel.write(ByteBuffer.wrap(ByteArray(1)), length - 1))
            }
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        return AsyncPositionalOutput(channel)
    }

    private class AsyncPositionalOutput(private val channel: AsynchronousFileChannel) : PositionalOutput {
        override fun write(position: Long, b: ByteArray, off: Int, len: Int) {
            val buf = ByteBuffer.wrap(b, off, len)
            var p = position
            while (buf.hasRemaining()) p += awaitWrite(channel.write(buf, p))
        }

        override fun length(): Long = channel.size()
        override fun force() = channel.force(true)
        override fun close() = channel.close()
    }

    /**
     * Waits for a write to finish, and reports its failure as the [IOException] it is (a close while it ran
     * is an [java.nio.channels.AsynchronousCloseException]). An interrupt does not end the wait: the write
     * would still be running, so returning early could let the caller count bytes that are not written yet.
     * The interrupt is restored once the write is done.
     */
    private fun awaitWrite(write: Future<Int>): Int {
        var interrupted = false
        try {
            while (true) {
                try {
                    return write.get()
                } catch (e: InterruptedException) {
                    interrupted = true
                } catch (e: ExecutionException) {
                    val cause = e.cause
                    throw cause as? IOException ?: IOException(cause)
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
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
