package xdm.core.util

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object AtomicIO {
    inline fun writeTransacted(
        fileName: String,
        folder: String,
        writer: (out: DataOutputStream) -> Unit
    ): Result<Unit> {
        val tmp1 = File(folder, "$fileName.bak1")
        val finalFile = File(folder, fileName)
        val tmp3 = File(folder, "$fileName.bak2")
        return runCatching {
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