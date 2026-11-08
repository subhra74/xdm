// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders.web.streaming.manifest.hls


import xdm.core.downloaders.SubtitleTrack
import okhttp3.HttpUrl

data class HlsPlaylist(
    val mediaSegments: List<HlsMediaSegment>,
    val encrypted: Boolean = false,
    val hasByteRange: Boolean = false,
    val totalDuration: Double = 0.0,
    val keyFrameOnly: Boolean = false,
    val hasInitSection: Boolean = false,
    val version: Int = 0,
    val independent: Boolean = false,
    val hasDiscontinuity: Boolean = false,
    /** `#EXT-X-ENDLIST` was present: no segments will be added. */
    val endList: Boolean = false,
    /** `#EXT-X-PLAYLIST-TYPE` (`VOD` or `EVENT`), null when absent. */
    val playlistType: String? = null,
) {
    /** The playlist may still grow, so it has no final duration or size. */
    val isLive: Boolean
        get() = !endList && !"VOD".equals(playlistType, ignoreCase = true)

    /**
     * The total bytes of every segment, init section included, when each one has its own
     * `#EXT-X-BYTERANGE` (or `BYTERANGE` on `#EXT-X-MAP`); null when any size is unknown.
     */
    fun byteRangeTotal(): Long? {
        if (mediaSegments.isEmpty()) return null
        var total = 0L
        for (seg in mediaSegments) total += seg.byteRange?.second ?: return null
        return total
    }
}

data class HlsMasterPlaylist(
    val videoPlaylist: HttpUrl? = null,
    val audioPlaylist: HttpUrl? = null,
    val attributes: Map<String, String>,
    val independent: Boolean = false,
    /** The variant's WebVTT `SUBTITLES` group, each with its media playlist. */
    val subtitles: List<SubtitleTrack> = emptyList(),
)

data class HlsMediaSegment(
    val url: String,
    val byteRange: Pair<Long, Long>? = null,
    val duration: Double = 0.0,
    val keyUrl: String? = null,
    val iv: String? = null,
    val encrypted: Boolean,
    val discontinuity: Boolean = false,
)