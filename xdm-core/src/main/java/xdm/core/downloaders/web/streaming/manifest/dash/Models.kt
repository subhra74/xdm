// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders.web.streaming.manifest.dash


import xdm.core.downloaders.SubtitleTrack
import okhttp3.HttpUrl

data class MpdEntry(
    val video: Representation?,
    val audio: Representation?,
    /** The period's WebVTT (`text/vtt`) tracks. */
    val subtitles: List<SubtitleTrack> = emptyList(),
)

/**
 * One playable unit of a representation. [url] is the (resolved) segment URL; [range] is an
 * optional `(offset, length)` byte range within that URL, used by single-file DASH (SegmentList
 * with `mediaRange`, or an `Initialization` `range`) where every segment shares one file. The
 * `(offset, length)` shape matches [xdm.core.downloaders.web.streaming.downloader.StreamingChunk.byteRange]
 * so it can be handed to the downloader unchanged.
 *
 * [toString] returns the bare URL so the many existing call-sites that render/serialise a segment
 * via `.toString()` (UI display, task-info persistence) are unaffected by the added range.
 */
data class DashSegment(
    val url: HttpUrl,
    val range: Pair<Long, Long>? = null,
    /** Seconds, from the manifest (SegmentTemplate/SegmentList duration, SegmentTimeline S@d); 0 when unknown. */
    val duration: Double = 0.0,
    /** The representation's initialization segment. */
    val init: Boolean = false,
) {
    override fun toString(): String = url.toString()
}

data class Representation(
    val width: Int,
    val height: Int,
    val codec: String,
    val bandwidth: Long,
    val duration: Long,
    val segments: List<DashSegment>,
    val mimeType: String,
    val language: String
)