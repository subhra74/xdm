// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Which responses are worth a look as media: the rule the browser extension applies before it posts
 * a request to `/media` (`isMatchingRequest` in request-watcher.js), so the Android browser sends
 * capture exactly what the extension would. The desktop hands the same lists to the extension in
 * `/sync`; everything after this rule is [VideoHelper]'s.
 */
object MediaFilter {
    /** Content-Type fragments that mark media (the extension's `mediaTypes`). */
    val MEDIA_TYPES = listOf("audio/", "video/", "mpeg", "dash")

    /** Path extensions that mark media (the default of the "video extensions" setting). */
    val DEFAULT_VIDEO_EXTENSIONS = listOf(
        "MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
        "MOV", "MPG", "MPEG", "OPUS", "MPD", "M4V", "M4A", "OGV", "OGA", "WAV", "FLAC", "3GP", "AVI", "WMV"
    )

    /** Content-Type fragments of stand-alone subtitle files: WebVTT, SubRip, TTML / DFXP. */
    val SUBTITLE_TYPES = listOf("text/vtt", "subrip", "text/srt", "ttml+xml", "ttaf+xml", "dfxp")

    /** Path extensions of stand-alone subtitle files. Not part of the "video extensions" setting. */
    val SUBTITLE_EXTENSIONS = listOf("VTT", "WEBVTT", "SRT", "TTML", "DFXP")

    /** Hosts never looked at (the default of the "blocked hosts" setting). */
    val DEFAULT_BLOCKED_HOSTS = listOf("update.microsoft.com", "windowsupdate.com", "thwawte.com")

    /**
     * True when a response with [status] and [contentType] from [url] is media: a 2xx, not from a
     * blocked host, and with a media extension on its path or a media Content-Type.
     */
    fun isMedia(url: String, status: Int, contentType: String?, config: CaptureConfig): Boolean {
        // A failed response (a 410 from an expired or used-up CDN token, a 403, ...) is not media:
        // XDM would only replay the request and get the same error back.
        if (status !in 200..299) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        // The extension compares against URL.host, which carries a non-default port.
        val host = if (parsed.port == okhttp3.HttpUrl.defaultPort(parsed.scheme)) parsed.host else "${parsed.host}:${parsed.port}"
        if (config.blockedHosts.any { host.contains(it) }) return false
        val path = parsed.encodedPath.uppercase(Locale.ROOT)
        if (config.detectSubtitles && isSubtitle(url, contentType)) return true
        if (config.videoExtensions.any { path.endsWith("." + it.uppercase(Locale.ROOT)) }) return true
        // MIME types are case-insensitive (RFC 2045), so "Video/MP4" must match "video/" too.
        val type = contentType?.lowercase(Locale.ROOT).orEmpty()
        return type.isNotEmpty() && MEDIA_TYPES.any { type.contains(it) }
    }

    /**
     * True when [url] / [contentType] is a stand-alone subtitle file by its type or path extension.
     * Whether it really is one (and not an HLS subtitle segment) is decided on its content.
     */
    fun isSubtitle(url: String, contentType: String?): Boolean {
        val type = contentType?.lowercase(Locale.ROOT).orEmpty()
        if (type.isNotEmpty() && SUBTITLE_TYPES.any { type.contains(it) }) return true
        val path = (url.toHttpUrlOrNull()?.encodedPath ?: return false).uppercase(Locale.ROOT)
        return SUBTITLE_EXTENSIONS.any { path.endsWith(".$it") }
    }
}
