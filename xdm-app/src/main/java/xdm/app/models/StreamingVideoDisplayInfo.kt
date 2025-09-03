package xdm.app.models

import xdm.core.util.FormatUtilities
import java.time.LocalDateTime

data class StreamingVideoDisplayInfo(
    var quality: String? = null,
    var size: Long? = null,
    var duration: Long? = null,
    var dateTime: LocalDateTime = LocalDateTime.now(),
    var tabUrl: String? = null,
    var tabId: String? = null,
) {
    val descriptionText: String
        get() {
            return StringBuilder().apply {
                quality?.let { append(it) }
                duration?.let {
                    if (it > 0) {
                        quality?.let { append(" ") }
                        append(FormatUtilities.hms(it.toInt()))
                    }
                }
            }.toString()
        }
}