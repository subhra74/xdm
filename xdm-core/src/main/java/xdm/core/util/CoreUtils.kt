package xdm.core.util

import java.net.URLDecoder
import java.security.SecureRandom

object CoreUtils {
    private val rnd: SecureRandom = SecureRandom()
    fun uniqueId(): Long {
        synchronized(rnd) {
            return rnd.nextLong()
        }
    }

    fun deriveFileName(url: String, contentType: String?, contentDisposition: String?): String {
        //TODO: use proper file name from attachment if available
        return getFileName(url)
    }

    fun getFileName(uri: String): String {
        try {
            if (uri == "/" || uri.isEmpty()) {
                return "FILE"
            }
            val x = uri.lastIndexOf("/")
            var path: String = uri
            if (x > -1) {
                path = uri.substring(x)
            }
            val idx = path.indexOf("?")
            if (idx > -1) {
                path = path.substring(0, idx)
            }
            path = FileUtils.decodeFileName(path)
            if (path.isEmpty()) return "FILE"
            if (path == "/") return "FILE"
            return FileUtils.sanitizeFileName(path)
        } catch (e: java.lang.Exception) {
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
}