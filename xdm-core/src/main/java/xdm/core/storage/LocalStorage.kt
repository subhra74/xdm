package xdm.core.storage

import xdm.core.util.FileUtils
import xdm.core.util.MoveOps
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.RandomAccessFile

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
        // Sparse on NTFS: see NioFileOps.createSparse.
        if (create) PlatformFiles.ops.createSparse(file, 0)
        return LocalOutput(RandomAccessFile(file, "rw"))
    }

    override fun openPresized(name: String, length: Long): RandomAccessOutput {
        val file = f(name)
        PlatformFiles.ops.createSparse(file, length)
        // A file that was already there, or a presize that failed: size it with setLength.
        return LocalOutput(RandomAccessFile(file, "rw")).apply { if (length() != length) setLength(length) }
    }

    override fun openRead(name: String): InputStream = FileInputStream(f(name))

    override fun delete(name: String): Boolean = f(name).let { it.delete() || !it.exists() }

    override fun rename(from: String, to: String, replace: Boolean) =
        PlatformFiles.ops.rename(f(from), f(to), replace)

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

    override fun force(name: String) = MoveOps.force(f(name))

    override fun sync() = FileUtils.syncDirectory(dir)

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
