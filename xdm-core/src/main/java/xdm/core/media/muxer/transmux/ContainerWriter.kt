// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux

import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.sample.Track

/**
 * A container-format writer that a demuxer streams sample bytes into (via [SampleSink]) and that
 * finalizes the output file from the accumulated [Track]s. Implemented by the MP4 writer
 * ([xdm.core.media.muxer.transmux.mp4.Mp4Writer]) and the Matroska/WebM writer
 * ([xdm.core.media.muxer.transmux.mkv.MkvWriter]).
 */
interface ContainerWriter : SampleSink {
    /**
     * The stream has discontinuities (timestamps restart or jump): [finish] places its sections back
     * to back. A track going backward is repaired whatever this says (see `Timeline`).
     */
    var repairTimeline: Boolean

    /**
     * Writes the container structure and finalizes the file. Returns false if there is no media (or the
     * write was cancelled); an I/O failure is thrown, and the caller drops the partial output with [abort].
     */
    fun finish(tracks: List<Track>): Boolean

    /** Closes handles and deletes any partial output. */
    fun abort()
}
