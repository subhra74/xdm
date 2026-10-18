package xdm.core.storage

import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.MoveOps
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.EnumSet

/** [Storage] on the local file system: a location is an absolute path. */
object LocalStorage : Storage {
    override fun folder(location: String): StorageFolder = LocalFolder(File(location).absoluteFile)

    /** The file at [path]. */
    fun file(path: String): StorageFile {
        val f = File(path).absoluteFile
        return LocalFolder(f.parentFile).file(f.name)
    }
}

private class LocalFolder(private val dir: File) : StorageFolder {
    override val location: String get() = dir.path
    override val localPath: String get() = dir.path

    private fun f(name: String) = File(dir, name)

    override fun child(name: String): StorageFolder = LocalFolder(File(dir, name))
    override fun exists() = dir.isDirectory
    override fun create() = dir.isDirectory || dir.mkdirs() || dir.isDirectory
    override fun exists(name: String) = f(name).exists()
    override fun length(name: String): Long = f(name).let { if (it.isFile) it.length() else -1 }

    override fun open(name: String, create: Boolean): RandomAccessOutput {
        val file = f(name)
        if (!create && !file.isFile) throw FileNotFoundException(file.path)
        if (create) createSparse(file)
        return LocalOutput(RandomAccessFile(file, "rw"))
    }

    /**
     * Creates the file as sparse (NTFS; ignored elsewhere). Chunks write far into a preallocated file,
     * and NTFS zero-fills everything below a write past the valid data length, so a normal file gets
     * written almost twice. A sparse file skips the zero-fill.
     */
    private fun createSparse(file: File) {
        if (file.exists()) return
        try {
            Files.newByteChannel(
                file.toPath(),
                EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)
            ).close()
        } catch (e: IOException) {
            Logger.info("XDM", "Could not create sparse file, using a normal one: $e")
        }
    }

    override fun openRead(name: String): InputStream = FileInputStream(f(name))

    override fun delete(name: String): Boolean = f(name).let { it.delete() || !it.exists() }

    override fun rename(from: String, to: String, replace: Boolean) {
        val src = f(from).toPath()
        val dst = f(to).toPath()
        // ATOMIC_MOVE replaces an existing target on POSIX even without REPLACE_EXISTING.
        if (!replace && Files.exists(dst)) throw FileAlreadyExistsException(dst.toString())
        val options = listOfNotNull(StandardCopyOption.REPLACE_EXISTING.takeIf { replace })
        try {
            Files.move(src, dst, *(options + StandardCopyOption.ATOMIC_MOVE).toTypedArray())
        } catch (e: AtomicMoveNotSupportedException) {
            // Same folder, so only on exotic file systems: a plain rename still moves no bytes.
            Files.move(src, dst, *options.toTypedArray())
        }
    }

    override fun listFiles(): List<String> = dir.listFiles { f -> f.isFile }?.map { it.name } ?: emptyList()

    override fun deleteIfEmpty() {
        if (dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete()
    }

    override fun usableSpace(): Long = try {
        // A folder that does not exist yet reports 0: measure the nearest existing parent.
        generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: -1
    } catch (e: SecurityException) {
        -1
    }

    override fun force(name: String) = MoveOps.force(f(name).toPath())

    override fun sync() = FileUtils.syncDirectory(dir.toPath())

    override fun equals(other: Any?) = other is LocalFolder && other.dir == dir
    override fun hashCode() = dir.hashCode()
    override fun toString() = dir.path
}

private class LocalOutput(private val raf: RandomAccessFile) : RandomAccessOutput {
    override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
    override fun seek(position: Long) = raf.seek(position)
    override fun position(): Long = raf.filePointer
    override fun length(): Long = raf.length()
    override fun setLength(length: Long) = raf.setLength(length)
    override fun force() = raf.channel.force(true)
    override fun close() = raf.close()
}
