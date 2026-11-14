// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux

import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.media.muxer.transmux.sample.presentationStart

/**
 * A container-format writer that a demuxer streams sample bytes into (via [SampleSink]) and that
 * finalizes the output file from the accumulated [Track]s. Implemented by the MP4 writer
 * ([xdm.core.media.muxer.transmux.mp4.Mp4Writer]), the Matroska/WebM writer
 * ([xdm.core.media.muxer.transmux.mkv.MkvWriter]) and the Ogg Opus writer
 * ([xdm.core.media.muxer.transmux.ogg.OggWriter]).
 */
interface ContainerWriter : SampleSink {
    /**
     * The stream has discontinuities (timestamps restart or jump): [finish] places its sections back
     * to back. A track going backward is repaired whatever this says (see `Timeline`).
     */
    var repairTimeline: Boolean

    /**
     * Whether the caller has a lossless fallback (TS join or merge, fMP4 join) for a remux whose timing
     * [xdm.core.media.muxer.transmux.sample.RemuxCheck] rejects; asked only on a hard finding. Null: none.
     */
    var remuxCheckFallback: (() -> Boolean)?
        get() = null
        set(_) {}

    /**
     * Writes the container structure and finalizes the file. Returns false if there is no media (or the
     * write was cancelled); an I/O failure is thrown, and the caller drops the partial output with [abort].
     */
    fun finish(tracks: List<Track>): Boolean

    /** Closes handles and deletes any partial output. */
    fun abort()

    /**
     * Seconds, on the tracks' own timeline, that become time zero of the output: by default the
     * earliest presented sample of any track. Called before [finish].
     */
    fun presentationZero(tracks: List<Track>): Double? =
        tracks.filter { it.samples.isNotEmpty() && it.timescale > 0 }
            .minOfOrNull { presentationStart(it).toDouble() / it.timescale }
}
