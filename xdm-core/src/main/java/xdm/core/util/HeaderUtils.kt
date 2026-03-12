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
    return headers?.let { getHeader("content-length", headers)?.toLong() }
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

private fun getExtendedContentDisposition(header: String): String? {
    try {
        val arr = header.split(";")
        for (str in arr) {
            if (str.contains("filename*")) {
                val index = str.lastIndexOf("'")
                if (index > 0) {
                    val st = str.substring(index + 1)
                    return decodeFileName(st)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

fun getNameFromContentDisposition(header: String?): String? {
    try {
        if (header == null) return null
        val headerLow = header.lowercase(Locale.getDefault())
        if (headerLow.startsWith("attachment") || headerLow.startsWith("inline")) {
            val name = getExtendedContentDisposition(header)
            if (name != null) return name
            val arr = header.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (i in arr.indices) {
                val str = arr[i].trim { it <= ' ' }
                if (str.lowercase(Locale.getDefault()).startsWith("filename")) {
                    val index = str.indexOf('=')
                    val file = str.substring(index + 1).replace("\"", "").trim { it <= ' ' }
                    return try {
                        decodeFileName(file)
                    } catch (e: Exception) {
                        file
                    }
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