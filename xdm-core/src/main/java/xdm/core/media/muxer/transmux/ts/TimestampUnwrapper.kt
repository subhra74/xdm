// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.ts

/**
 * Turns the 33-bit PES timestamps of every stream of one transport stream into one 64-bit timeline,
 * so the MPEG clock rollover (every ~26.5 h) doesn't move frames by a day.
 *
 * Each value becomes the `v + k·2^33` nearest the latest unwrapped timestamp of any stream. Being
 * nearest (not only forward) keeps a reordered frame from before the wrap that arrives after one from
 * after it on its own side; being shared keeps streams that start on either side of the wrap together.
 * A value is never made negative (negative means "no timestamp" to the readers): one that would be
 * stays where it is, as a stream from before the wrap seen only after another stream's first frames
 * from after it.
 *
 * One long of state; [reset] at a section start (another encoder, another clock).
 */
class TimestampUnwrapper {
    /** The latest unwrapped timestamp, or -1 before the first. */
    private var latest = -1L

    /** [v] (33-bit, or negative for none) on the shared timeline; negative stays negative. */
    fun unwrap(v: Long): Long {
        if (v < 0) return v
        if (latest < 0) { latest = v; return v }
        var r = v + Math.floorDiv(latest - v + HALF, WRAP) * WRAP
        if (r < 0) r += WRAP
        latest = r
        return r
    }

    fun reset() { latest = -1 }

    private companion object {
        const val WRAP = 1L shl 33
        const val HALF = 1L shl 32
    }
}
