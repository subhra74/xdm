package xdm.core.downloaders.web.streaming.manifest.dash


import java.net.URI

data class MpdEntry(val video: Representation?, val audio: Representation?)

data class Representation(
    val width: Int,
    val height: Int,
    val codec: String,
    val bandwidth: Long,
    val duration: Long,
    val segments: List<URI>,
    val mimeType: String,
    val language: String
)