package xdm.app

import kotlinx.serialization.Serializable

@Serializable
data class AppConfigData(
    var tempDir: String,
    var fileExtList: List<String> = mutableListOf(
        "3GP", "7Z", "AVI", "BZ2", "DEB", "DOC", "DOCX", "EXE", "ISO", "DMG",
        "MSI", "PDF", "PPT", "PPTX", "RAR", "RPM", "XLS", "XLSX", "SIT", "SITX", "TAR",
        "JAR", "ZIP", "XZ", "MP4", "GZ", "PT", "GGUF", "SAFETENSORS"
    ),
    var blockedHosts: List<String> = mutableListOf("update.microsoft.com", "windowsupdate.com", "thwawte.com"),
    var videoExtList: List<String> = mutableListOf(
        "MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
        "MOV", "MPG", "MPEG", "OPUS", "MPD"
    ),
    var lang: String = "en"
)

enum class OS {
    Windows,
    Linux,
    MacOS
}

enum class MessageBoxResult {
    YES,
    CANCEL,
    YES_WITH_SELECTION
}

interface ListChangeListener {
    fun listChanged()
    fun listItemUpdated(id: Long)
}

data class QueueItem(val id: Long, val resume: Boolean)
/** File counts of a running batch, shown in its progress window. */
data class BatchFileCounts(val done: Int, val failed: Int, val total: Int)

/** One link offered in the batch dialog. */
data class BatchRequestItem(
    val url: String,
    /** The name the page gave the link, if any; otherwise one is taken from the URL. */
    val fileName: String? = null,
    /** Index into [BatchRequest.cookies], or -1 for none. */
    val cookieGroup: Int = -1,
    val knownSize: Long? = null,
)

/**
 * Links for the batch dialog: from the browser's "Download all" (with the page, headers and cookies
 * the browser would send) or from the clipboard (URLs only).
 */
data class BatchRequest(
    val items: List<BatchRequestItem>,
    val fromBrowser: Boolean,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    /** Sent with every link: User-Agent and Referer. */
    val headers: Map<String, List<String>>? = null,
    /** Distinct Cookie header values, referenced by [BatchRequestItem.cookieGroup]. */
    val cookies: List<String> = emptyList(),
)
