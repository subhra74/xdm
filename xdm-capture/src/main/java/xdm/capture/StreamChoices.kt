// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import xdm.core.downloaders.web.streaming.manifest.dash.Representation
import xdm.core.downloaders.web.streaming.manifest.hls.HlsMasterPlaylist
import xdm.core.downloaders.web.streaming.manifest.hls.singleFileHttpUrl
import xdm.core.util.plainDownloadExt
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/** What one choice in the Stream download dialog downloads. */
sealed interface StreamPick {
    /** An HLS stream: [url] is the video (or the only) playlist, [audioUrl] a separate audio rendition. */
    data class Hls(val url: String, val audioUrl: String?, val audioOnly: Boolean, val independent: Boolean) : StreamPick

    /** A DASH video and audio pair, muxed after download. */
    data class Dash(val video: Representation, val audio: Representation) : StreamPick {
        /** The container the pair is muxed into, as for captured DASH videos. */
        val extension: String
            get() = if (video.mimeType.contains("mp4") && audio.mimeType.contains("mp4")) "mp4" else "mkv"
    }

    /** One self-contained file (single-file HLS, a single DASH stream): a regular download. */
    data class Http(val url: String, val extension: String) : StreamPick
}

/** How the Audio drop-down behaves for a format. */
enum class AudioMode {
    /** Separate tracks to choose from. */
    CHOOSE,

    /** The audio is muxed into the format itself: one fixed entry, nothing to choose. */
    INCLUDED,

    /** No audio at all (a video-only or audio-only stream). */
    NONE,
}

/** [size]: what the download comes to with this audio, from the playlists; null when unknown. */
class AudioChoice(val label: String, val pick: StreamPick, val size: StreamSize? = null) {
    override fun toString() = label
}

/**
 * One entry of the Format list and the audio it can go with ([audio] is never empty).
 * [audioOnly]: the format is an audio stream by itself (shown with an audio icon).
 */
class FormatChoice(
    val label: String,
    val audioMode: AudioMode,
    val audio: List<AudioChoice>,
    val audioOnly: Boolean = false,
    /** Video height in pixels, 0 when unknown or audio-only. */
    val height: Int = 0,
) {
    /** The size with the first (default) audio track. */
    val size: StreamSize? get() = audio.firstOrNull()?.size

    override fun toString() = label
}

/**
 * Turns a loaded playlist or manifest into the dialog's choices. The parsers hand back every
 * (video, audio) pair; this regroups them into distinct formats, each with its own audio tracks,
 * best first. An HLS media playlist has nothing to choose: one format, downloaded as it is.
 */
object StreamChoices {
    fun of(manifest: VideoHelper.LoadedManifest): List<FormatChoice> = when (manifest) {
        is VideoHelper.LoadedManifest.HlsMedia -> {
            val pick = manifest.playlist.singleFileHttpUrl()?.let { StreamPick.Http(it, plainDownloadExt(null, it)) }
                ?: StreamPick.Hls(manifest.url, null, false, manifest.playlist.independent)
            val size = StreamSizes.hlsMedia(HlsMediaInfo.of(manifest.playlist))
            listOf(FormatChoice(manifest.url, AudioMode.INCLUDED, listOf(AudioChoice("", pick, size))))
        }

        is VideoHelper.LoadedManifest.HlsMaster -> hls(manifest.entries, manifest.media)
        is VideoHelper.LoadedManifest.Dash -> dash(manifest.entries.map { it.video to it.audio })
    }

    /** [media]: the variants' media playlists loaded so far, by URL (for duration and exact sizes). */
    fun hls(entries: List<HlsMasterPlaylist>, media: Map<String, HlsMediaInfo> = emptyMap()): List<FormatChoice> {
        val live = media.values.any { it.live }
        // Variants share one timeline: any finished playlist's duration serves them all.
        val duration = media.values.firstOrNull { !it.live && it.duration > 0 }?.duration
        fun size(e: HlsMasterPlaylist, video: String?, audio: String?): StreamSize? =
            if (live) StreamSize.LIVE
            else StreamSizes.hlsVariant(e.attributes, video?.let { media[it] }, audio?.let { media[it] }, audio != null, duration)
        // Entries that share a video playlist are one format with several audio renditions.
        val byVideo = LinkedHashMap<String, MutableList<HlsMasterPlaylist>>()
        for (e in entries) {
            val key = e.videoPlaylist?.toString() ?: "audio:${e.audioPlaylist}"
            byVideo.getOrPut(key) { ArrayList() }.add(e)
        }
        return byVideo.values
            .sortedByDescending { it.first().attributes["BANDWIDTH"]?.toLongOrNull() ?: 0 }
            .mapNotNull { group ->
                val first = group.first()
                val video = first.videoPlaylist?.toString()
                if (video == null) {
                    // An audio-only variant: the rendition is the whole download.
                    val audio = first.audioPlaylist?.toString() ?: return@mapNotNull null
                    val label = listOfNotNull("Audio only", bandwidth(first.attributes), audioName(first.attributes))
                        .joinToString(" · ")
                    return@mapNotNull FormatChoice(
                        label, AudioMode.NONE,
                        listOf(AudioChoice("", StreamPick.Hls(audio, null, true, first.independent), size(first, audio, null))),
                        audioOnly = true,
                    )
                }
                val renditions = group.filter { it.audioPlaylist != null }
                    .sortedByDescending { it.attributes["DEFAULT"].equals("YES", ignoreCase = true) }
                val label = listOfNotNull(resolution(first.attributes), bandwidth(first.attributes), codecs(first.attributes))
                    .joinToString(" · ").ifEmpty { video.substringAfterLast('/') }
                val height = height(first.attributes)
                if (renditions.isEmpty()) {
                    FormatChoice(
                        label, AudioMode.INCLUDED,
                        listOf(AudioChoice("", StreamPick.Hls(video, null, false, first.independent), size(first, video, null))),
                        height = height,
                    )
                } else {
                    FormatChoice(label, AudioMode.CHOOSE, renditions.map { r ->
                        AudioChoice(audioName(r.attributes) ?: r.audioPlaylist.toString().substringAfterLast('/'),
                            StreamPick.Hls(video, r.audioPlaylist.toString(), false, r.independent),
                            size(r, video, r.audioPlaylist.toString()))
                    }, height = height)
                }
            }
    }

    /** [pairs] are the parser's (video, audio) entries; either side may be missing. */
    fun dash(pairs: List<Pair<Representation?, Representation?>>): List<FormatChoice> {
        val videos = distinct(pairs.mapNotNull { it.first })
            .sortedWith(compareByDescending<Representation> { it.height }.thenByDescending { it.bandwidth })
        val audios = distinct(pairs.mapNotNull { it.second }).sortedByDescending { it.bandwidth }
        if (videos.isNotEmpty() && audios.isNotEmpty()) {
            val audioChoices = { video: Representation ->
                audios.map { a -> AudioChoice(dashAudioLabel(a), StreamPick.Dash(video, a), StreamSizes.dash(video, a)) }
            }
            return videos.map { v -> FormatChoice(dashVideoLabel(v), AudioMode.CHOOSE, audioChoices(v), height = v.height) }
        }
        // A single stream is downloaded whole with no mux, as captured ones are.
        return (videos.map { Triple(it, dashVideoLabel(it), false) } + audios.map { Triple(it, "Audio only · ${dashAudioLabel(it)}", true) })
            .filter { it.first.segments.isNotEmpty() }
            .map { (rep, label, audioOnly) ->
                val url = rep.segments[0].toString()
                FormatChoice(
                    label, AudioMode.NONE,
                    listOf(AudioChoice("", StreamPick.Http(url, plainDownloadExt(rep.mimeType, url)), StreamSizes.dash(rep, null))),
                    audioOnly,
                    height = if (audioOnly) 0 else rep.height,
                )
            }
    }

    /** The parser shares one [Representation] object across all its pairs: dedupe by identity. */
    private fun distinct(reps: List<Representation>): List<Representation> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Representation, Boolean>())
        return reps.filter { seen.add(it) }
    }

    private fun dashVideoLabel(v: Representation) = listOfNotNull(
        v.height.takeIf { it > 0 }?.let { "${it}p" }, rate(v.bandwidth), codecName(v.codec)
    ).joinToString(" · ").ifEmpty { v.mimeType }

    private fun dashAudioLabel(a: Representation) = listOfNotNull(
        language(a.language), codecName(a.codec), rate(a.bandwidth)
    ).joinToString(" · ").ifEmpty { a.mimeType }

    private fun height(attrs: Map<String, String>): Int =
        attrs["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull() ?: 0

    private fun resolution(attrs: Map<String, String>): String? =
        height(attrs).takeIf { it > 0 }?.let { "${it}p" } ?: attrs["RESOLUTION"]

    private fun bandwidth(attrs: Map<String, String>): String? = attrs["BANDWIDTH"]?.toLongOrNull()?.let { rate(it) }

    private fun codecs(attrs: Map<String, String>): String? =
        attrs["CODECS"]?.split(',')?.mapNotNull { codecName(it.trim()) }?.distinct()?.joinToString(", ")?.ifEmpty { null }

    private fun audioName(attrs: Map<String, String>): String? {
        val name = attrs["NAME"]?.takeIf { it.isNotBlank() }
        val lang = attrs["LANGUAGE"]?.takeIf { it.isNotBlank() }
        return when {
            name != null && lang != null && !name.equals(lang, ignoreCase = true) -> "$name ($lang)"
            else -> name ?: lang
        }
    }

    private fun language(code: String): String? {
        if (code.isBlank() || code == "und") return null
        val display = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        return if (display.isBlank() || display.equals(code, ignoreCase = true)) code else "$display ($code)"
    }

    fun rate(bitsPerSecond: Long): String? = when {
        bitsPerSecond <= 0 -> null
        bitsPerSecond >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        else -> "${bitsPerSecond / 1000} kbps"
    }

    /** A friendly name for an RFC 6381 codec string; null for ones not worth showing. */
    fun codecName(codec: String): String? {
        val c = codec.lowercase(Locale.ROOT)
        return when {
            c.isBlank() -> null
            c.startsWith("avc") -> "H.264"
            c.startsWith("hvc") || c.startsWith("hev") -> "H.265"
            c.startsWith("av01") -> "AV1"
            c.startsWith("vp09") || c == "vp9" -> "VP9"
            c.startsWith("vp8") -> "VP8"
            c.startsWith("mp4a") -> "AAC"
            c.startsWith("ec-3") -> "E-AC-3"
            c.startsWith("ac-3") -> "AC-3"
            c == "opus" -> "Opus"
            c == "flac" -> "FLAC"
            else -> codec
        }
    }
}
