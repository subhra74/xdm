package xdm.integration

import xdm.core.util.FormatHelper
import java.time.LocalDateTime

data class DetectedVideoInfo(
    val id: Long,
    val name: String,
    val description: String,
    val date: LocalDateTime,
    val tabId: String?,
    /** One MP4 file the extension can show keyframes of (`/preview`): a plain capture or a split track. */
    val previewable: Boolean = false,
)

data class StreamingVideoDisplayInfo(
    var quality: String? = null,
    var size: Long? = null,
    var duration: Long? = null,
    var dateTime: LocalDateTime = LocalDateTime.now(),
    var tabUrl: String? = null,
    var tabId: String? = null,
    /** Video height in pixels for HLS/DASH, shown as e.g. 720p where the size is unknown; null if not given. */
    var height: Int? = null,
) {
    val descriptionText: String
        get() {
            return StringBuilder().apply {
                quality?.let { append(it) }
                duration?.let {
                    if (it > 0) {
                        quality?.let { append(" ") }
                        append(FormatHelper.hms(it.toInt()))
                    }
                }
            }.toString()
        }
}