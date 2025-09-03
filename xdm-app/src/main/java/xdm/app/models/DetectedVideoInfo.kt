package xdm.app.models

import java.time.LocalDateTime

data class DetectedVideoInfo(
    val id: Long,
    val name: String,
    val description: String,
    val date: LocalDateTime,
    val tabId: String?
)