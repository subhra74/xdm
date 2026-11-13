// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.sample

import xdm.core.util.Logger

/**
 * The output timeline, shared by both writers and run once at the start of their `finish`. A stream
 * whose timestamps restart or jump (an HLS discontinuity, an ad in another container, a DASH
 * period, an init segment repeated with timestamps from 0) is placed back to back: whole sections
 * when every track agrees on them ([alignSections]), else each track's gaps are replaced with its
 * typical frame and its timestamps rewritten from those durations. It runs when the caller says
 * the stream has discontinuities, and also when any track's decode time goes backward, which no
 * stream does on its own. Tracks that carry only presentation times ([Track.ptsOnly]: Matroska
 * input) go back by up to a reorder window between B-frames; that is not a jump, and their
 * timestamps are only shifted by whole sections, never rewritten.
 */
object Timeline {
    /** Returns true when the timeline was repaired. */
    fun prepare(tracks: List<Track>, discontinuous: Boolean): Boolean {
        val usable = tracks.filter { it.samples.size > 1 }
        val jumped = usable.firstOrNull { backwardJump(it) >= 0 }
        if (!discontinuous && jumped == null) return false
        if (!discontinuous) {
            Logger.info("XDM", "Timeline: a ${if (jumped!!.codec.isVideo) "video" else "audio"} track's timestamps go back at sample ${backwardJump(jumped)}, repairing")
        }
        if (alignSections(usable)) return true
        for (t in usable) {
            if (t.ptsOnly) continue
            assignSampleDurations(t.samples, true)
            retime(t.samples)
        }
        return true
    }

    /** Index of the first sample whose decode time goes back (by more than a reorder window for [Track.ptsOnly]), or -1. */
    internal fun backwardJump(t: Track): Int {
        val s = t.samples
        val allowance = if (t.ptsOnly) typicalStep(s, 1, s.size).coerceAtLeast(0) * PTS_ONLY_REORDER_FRAMES else 0
        var max = s.dts(0)
        for (k in 1 until s.size) {
            val d = s.dts(k)
            if (d < max - allowance) return k
            if (d > max) max = d
        }
        return -1
    }

    /** Decode times rewritten as the running sum of the durations, each PTS keeping its offset from its DTS. */
    private fun retime(s: SampleTable) {
        var dts = s.dts(0)
        for (i in 0 until s.size) {
            val offset = s.pts(i) - s.dts(i)
            s.setDts(i, dts)
            s.setPts(i, dts + offset)
            dts += s.duration(i)
        }
    }
}

/** Frames a PTS-only track may go back between reordered (B-)frames without that being a jump. */
private const val PTS_ONLY_REORDER_FRAMES = 16

/** Samples scanned for the earliest PTS; far deeper than any real B-frame reorder window. */
private const val REORDER_WINDOW = 64

/**
 * Earliest presentation time among the first samples in decode order. Only the leading window is
 * scanned: later samples may sit on a reset timeline after a discontinuity.
 */
internal fun presentationStart(samples: SampleTable): Long {
    var min = Long.MAX_VALUE
    for (i in 0 until minOf(samples.size, REORDER_WINDOW)) min = minOf(min, samples.pts(i))
    return min
}

/**
 * End of the last presented sample on the written timeline (decode times are the running sum of
 * [SampleTable.duration], composition adds pts - dts), in media ticks from the first sample's DTS.
 * With B-frames the last frames in decode order are presented after the last DTS, so an edit that
 * ended at the decode duration would cut them off.
 */
internal fun presentationEnd(samples: SampleTable): Long {
    var decodeTime = 0L
    var end = 0L
    for (i in 0 until samples.size) {
        val d = samples.duration(i)
        end = maxOf(end, decodeTime + (samples.pts(i) - samples.dts(i)) + d)
        decodeTime += d
    }
    return end
}

/**
 * Places the sections of a stream with discontinuities (timestamps restarting or jumping at
 * each one) back to back on one timeline, as players do: every track of section k is shifted by
 * the same amount, so the A/V offset inside a section is kept, and by just enough that no track
 * overlaps its previous section. A track that ended earlier is left with a small gap (a video
 * frame shown a little longer, or audio timestamps resuming a little later).
 *
 * Sections are found per track as the timestamp jumps of [isJump]; the tracks must agree on
 * their number, else nothing is changed and false is returned (the caller then repairs each
 * track on its own).
 */
internal fun alignSections(tracks: List<Track>): Boolean {
    val usable = tracks.filter { it.samples.size > 1 && it.timescale > 0 }
    if (usable.isEmpty()) return false
    val starts = usable.map { sectionStarts(it) }
    val count = starts.first().size
    if (count <= 1 || starts.any { it.size != count }) return false

    // Seconds where the previous section ended, after its shift (the latest end over all tracks).
    var previousEnd = Double.NEGATIVE_INFINITY
    for (k in 0 until count) {
        var shift = 0.0
        if (k > 0) {
            // Smallest shift that puts every track's section start at or after its previous end.
            shift = Double.NEGATIVE_INFINITY
            for ((i, t) in usable.withIndex()) {
                val from = starts[i][k]
                val sectionStart = sectionFirstPresented(t.samples, from, starts[i].getOrElse(k + 1) { t.samples.size })
                val trackEnd = sectionEnd(t, starts[i][k - 1], from)
                shift = maxOf(shift, trackEnd - sectionStart.toDouble() / t.timescale)
            }
        }
        var end = previousEnd
        for ((i, t) in usable.withIndex()) {
            val from = starts[i][k]
            val to = starts[i].getOrElse(k + 1) { t.samples.size }
            t.samples.shift(from, to, Math.round(shift * t.timescale))
            end = maxOf(end, sectionEnd(t, from, to))
        }
        previousEnd = end
    }
    return true
}

/** Upper median of the positive DTS steps into the samples [from, to) (each from the one before), or -1. */
internal fun typicalStep(samples: SampleTable, from: Int, to: Int): Long = upperMedian { sink ->
    for (k in from until to) {
        val d = samples.dts(k) - samples.dts(k - 1)
        if (d > 0) sink.accept(d)
    }
}

/**
 * Indices where each section of [t] starts: 0, then every timestamp jump (back, or forward by more
 * than [DISCONTINUITY_GAP_FACTOR] frames). A [Track.ptsOnly] track is measured from its latest time
 * so far and may go back by a reorder window.
 */
private fun sectionStarts(t: Track): List<Int> {
    val samples = t.samples
    val typical = typicalStep(samples, 1, samples.size)
    if (typical < 0) return listOf(0)
    val allowance = if (t.ptsOnly) typical * PTS_ONLY_REORDER_FRAMES else 0
    val out = arrayListOf(0)
    var max = samples.dts(0)
    for (k in 1 until samples.size) {
        val dts = samples.dts(k)
        val d = dts - max
        if (d < -allowance || d > typical * DISCONTINUITY_GAP_FACTOR) { out += k; max = dts }
        else if (dts > max) max = dts
    }
    return out
}

/** Earliest PTS among the first samples of the section [from, to). */
private fun sectionFirstPresented(samples: SampleTable, from: Int, to: Int): Long {
    var min = Long.MAX_VALUE
    for (j in from until minOf(to, from + REORDER_WINDOW)) min = minOf(min, samples.pts(j))
    return min
}

/** Seconds at which the section [from, to) of [t] stops being presented: its latest PTS plus one frame. */
private fun sectionEnd(t: Track, from: Int, to: Int): Double {
    var maxPts = Long.MIN_VALUE
    for (j in maxOf(from, to - REORDER_WINDOW) until to) maxPts = maxOf(maxPts, t.samples.pts(j))
    // One frame: the section's typical DTS step.
    val step = if (to - from >= 2) typicalStep(t.samples, from + 1, to).coerceAtLeast(0) else 0
    return (maxPts + step).toDouble() / t.timescale
}

/** A discontinuity gap is an inter-sample delta larger than this multiple of the typical frame. */
private const val DISCONTINUITY_GAP_FACTOR = 8

/**
 * Derives each sample's duration ([SampleTable.duration]) from successive DTS deltas.
 *
 * When [repairTimeline] is true (the manifest signalled a discontinuity), inter-sample gaps that
 * are negative or implausibly large — the artefact of a mid-stream timestamp reset — are replaced
 * with the track's typical (median) frame duration, so the output timeline stays monotonic and
 * continuous across the boundary. When false, behaviour is unchanged (negative gaps clamp to 0,
 * legitimate large gaps are preserved). The last sample reuses the previous duration (or 1).
 */
internal fun assignSampleDurations(samples: SampleTable, repairTimeline: Boolean) {
    if (samples.isEmpty()) return
    if (!repairTimeline) {
        samples.deriveDurations()
        return
    }
    val typical = upperMedian { sink ->
        for (i in 0 until samples.size) {
            val d = samples.rawDuration(i)
            if (d > 0) sink.accept(d)
        }
    }
    if (typical > 0) samples.deriveDurations(typical, typical * DISCONTINUITY_GAP_FACTOR)
    else samples.deriveDurations()
}
