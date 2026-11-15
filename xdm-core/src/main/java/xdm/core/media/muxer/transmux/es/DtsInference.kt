// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.sample.SampleTable

/**
 * Decode times for video samples whose PES gave none, or gave one later than the presentation time
 * (which no decoder can honour). A PES may carry only a PTS: by the spec DTS then equals PTS, which
 * holds for streams without reordering, but encoders and segmenters also leave the DTS out on
 * reordered (B-)frames, and some start segments with such a frame. Taking DTS = PTS there makes the
 * decode times go back at every B-frame and the timeline repair would rewrite the presentation times.
 *
 * Such samples are collected in runs (consecutive in decode order, within one section). A run closes
 * at the next sample with a usable DTS, at a section start or at the end; its decode times are then
 * its presentation times sorted, shifted back by the run's reorder depth (the most any sample comes
 * before its place in presentation order): monotonic and never after a sample's PTS, as ffmpeg derives
 * them from the reorder delay. Where the samples around the run leave too little room (a segmenter's
 * timestamps that don't add up), decode times are moved by single ticks to stay increasing, the way
 * ffmpeg's muxer bumps them. Presentation times are never changed.
 */
internal class DtsInference(private val samples: SampleTable) {
    private val run = ArrayList<Int>()
    /** DTS of the last sample before the run, in the same section; null at a section's start. */
    private var before: Long? = null
    /** That sample's index, and the DTS of the one before it (how far it may move back), or null. */
    private var beforeIndex = -1
    private var beforeFloor: Long? = null
    private var section = Int.MIN_VALUE

    /** Number of samples whose decode time was inferred or moved (for the log). */
    var inferred = 0
        private set

    /**
     * Called after sample [index] is added with PTS [pts] and DTS [dts] in [sectionOf]; [dtsGiven] says
     * whether the PES carried a DTS.
     */
    fun added(index: Int, pts: Long, dts: Long, dtsGiven: Boolean, sectionOf: Int) {
        if (sectionOf != section) {
            close(null)
            section = sectionOf
            before = null
            beforeIndex = -1
            beforeFloor = null
        }
        if (!dtsGiven || dts > pts) {
            run += index
            return
        }
        close(dts)
        beforeFloor = before
        before = samples.dts(index)
        beforeIndex = index
    }

    /** A sample without a real timestamp at all (times extrapolated elsewhere): the run can't span it. */
    fun interrupted() {
        close(null)
        before = null
        beforeIndex = -1
        beforeFloor = null
    }

    /** The section or the stream ends. */
    fun finish() = close(null)

    private fun close(next: Long?) {
        if (run.isEmpty()) return
        val n = run.size
        val pts = LongArray(n) { samples.pts(run[it]) }
        val sorted = pts.sortedArray()
        var depth = 0L
        for (i in 0 until n) depth = maxOf(depth, sorted[i] - pts[i])
        val dts = LongArray(n) { sorted[it] - depth }
        // Room around the run: strictly after the sample before, strictly before the sample after.
        var low = before
        for (i in 0 until n) {
            if (low != null && dts[i] <= low) dts[i] = low + 1
            low = dts[i]
        }
        var high = next
        for (i in n - 1 downTo 0) {
            val cap = minOf(pts[i], if (high != null) high - 1 else Long.MAX_VALUE)
            if (dts[i] > cap) dts[i] = cap
            high = dts[i]
        }
        // No room after the sample before (its DTS already at the first one's PTS, as when a segmenter
        // puts B-frames' DTS a frame late): that sample moves back instead, as far as the one before it allows.
        val b = before
        if (b != null && beforeIndex >= 0 && dts[0] <= b) {
            val moved = maxOf(dts[0] - 1, (beforeFloor ?: Long.MIN_VALUE) + 1)
            if (moved < b && moved <= samples.pts(beforeIndex)) { samples.setDts(beforeIndex, moved); inferred++ }
        }
        for (i in 0 until n) {
            if (samples.dts(run[i]) != dts[i]) inferred++
            samples.setDts(run[i], dts[i])
        }
        beforeFloor = if (n > 1) dts[n - 2] else samples.dts(beforeIndex.coerceAtLeast(0)).takeIf { beforeIndex >= 0 }
        before = dts[n - 1]
        beforeIndex = run[n - 1]
        run.clear()
    }
}

/**
 * Rows [0, count) of [samples] were written before the stream's first PES timestamp (timed from 0 at
 * a guessed cadence): they are moved back from [firstDts] at [step] per picture, so they end where it
 * starts, as ffmpeg back-fills the timestamps before the first one. Never below 0 (the step shrinks).
 */
internal fun backFillLeading(samples: SampleTable, count: Int, firstDts: Long, step: Long) {
    if (count <= 0 || firstDts <= 0) return
    val s = minOf(step, firstDts / count)
    if (s <= 0) return
    for (i in 0 until count) {
        val offset = samples.pts(i) - samples.dts(i)
        val d = firstDts - s * (count - i)
        samples.setDts(i, d)
        samples.setPts(i, d + offset)
    }
}
