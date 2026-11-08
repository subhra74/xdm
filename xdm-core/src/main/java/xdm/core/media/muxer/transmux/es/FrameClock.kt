// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.es

/**
 * Timestamps of consecutive audio frames in the track timescale: counted from a PES PTS anchor, so
 * the audio is gapless, and re-anchored when a PTS strays more than two frames from the count.
 *
 * Frames may run at a sampling rate other than the track timescale (a stream that switches from
 * 48 to 44.1 kHz keeps its first rate as timescale); their duration is then fractional and the
 * remainder is carried, so the count never drifts.
 */
class FrameClock {
    private var next = UNSET
    private var remainder = 0L

    /** Applies a PES PTS (90 kHz) to the next frame; [frameTicks] is roughly one frame in track ticks. */
    fun anchor(pts90k: Long, timescale: Int, frameTicks: Long) {
        val expected = pts90k * timescale / 90_000L
        if (next == UNSET || kotlin.math.abs(expected - next) > 2 * frameTicks) {
            next = expected
            remainder = 0
        }
    }

    /** Timestamp of the next frame (0 when no PTS has been seen). */
    fun current(): Long {
        if (next == UNSET) next = 0
        return next
    }

    /** Moves past a frame of [samples] audio samples at [sampleRate], in a track of [timescale]. */
    fun advance(samples: Int, sampleRate: Int, timescale: Int) {
        val num = samples.toLong() * timescale + remainder
        next = current() + num / sampleRate
        remainder = num % sampleRate
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}
