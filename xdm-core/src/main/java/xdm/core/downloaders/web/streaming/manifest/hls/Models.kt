package xdm.core.downloaders.web.streaming.manifest.hls


import java.net.URI

data class HlsPlaylist(
    val mediaSegments: List<HlsMediaSegment>,
    val encrypted: Boolean = false,
    val hasByteRange: Boolean = false,
    val totalDuration: Double = 0.0,
    val keyFrameOnly: Boolean = false,
    val hasInitSection: Boolean = false,
    val version: Int = 0
)

data class HlsMasterPlaylist(
    val videoPlaylist: URI? = null,
    val audioPlaylist: URI? = null,
    val attributes: Map<String, String>
)

data class HlsMediaSegment(
    val url: String,
    val byteRange: Pair<Long, Long>? = null,
    val duration: Double = 0.0,
    val keyUrl: URI? = null,
    val iv: String? = null
)