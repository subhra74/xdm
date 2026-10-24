// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.util

/**
 * Extensions for the well-known MIME types a link may serve: the first is the one added, the rest are
 * names that already match. Generic types (application/octet-stream, text/plain, text/html) are left out
 * on purpose: they say nothing about the file, so a name served with them is never touched.
 */
private class MimeExt(val family: Family, val exts: List<String>)

private enum class Family { IMAGE, DOCUMENT, AUDIO, VIDEO, ARCHIVE, DATA }

private val MIME_EXTS: Map<String, MimeExt> = buildMap {
    fun add(family: Family, vararg exts: String, mimes: List<String>) =
        mimes.forEach { put(it, MimeExt(family, exts.toList())) }

    add(Family.IMAGE, "jpg", "jpeg", "jpe", "jfif", mimes = listOf("image/jpeg", "image/jpg", "image/pjpeg"))
    add(Family.IMAGE, "png", mimes = listOf("image/png", "image/apng"))
    add(Family.IMAGE, "gif", mimes = listOf("image/gif"))
    add(Family.IMAGE, "webp", mimes = listOf("image/webp"))
    add(Family.IMAGE, "avif", mimes = listOf("image/avif"))
    add(Family.IMAGE, "bmp", mimes = listOf("image/bmp", "image/x-ms-bmp"))
    add(Family.IMAGE, "svg", mimes = listOf("image/svg+xml"))
    add(Family.IMAGE, "tif", "tiff", mimes = listOf("image/tiff"))
    add(Family.IMAGE, "ico", mimes = listOf("image/x-icon", "image/vnd.microsoft.icon"))
    add(Family.IMAGE, "heic", mimes = listOf("image/heic"))
    add(Family.IMAGE, "heif", mimes = listOf("image/heif"))

    add(Family.DOCUMENT, "pdf", mimes = listOf("application/pdf"))
    add(Family.DOCUMENT, "doc", "dot", mimes = listOf("application/msword"))
    add(Family.DOCUMENT, "docx", mimes = listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    add(Family.DOCUMENT, "xls", "xlt", mimes = listOf("application/vnd.ms-excel"))
    add(Family.DOCUMENT, "xlsx", mimes = listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    add(Family.DOCUMENT, "ppt", "pps", "pot", mimes = listOf("application/vnd.ms-powerpoint"))
    add(Family.DOCUMENT, "pptx", mimes = listOf("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
    add(Family.DOCUMENT, "odt", mimes = listOf("application/vnd.oasis.opendocument.text"))
    add(Family.DOCUMENT, "ods", mimes = listOf("application/vnd.oasis.opendocument.spreadsheet"))
    add(Family.DOCUMENT, "odp", mimes = listOf("application/vnd.oasis.opendocument.presentation"))
    add(Family.DOCUMENT, "rtf", mimes = listOf("application/rtf", "text/rtf"))
    add(Family.DOCUMENT, "epub", mimes = listOf("application/epub+zip"))

    add(Family.AUDIO, "mp3", mimes = listOf("audio/mpeg", "audio/mp3"))
    add(Family.AUDIO, "m4a", mimes = listOf("audio/mp4", "audio/x-m4a"))
    add(Family.AUDIO, "aac", mimes = listOf("audio/aac"))
    add(Family.AUDIO, "ogg", "oga", "opus", mimes = listOf("audio/ogg"))
    add(Family.AUDIO, "opus", mimes = listOf("audio/opus"))
    add(Family.AUDIO, "flac", mimes = listOf("audio/flac", "audio/x-flac"))
    add(Family.AUDIO, "wav", mimes = listOf("audio/wav", "audio/x-wav", "audio/wave"))
    add(Family.AUDIO, "webm", "weba", mimes = listOf("audio/webm"))

    add(Family.VIDEO, "mp4", "m4v", mimes = listOf("video/mp4"))
    add(Family.VIDEO, "webm", mimes = listOf("video/webm"))
    add(Family.VIDEO, "mkv", mimes = listOf("video/x-matroska"))
    add(Family.VIDEO, "mov", mimes = listOf("video/quicktime"))
    add(Family.VIDEO, "avi", mimes = listOf("video/x-msvideo"))
    add(Family.VIDEO, "3gp", mimes = listOf("video/3gpp"))
    add(Family.VIDEO, "mpg", "mpeg", mimes = listOf("video/mpeg"))
    add(Family.VIDEO, "flv", mimes = listOf("video/x-flv"))
    add(Family.VIDEO, "ts", mimes = listOf("video/mp2t"))

    add(Family.ARCHIVE, "zip", mimes = listOf("application/zip", "application/x-zip-compressed"))
    add(Family.ARCHIVE, "rar", mimes = listOf("application/vnd.rar", "application/x-rar-compressed"))
    add(Family.ARCHIVE, "7z", mimes = listOf("application/x-7z-compressed"))
    add(Family.ARCHIVE, "gz", "tgz", mimes = listOf("application/gzip", "application/x-gzip"))
    add(Family.ARCHIVE, "tar", mimes = listOf("application/x-tar"))

    add(Family.DATA, "json", mimes = listOf("application/json"))
    add(Family.DATA, "xml", mimes = listOf("application/xml", "text/xml"))
    add(Family.DATA, "csv", mimes = listOf("text/csv"))
}

/** Extensions of server-side scripts: the URL's, never the file's. */
private val SCRIPT_EXTS = setOf("php", "asp", "aspx", "jsp", "cgi", "pl", "py", "do", "action", "ashx", "htm", "html")

/** Families whose wrong extension is replaced: the bytes are plainly that type (a CDN serving webp for .jpg). */
private val REPLACEABLE = setOf(Family.IMAGE, Family.AUDIO, Family.VIDEO, Family.DOCUMENT)

private val REAL_EXT = Regex("[a-z][a-z0-9]{0,4}")

/**
 * [name] with an extension matching [contentType], for a file saved as-is.
 *
 * - Generic or unknown type, or the extension already one of the type's: [name] unchanged.
 * - No extension, a script's (`.php`) or one that is not an extension at all (`IMG.08.01`): the type's is added.
 * - With [replace], an extension of another type of the same kind (`.jpg` served as image/webp): replaced.
 * - Any other extension (`.jar` served as application/zip): kept, the name knows better.
 */
fun fixExtension(name: String, contentType: String?, replace: Boolean = true): String {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return name
    val target = MIME_EXTS[mime] ?: return name
    val ext = getExtension(name)?.removePrefix(".")?.lowercase()
    if (ext != null && ext in target.exts) return name
    val add = target.exts[0]
    if (ext == null || ext in SCRIPT_EXTS || !REAL_EXT.matches(ext)) return "$name.$add"
    if (replace && target.family in REPLACEABLE && MIME_EXTS.values.any { it.family == target.family && ext in it.exts }) {
        return "${getFileNameWithoutExtension(name)}.$add"
    }
    return name
}
