// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import xdm.core.downloaders.web.streaming.manifest.dash.Representation
import xdm.core.downloaders.web.streaming.manifest.hls.HlsPlaylist
import xdm.core.util.FormatHelper

/**
 * The size of an HLS/DASH stream before it is downloaded, worked out from the playlists alone
 * (private-docs/docs/hls-size-estimate.md). [bytes] is the source segments' total; the saved file
 * can be a little smaller once MPEG-TS is transmuxed to MP4.
 */
class StreamSize(val bytes: Long, val kind: Kind) {
    enum class Kind {
        /** The sum of every segment's byte range. */
        EXACT,

        /** From the average bitrate: usually within about 1%. */
        AVERAGE,

        /** From the declared (peak or nominal) bitrate: rough, can be tens of percent off. */
        PEAK,

        /** The playlist is still growing: there is no size yet. */
        LIVE,
    }

    /** "245.3 MB", "≈ 245.3 MB", "~ 245.3 MB" or "Live". */
    val text: String
        get() = when (kind) {
            Kind.LIVE -> "Live"
            Kind.EXACT -> FormatHelper.formatSize(bytes.toDouble())
            Kind.AVERAGE -> "≈ " + FormatHelper.formatSize(bytes.toDouble())
            Kind.PEAK -> "~ " + FormatHelper.formatSize(bytes.toDouble())
        }

    /** The bytes a free-space check should count on; null for a live stream. */
    val neededBytes: Long?
        get() = bytes.takeIf { kind != Kind.LIVE && it > 0 }

    override fun toString() = text

    companion object {
        val LIVE = StreamSize(0, Kind.LIVE)
    }
}

/**
 * What a size estimate needs from an HLS media playlist, small enough to keep for a while after
 * the playlist itself (which can list thousands of segments) is gone.
 */
class HlsMediaInfo(
    /** Seconds, the sum of `#EXTINF`. */
    val duration: Double,
    val live: Boolean,
    /** Every segment's byte range added up, null when any is missing. */
    val exactBytes: Long?,
) {
    companion object {
        fun of(playlist: HlsPlaylist) = HlsMediaInfo(playlist.totalDuration, playlist.isLive, playlist.byteRangeTotal())
    }
}

object StreamSizes {
    /**
     * One HLS variant. [attrs] are its `EXT-X-STREAM-INF` attributes; [video] and [audio] its media
     * playlists when loaded, [separateAudio] whether it plays with a separate audio rendition; [duration] the stream's length in seconds, which every variant shares,
     * when [video] is not loaded. Separate audio adds nothing to a bitrate estimate: the variant's
     * `BANDWIDTH` covers the whole combination (RFC 8216 §4.3.4.2).
     */
    fun hlsVariant(
        attrs: Map<String, String>, video: HlsMediaInfo?, audio: HlsMediaInfo?, separateAudio: Boolean, duration: Double?,
    ): StreamSize? {
        if (video?.live == true || audio?.live == true) return StreamSize.LIVE
        if (video != null) {
            val videoBytes = video.exactBytes
            // Exact only when the separate audio (if any) is known exactly too.
            val audioBytes = if (separateAudio) audio?.exactBytes else 0L
            if (videoBytes != null && audioBytes != null) return StreamSize(videoBytes + audioBytes, StreamSize.Kind.EXACT)
        }
        val seconds = video?.duration?.takeIf { it > 0 } ?: duration?.takeIf { it > 0 } ?: return null
        attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0 }?.let {
            return StreamSize(bytes(it, seconds), StreamSize.Kind.AVERAGE)
        }
        attrs["BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0 }?.let {
            return StreamSize(bytes(it, seconds), StreamSize.Kind.PEAK)
        }
        return null
    }

    /** A media playlist on its own carries no bitrate: only an exact size, or Live. */
    fun hlsMedia(media: HlsMediaInfo): StreamSize? = when {
        media.live -> StreamSize.LIVE
        else -> media.exactBytes?.let { StreamSize(it, StreamSize.Kind.EXACT) }
    }

    /**
     * A DASH pick: the chosen representations together (either may be missing). Exact when every
     * segment has a byte range; otherwise `bandwidth` × duration, which DASH defines as a bound, so
     * it is marked as rough.
     */
    fun dash(video: Representation?, audio: Representation?): StreamSize? {
        val reps = listOfNotNull(video, audio)
        if (reps.isEmpty()) return null
        val exact = reps.map { exactBytes(it) }
        if (exact.all { it != null }) return StreamSize(exact.sumOf { it!! }, StreamSize.Kind.EXACT)
        var total = 0L
        for (rep in reps) {
            if (rep.bandwidth <= 0 || rep.duration <= 0) return null
            total += bytes(rep.bandwidth, rep.duration / 1000.0)
        }
        return StreamSize(total, StreamSize.Kind.PEAK)
    }

    private fun exactBytes(rep: Representation): Long? {
        if (rep.segments.isEmpty()) return null
        var total = 0L
        for (seg in rep.segments) total += seg.range?.second ?: return null
        return total
    }

    private fun bytes(bitsPerSecond: Long, seconds: Double): Long = (bitsPerSecond / 8.0 * seconds).toLong()
}
