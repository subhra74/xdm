package xdm.app.models

import kotlinx.serialization.Serializable

@Serializable
data class BrowserDownloadInfo(var id: Long, var url: String) {
    var requestHeaders: Map<String, List<String>>? = null
    var responseHeaders: Map<String, List<String>>? = null
    var fileName: String? = null
    var cookie: String? = null
    var fileSize: Long? = null
    var httpMethod: String? = null
    var userAgent: String? = null
    var tabUrl: String? = null
    var referer: String? = null
    var mimeType: String? = null
    var modifiedDate: Long? = null
}
