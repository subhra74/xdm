// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import xdm.core.util.FormatHelper

data class DetectedVideoInfo(
    val id: Long,
    val name: String,
    val description: String,
    /** When it was captured, epoch milliseconds. */
    val date: Long,
    val tabId: String?,
    /** One MP4 file the extension can show keyframes of (`/preview`): a plain capture or a split track. */
    val previewable: Boolean = false,
    /** A subtitle file ([CapturedSubtitle]), not a video. */
    val subtitle: Boolean = false,
)

data class StreamingVideoDisplayInfo(
    var quality: String? = null,
    var size: Long? = null,
    var duration: Long? = null,
    /** When it was captured, epoch milliseconds. */
    var dateTime: Long = System.currentTimeMillis(),
    var tabUrl: String? = null,
    var tabId: String? = null,
    /** Video height in pixels for HLS/DASH, shown as e.g. 720p where the size is unknown; null if not given. */
    var height: Int? = null,
    /** An HLS/DASH stream's size from its playlists, filled in once known; null if unknown. */
    var streamSize: StreamSize? = null,
) {
    val descriptionText: String
        get() = listOfNotNull(
            quality?.trim()?.takeIf { it.isNotEmpty() },
            streamSize?.text,
            duration?.takeIf { it > 0 }?.let { FormatHelper.hms(it.toInt()) },
        ).joinToString(" ")

    /**
     * The size line of the video dialog for a stream: its size ("≈ 245.3 MB"), else its resolution
     * ("1080p"); null when neither is known. The line is narrow, so it never shows both.
     */
    val streamSummary: String?
        get() = streamSize?.text ?: height?.let { "${it}p" }
}