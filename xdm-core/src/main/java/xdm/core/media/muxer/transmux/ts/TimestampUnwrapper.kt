// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.ts

/**
 * Turns the 33-bit PES timestamps of every stream of one transport stream into one 64-bit timeline,
 * so the MPEG clock rollover (every ~26.5 h) doesn't move frames by a day.
 *
 * As ffmpeg does it (libavformat/demux.c update_wrap_reference / wrap_timestamp): one wrap reference
 * for all streams, 60 s before the first timestamp seen (the first PES's DTS, else its PTS); a value
 * below the reference has wrapped and gets 2^33 added. Deciding by the side of a fixed reference keeps
 * reordered frames on either side of the wrap, and streams that start on either side of it, in place.
 * ffmpeg subtracts 2^33 from the values at or above the reference instead when the first timestamp is
 * in the last eighth before the wrap; that timeline is ours shifted by a constant 2^33, and ours has no
 * negative values (negative means "no timestamp" to the readers). One wrap only, as in ffmpeg.
 *
 * One long of state; [reset] at a section start (another encoder, another clock).
 */
class TimestampUnwrapper {
    /** 60 s before the first timestamp (may be negative: then nothing wraps); unset before it. */
    private var reference = UNSET

    /** [v] (33-bit, or negative for none) on the shared timeline; negative stays negative. */
    fun unwrap(v: Long): Long {
        if (v < 0) return v
        if (reference == UNSET) reference = v - REFERENCE_LEAD
        return if (v < reference) v + WRAP else v
    }

    fun reset() { reference = UNSET }

    private companion object {
        const val WRAP = 1L shl 33
        const val REFERENCE_LEAD = 60L * 90_000
        const val UNSET = Long.MIN_VALUE
    }
}
