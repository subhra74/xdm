package xdm.core.util

import xdm.core.util.FileUtils.decodeFileName
import java.text.SimpleDateFormat
import java.util.*

private val URL_PATTERN by lazy { Regex("https?://([^/:]*)/?") }

fun getHeaders(key: String, headers: Map<String, List<String>>?): List<String>? {
    val keyName = headers?.keys?.find { StringUtils.equalsIgnoreCase(it, key) }
    return keyName?.let { return headers[keyName] }
}

fun getHeader(key: String, headers: Map<String, List<String>>?): String? {
    val values = getHeaders(key, headers)
    return values?.isNotEmpty()?.let { values[0] }
}


fun getContentLength(headers: Map<String, List<String>>?): Long? {
    // A malformed value is no length at all; it must not throw at the caller.
    return headers?.let { getHeader("content-length", headers)?.toLongOrNull() }
}

private val fmt by lazy {
    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }
}

fun getModifiedDate(headers: Map<String, List<String>>?): Long? {
    try {
        return getHeader("last-modified", headers)?.let { fmt.parse(it).time }
    } catch (ex: Exception) {
        Logger.info(ex)
    }
    return null
}

fun getRetryDelay(retryAfter: String?, defaultDelay: Long = 5): Long {
    if (retryAfter != null) {
        try {
            // First, try to parse as a delta-seconds value
            return retryAfter.toLong()
        } catch (e: NumberFormatException) {
            // If it's not a number, try to parse as an HTTP-date
            try {
                val date = fmt.parse(retryAfter)
                val delaySeconds = (date.time - System.currentTimeMillis()) / 1000
                return if (delaySeconds > 0) delaySeconds else 0
            } catch (ex: Exception) {
                Logger.error("Failed to parse Retry-After header as date: $retryAfter", ex)
            }
        }
    }
    return defaultDelay
}

fun getFileName(
    oldName: String,
    keepFileName: Boolean,
    url: String,
    contentDisposition: String? = null,
    contentType: String? = null
): String {
    val newFileName = getFileName(url, contentDisposition, contentType)
    if (!keepFileName) {
        return newFileName
    }

    if (newFileName.endsWith(".html") && !oldName.endsWith(".html")) {
        return "$oldName.html"
    }
    return oldName
}

// Parses the RFC 5987/6266 extended form: filename*=charset'lang'percent-encoded-value
private fun getExtendedContentDisposition(header: String): String? {
    try {
        for (segment in header.split(";")) {
            val str = segment.trim { it <= ' ' }
            if (!str.lowercase(Locale.getDefault()).startsWith("filename*")) continue
            val eq = str.indexOf('=')
            if (eq < 0) continue
            var value = str.substring(eq + 1).trim { it <= ' ' }
            // Strip the leading charset'lang' prefix (if present) up to the last quote.
            val firstQuote = value.indexOf('\'')
            val lastQuote = value.lastIndexOf('\'')
            if (firstQuote in 0 until lastQuote) {
                value = value.substring(lastQuote + 1)
            }
            value = value.replace("\"", "").trim { it <= ' ' }
            if (value.isEmpty()) continue
            val name = decodeFileName(value)
            if (name.isNotEmpty()) return name
        }
    } catch (e: Exception) {
        Logger.info(e)
    }
    return null
}

fun getNameFromContentDisposition(header: String?): String? {
    try {
        if (header == null) return null
        val headerLow = header.lowercase(Locale.getDefault())
        if (headerLow.startsWith("attachment") || headerLow.startsWith("inline")) {
            // RFC 6266: prefer the extended (filename*) form when present.
            getExtendedContentDisposition(header)?.let { return it }
            val arr = header.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (i in arr.indices) {
                val str = arr[i].trim { it <= ' ' }
                val low = str.lowercase(Locale.getDefault())
                // Skip the extended form here; it is handled above.
                if (low.startsWith("filename") && !low.startsWith("filename*")) {
                    val index = str.indexOf('=')
                    if (index < 0) continue
                    val file = str.substring(index + 1).replace("\"", "").trim { it <= ' ' }
                    if (file.isEmpty()) continue
                    val name = try {
                        decodeFileName(file)
                    } catch (e: Exception) {
                        file
                    }
                    if (name.isNotEmpty()) return name
                }
            }
        }
    } catch (e: Exception) {
        //NOOP
    }
    return null
}

fun getFileName(url: String, contentDisposition: String? = null, contentType: String? = null): String {
    getNameFromContentDisposition(contentDisposition)?.let { return it }
    FileUtils.getFileName(url)?.let {
        if (StringUtils.containsIgnoreCase(contentType, "text/html")) {
            if (it.endsWith(".html")) {
                return it
            }
            return "$it.html"
        }
        return it
    }
    return "FILE"
}

//fun getFileName(uri: String?): String {
//    try {
//        if (uri == null) return "FILE"
//        if (uri == "/" || uri.isEmpty()) {
//            return "FILE"
//        }
//        var str = uri
//        var index = str.indexOf("?")
//        if (index != -1) {
//            str = str.substring(0, index)
//        }
//        if (str.endsWith("/")) {
//            return getFileNameFromUrlHost(str)
//        }
//
//        index = str.lastIndexOf("/")
//        if(index!=-1){
//            return
//        }
//        var path: String = uri
//        if (x > -1) {
//            path = uri.substring(x)
//        }
//        val qindex = path.indexOf("?")
//        if (qindex > -1) {
//            path = path.substring(0, qindex)
//        }
//        path = FileUtils.decodeFileName(path)
//        if (path.isEmpty()) return "FILE"
//        if (path == "/") return "FILE"
//        return FileUtils.sanitizeFileName(path)
//    } catch (e: Exception) {
//        Logger.log(e)
//        return "FILE"
//    }
//}
//
//fun getFileNameFromUrlHost(url: String): String {
//    URL_PATTERN.find(url)?.let {
//        if (it.groupValues.size > 1) return it.groupValues[1]
//    }
//    return "FILE"
//}