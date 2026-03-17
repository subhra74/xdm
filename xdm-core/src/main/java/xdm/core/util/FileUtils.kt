package xdm.core.util

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.use

object FileUtils {
    private val invalidChars = setOf('/', '\\', '"', '?', '*', '<', '>', ':', '|')

    fun sanitizeFileName(name: String?): String? {
        if (name == null) return null
        val arr = name.toCharArray()
        for (i in arr.indices) {
            if (invalidChars.contains(arr[i])) {
                arr[i] = '_'
            }
        }
        return String(arr)
    }

    fun getUniqueFileName(folder: String?, fileName: String): String {
        var f = File(folder, fileName)
        var ext = getExtension(fileName)
        val name = getFileNameWithoutExtension(fileName)
        if (ext == null) {
            ext = ""
        }
        var c = 0
        while (f.exists()) {
            c++
            f = File(folder, name + "_" + c + ext)
        }
        return if (c == 0) fileName else name + "_" + c + ext
    }

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

    fun deleteFolder(folder: String): Result<Unit> {
        return deleteFolder(Paths.get(folder))
    }

    fun deleteFolder(folder: Path): Result<Unit> {
        return runCatching {
            Files.walk(folder).sorted(Comparator.reverseOrder()).use { files ->
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
    }
}
