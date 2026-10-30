// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.storage

import xdm.core.util.getExtension
import xdm.core.util.getFileNameWithoutExtension
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.ClosedChannelException

/**
 * Where finished downloads go: resolves the folder locations recorded in task info (`targetFolder`, a
 * batch's folder) to [StorageFolder]s. Only the user-visible destination goes through here. The data
 * folder (state, task info, keys) and the temp folder (HLS/DASH segments, MKV spool) are app-private and
 * stay on plain `java.io`, on Android too.
 *
 * Desktop uses [LocalStorage]: a location is an absolute path. Android implements this over the Storage
 * Access Framework, where a location is a persisted tree URI plus a relative path inside it, and nothing
 * outside the tree is reachable by path.
 */
interface Storage {
    /** The folder at [location], as stored in task info. Does not touch storage. */
    fun folder(location: String): StorageFolder
}

/**
 * A folder downloads are written into. Every operation names a direct child by [name] only: download
 * code never builds paths, so a provider can map names to documents (SAF has no paths, and finds a
 * child by querying its parent).
 *
 * Operations that can fail throw [IOException] unless stated otherwise.
 */
interface StorageFolder {
    /** Opaque, stable string to persist and later pass to [Storage.folder]. */
    val location: String

    /** The folder on the local file system, or null when it is only reachable through a provider (SAF). */
    val localPath: String?

    /** The [name]d subfolder. Does not touch storage: [create] makes it. */
    fun child(name: String): StorageFolder

    /** Whether the folder exists. */
    fun exists(): Boolean

    /** Creates the folder and any missing parents. True if it exists afterwards; never throws. */
    fun create(): Boolean

    /** Size of the file [name], or -1 when there is no such file. Never throws. */
    fun length(name: String): Long

    fun isFile(name: String): Boolean = length(name) >= 0

    /** Whether anything (file or folder) is named [name] here, so a new file cannot take that name. */
    fun exists(name: String): Boolean = isFile(name)

    /**
     * Opens the file [name] for random-access writing, positioned at 0. With [create] a missing file is
     * created empty (as a sparse file where supported, see [LocalStorage]); without it a missing file
     * throws. An existing file keeps its content.
     */
    fun open(name: String, create: Boolean): RandomAccessOutput

    /**
     * Like [open] with `create`, then sized to [length] without writing the gap: a new file is created
     * sparse where supported (see [LocalStorage]). An existing file keeps its content and is set to [length].
     */
    fun openPresized(name: String, length: Long): RandomAccessOutput =
        open(name, create = true).apply { setLength(length) }

    /**
     * Opens the file [name] for [PositionalOutput] writes from many threads: like [open], or with [length]
     * like [openPresized] (a new file is then created and sized without writing the gap). The default
     * lets the writes take turns on one [RandomAccessOutput]; [LocalStorage] writes in parallel.
     */
    fun openPositional(name: String, create: Boolean, length: Long? = null): PositionalOutput =
        LockedPositionalOutput(if (length != null) openPresized(name, length) else open(name, create))

    /** Reads the file [name] from the start. */
    fun openRead(name: String): InputStream

    /** Deletes the file [name]. True if it is gone afterwards (also when it never existed); never throws. */
    fun delete(name: String): Boolean

    /**
     * Renames the file [from] to [to] in this folder. Never copies. When [to] exists it is replaced if
     * [replace], otherwise [TargetExistsException] is thrown and [from] is untouched.
     */
    fun rename(from: String, to: String, replace: Boolean)

    /** Names of the files in this folder (no subfolders); empty when it does not exist. Never throws. */
    fun listFiles(): List<String>

    /** Deletes this folder if it exists and holds nothing. Never throws. */
    fun deleteIfEmpty()

    /** Bytes that can still be written here, or -1 when unknown. Never throws. */
    fun usableSpace(): Long

    /** Flushes the file [name]'s data to stable storage. */
    fun force(name: String) {
        open(name, create = false).use { it.force() }
    }

    /** Makes renames in this folder durable, where the platform needs it. Best effort; never throws. */
    fun sync() {}

    /** The file [name] in this folder. */
    fun file(name: String): StorageFile = StorageFile(this, name)
}

/** A file named [name] in [folder]: the unit download code passes around instead of a path. */
data class StorageFile(val folder: StorageFolder, val name: String) {
    val exists: Boolean get() = folder.isFile(name)
    fun length(): Long = folder.length(name)
    fun open(create: Boolean): RandomAccessOutput = folder.open(name, create)
    fun openPresized(length: Long): RandomAccessOutput = folder.openPresized(name, length)
    fun openPositional(create: Boolean, length: Long? = null): PositionalOutput =
        folder.openPositional(name, create, length)
    fun delete(): Boolean = folder.delete(name)

    /** The file's local path, or null when it is only reachable through a provider (SAF). */
    val localPath: String? get() = folder.localPath?.let { java.io.File(it, name).absolutePath }

    override fun toString(): String = localPath ?: "${folder.location} :: $name"
}

/**
 * A file opened for writing at any offset: the muxer output (written front to back, then size fields are
 * patched), and each HTTP chunk's view of the shared part file ([PositionalOutput.at]).
 *
 * On Android a SAF document opened with mode "rw" gives a file descriptor; local providers (the
 * Downloads folder) back it with a real file, so a `FileChannel` on that descriptor seeks, and
 * `Os.ftruncate` sets the length.
 */
interface RandomAccessOutput : Closeable {
    fun write(b: ByteArray, off: Int, len: Int)
    fun seek(position: Long)
    fun position(): Long
    fun length(): Long

    /** Grows or shrinks the file; growing must not write the new bytes (keep it sparse where possible). */
    fun setLength(length: Long)

    /** Flushes written data and metadata to stable storage. */
    fun force()
}

/**
 * One handle on a file that many threads write through at once, each at its own offsets: the HTTP part
 * file, which all of a download's chunks share from the first write until the download pauses, fails or
 * completes. Defender scans a file when the handles that wrote to it are closed; a handle per chunk and
 * connection, each closed when its connection ended, got a part file scanned dozens of times per download.
 * An output closes whatever handles it holds together: one scan.
 *
 * Thread safe. Closing it while writes are in flight fails them with an [IOException].
 */
interface PositionalOutput : Closeable {
    /** Writes [len] bytes of [b] from [off] at [position]; returns once they are written, or throws. */
    fun write(position: Long, b: ByteArray, off: Int, len: Int)
    fun length(): Long

    /** Flushes written data and metadata to stable storage. */
    fun force()
}

/**
 * [PositionalOutput] on one [RandomAccessOutput]: each write seeks and writes while holding the lock, so
 * the writes take turns. For storage without positional writes (SAF, Android).
 */
class LockedPositionalOutput(private val out: RandomAccessOutput) : PositionalOutput {
    @Synchronized
    override fun write(position: Long, b: ByteArray, off: Int, len: Int) {
        out.seek(position)
        out.write(b, off, len)
    }

    @Synchronized
    override fun length(): Long = out.length()

    @Synchronized
    override fun force() = out.force()

    @Synchronized
    override fun close() = out.close()
}

/**
 * A writer's own view of this output, starting at [position] and moving forward with each write. Closing
 * the view only closes the view: its next write throws [ClosedChannelException], and the output stays
 * open for the others. A pause or a takeover stops a chunk that way.
 */
fun PositionalOutput.at(position: Long): RandomAccessOutput = PositionalView(this, position)

private class PositionalView(private val out: PositionalOutput, private var position: Long) : RandomAccessOutput {
    @Volatile
    private var closed = false

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw ClosedChannelException()
        out.write(position, b, off, len)
        position += len
    }

    override fun seek(position: Long) {
        this.position = position
    }

    override fun position(): Long = position
    override fun length(): Long = out.length()
    override fun setLength(length: Long) = throw UnsupportedOperationException("A view cannot resize the file")
    override fun force() = out.force()

    override fun close() {
        closed = true
    }
}

/** Writes at the handle's current position; closing the stream closes the handle. */
fun RandomAccessOutput.asOutputStream(): OutputStream = object : OutputStream() {
    override fun write(b: Int) = this@asOutputStream.write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) = this@asOutputStream.write(b, off, len)
    override fun close() = this@asOutputStream.close()
}

/** [name], or `base_1.ext`, `base_2.ext`… — the first for which [taken] is false. */
fun uniqueFileName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val base = getFileNameWithoutExtension(name)
    val ext = getExtension(name) ?: ""
    var n = 0
    var candidate: String
    do {
        candidate = "${base}_${++n}$ext"
    } while (taken(candidate))
    return candidate
}
