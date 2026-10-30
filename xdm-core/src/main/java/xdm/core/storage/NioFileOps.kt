// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.storage

import xdm.core.util.Logger
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
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

    /**
     * A `FileChannel` per writer (chunk), every one kept open until the output closes, then closed together.
     *
     * Not one channel for all the chunks: Windows runs one I/O at a time on a synchronous handle, and the
     * JDK's Windows `FileChannel` holds a position lock around each positional write, so the chunks took
     * turns (75 MB/s to an SSD, against 249 MB/s with a handle each). Not an `AsynchronousFileChannel`
     * either: its IOCP threads and their temporary direct buffers cost +47% CPU and +10 MB. Handles kept
     * open and closed together get the file scanned by Defender once (two handles, closed seconds apart:
     * one scan), where a handle per connection, closed when it ended, got it scanned dozens of times.
     *
     * The file is grown by writing its last byte, never `RandomAccessFile.setLength` (see [createSparse]).
     */
    override fun openPositional(file: File, length: Long?): PositionalOutput {
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)
        try {
            if (length != null) {
                val size = channel.size()
                if (size > length) channel.truncate(length)
                else if (size < length) channel.write(ByteBuffer.wrap(ByteArray(1)), length - 1)
            }
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        return HandlePerWriterOutput(file, channel)
    }

    /**
     * [own] serves [write], [force] and [length]; each writer's views write through a channel of their own,
     * opened at the writer's first write. A writer is only ever written from one thread at a time (a chunk's
     * own thread), so its channel is never shared; a view closed under its chunk (pause, takeover) leaves the
     * channel open, and a write still running on it finishes or fails on its own handle.
     */
    private class HandlePerWriterOutput(private val file: File, private val own: FileChannel) : PositionalOutput {
        // Guarded by this.
        private val handles = arrayListOf(own)
        private val byWriter = HashMap<Long, FileChannel>()
        private var closed = false

        override fun write(position: Long, b: ByteArray, off: Int, len: Int) = writeFully(own, position, b, off, len)

        override fun view(position: Long, writer: Long?): RandomAccessOutput = WriterView(writer, position)

        override fun length(): Long = own.size()

        /** One handle is enough: `FlushFileBuffers` and `fsync` flush the file's data, whichever handle wrote it. */
        override fun force() = own.force(true)

        override fun close() {
            val all = synchronized(this) {
                closed = true
                handles.toList()
            }
            var error: IOException? = null
            for (channel in all) {
                try {
                    channel.close()
                } catch (e: IOException) {
                    if (error == null) error = e
                }
            }
            error?.let { throw it }
        }

        /** [writer]'s channel, opened on first use; a new one each time for a one-off (null) writer. */
        @Synchronized
        private fun channelFor(writer: Long?): FileChannel {
            if (closed) throw ClosedChannelException()
            writer?.let { byWriter[it]?.let { channel -> return channel } }
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)
            handles += channel
            if (writer != null) byWriter[writer] = channel
            return channel
        }

        private inner class WriterView(private val writer: Long?, private var position: Long) : RandomAccessOutput {
            @Volatile
            private var closed = false
            private var channel: FileChannel? = null

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed) throw ClosedChannelException()
                val channel = channel ?: channelFor(writer).also { this.channel = it }
                writeFully(channel, position, b, off, len)
                position += len
            }

            override fun seek(position: Long) {
                this.position = position
            }

            override fun position(): Long = position
            override fun length(): Long = this@HandlePerWriterOutput.length()
            override fun setLength(length: Long) = throw UnsupportedOperationException("A view cannot resize the file")
            override fun force() = this@HandlePerWriterOutput.force()

            /** Only the view: its channel stays open until the output closes. */
            override fun close() {
                closed = true
            }
        }
    }

    private fun writeFully(channel: FileChannel, position: Long, b: ByteArray, off: Int, len: Int) {
        val buf = ByteBuffer.wrap(b, off, len)
        var p = position
        while (buf.hasRemaining()) p += channel.write(buf, p)
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
