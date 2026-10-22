package xdm.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * One audio or video track of a split-track video, recognised from the URL of a CDN request the
 * browser made while playing it. See docs/site-video-detection.md.
 */
data class SplitTrackUrl(
    /** Identifies the file, whatever range of it this request was for. */
    val trackKey: String,
    /** The whole file. */
    val url: String,
    /** Groups the audio and video tracks of one video. */
    val assetKey: String,
    /** What the URL says; the file's own `hdlr` has the last word. */
    val audio: Boolean,
    /** A quality rank from the URL, when it carries one: only tracks that beat the best seen are probed. */
    val rank: Long? = null,
    /** An ad: recognised, so nothing else picks it up, but never offered. */
    val ad: Boolean = false,
)

/**
 * Facebook, Instagram and Threads: `*.fbcdn.net` serves each track as one file, read in pieces with
 * `bytestart`/`byteend` query parameters. The `efg` parameter is base64 JSON naming the encoding
 * (`vencode_tag`, ending in `_audio` for audio, carrying `.ad.`/`-ads` for ads), the asset both tracks
 * belong to (`xpv_asset_id`) and the bitrate.
 */
object MetaCdnRule {
    private val json = Json { ignoreUnknownKeys = true }

    fun match(url: String): SplitTrackUrl? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.endsWith(".fbcdn.net")) return null
        val rawQuery = uri.rawQuery ?: return null
        val params = rawQuery.split('&').filter { it.isNotEmpty() }
        if (params.none { it.startsWith("bytestart=") || it.startsWith("byteend=") }) return null
        val efg = params.firstOrNull { it.startsWith("efg=") }?.substringAfter('=') ?: return null
        val info = decodeEfg(efg) ?: return null
        val tag = info["vencode_tag"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!tag.contains("dash")) return null
        val asset = (info["xpv_asset_id"]?.jsonPrimitive?.contentOrNull
            ?: info["video_id"]?.jsonPrimitive?.contentOrNull)?.takeIf { it != "null" } ?: return null
        val kept = params.filterNot { it.startsWith("bytestart=") || it.startsWith("byteend=") }
        val whole = url.substringBefore('?') + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        return SplitTrackUrl(
            trackKey = uri.rawPath,
            url = whole,
            assetKey = "meta:$asset",
            audio = tag.endsWith("audio"),
            rank = info["bitrate"]?.jsonPrimitive?.longOrNull,
            ad = tag.contains(".ad.") || tag.contains("-ads"),
        )
    }

    private fun decodeEfg(raw: String): JsonObject? {
        val value = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(value.trimEnd('=')) }.getOrNull()
            ?: runCatching { Base64.getDecoder().decode(value) }.getOrNull()
            ?: return null
        return runCatching { json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject }.getOrNull()
    }
}

/**
 * bilibili.com: each DASH track is one file, `/upgcxcode/xx/yy/<cid>/<cid>-1-<n>.m4s` on whichever
 * mirror host the player was given, read with ordinary `Range:` headers. Both tracks of a video share
 * the cid; `<n>` in 302xx is an audio track.
 */
object BilibiliCdnRule {
    private val PATH = Regex("""^/upgcxcode/\d+/\d+/(\d+)/\1-\d+-(\d+)\.m4s$""")

    fun match(url: String): SplitTrackUrl? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.rawPath ?: return null
        val m = PATH.matchEntire(path) ?: return null
        val cid = m.groupValues[1]
        val n = m.groupValues[2].toLongOrNull() ?: return null
        return SplitTrackUrl(trackKey = path, url = url, assetKey = "bili:$cid", audio = n in 30200..30299)
    }
}
