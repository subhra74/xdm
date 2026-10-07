package xdm.core.util

fun getFileExtFromUrl(url: String): String? {
    val fileName = FileUtils.getFileName(url)
    return getExtension(fileName)
}

/**
 * The correct output extension (no leading dot) for content downloaded *as-is* over plain HTTP —
 * i.e. with no remux. Because the bytes are saved unchanged, the extension must reflect the native
 * container: MPEG-TS stays "ts" (never "mp4"), WebM stays "webm" (never "mkv"), and audio-only
 * streams keep their own format. Uses the MIME type first, then the sample URL's own extension, and
 * finally defaults to "ts" (a bare HLS media segment with no extension is almost always MPEG-TS).
 */
fun plainDownloadExt(mimeType: String?, sampleUrl: String?): String {
    val m = mimeType?.lowercase() ?: ""
    when {
        m.contains("mp2t") || m.contains("mpegts") || m.contains("video/ts") -> return "ts"
        m.contains("webm") -> return "webm"
        m.contains("matroska") -> return "mkv"
        m.contains("x-flv") || m.contains("/flv") -> return "flv"
        m.startsWith("audio") && m.contains("mp4") -> return "m4a"
        m.contains("mp4") -> return "mp4"
        m.contains("aac") -> return "aac"
        m.contains("mp3") || (m.startsWith("audio") && m.contains("mpeg")) -> return "mp3"
        m.contains("opus") -> return "opus"
        m.contains("ogg") -> return "ogg"
        m.contains("flac") -> return "flac"
        m.contains("wav") -> return "wav"
    }
    // Fall back to the container implied by the file's own extension.
    val urlExt = sampleUrl?.substringBefore('?')?.let { getFileExtFromUrl(it) }?.removePrefix(".")?.lowercase()
    return when (urlExt) {
        "ts", "m2ts", "mts" -> "ts"
        "mp4", "m4s", "m4v", "fmp4" -> "mp4"
        "webm" -> "webm"
        "mkv" -> "mkv"
        "m4a" -> "m4a"
        "aac" -> "aac"
        "mp3" -> "mp3"
        "opus" -> "opus"
        "ogg", "oga" -> "ogg"
        "flac" -> "flac"
        "wav" -> "wav"
        null, "" -> "ts"
        else -> urlExt
    }
}

fun getExtension(file: String): String? {
    val index = file.lastIndexOf(".")
    return if (index > 0) {
        file.substring(index)
    } else {
        null
    }
}

fun getFileNameWithoutExtension(name: String): String {
    val index = name.lastIndexOf(".")
    return if (index > 0) {
        name.substring(0, index)
    } else {
        return name
    }
}