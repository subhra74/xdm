// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.sample

import xdm.core.util.Logger
import java.io.IOException

/**
 * Checks the timing a writer is about to store, after [Timeline.prepare] and before the sample tables
 * (MP4) or clusters (Matroska) go out. A file with broken timing plays with stutters, frozen frames or
 * drifting audio; when a lossless one-file fallback exists (TS join or merge, fMP4 join), that is the
 * better result.
 *
 * Hard findings are timing no container can store as it is:
 *  - decode times that don't strictly increase (a step back, or a repeat: a zero duration);
 *  - a sample presented before it is decoded, from an input that has no such samples of its own
 *    ([Track.compositionMayPrecedeDecode]); in MP4 also a composition offset outside 32 bits;
 *  - two samples with the same presentation time close together in decode order.
 * Soft findings are the same repeat in an MP4 input's own timing (ffmpeg's DASH muxer writes one at a
 * segment cut; a stream copy keeps it, as here), and what reading errors looked like before (D13/D15 in the parity notes): a small
 * backward jump the timeline repaired in a stream nobody marked discontinuous, and a video track
 * where many frames got guessed timestamps although its PES otherwise carry them.
 *
 * Memory: one pass per track over its [SampleTable] (which reads spilled blocks through its own
 * cache), a [PTS_WINDOW]-entry ring and a few counters; nothing per sample, the same for any length.
 *
 * Enforced since 2026-11-14 (the report-only sweep over the corpus, parity cases and real streams was
 * clean after the reference-review fixes): a hard finding makes the writer refuse the remux when the
 * caller has a lossless fallback; soft ones are logged only.
 */
object RemuxCheck {
    /** Throw [RemuxCheckException] on a hard finding instead of only logging it. */
    @Volatile
    internal var enforce = true

    class Finding(val track: Int, val codec: Codec, val check: Check, val firstSample: Int, val count: Int, val detail: String) {
        val hard: Boolean get() = check.hard
        override fun toString() =
            "track $track (${codec}): ${check.what}, $count sample${if (count == 1) "" else "s"} from #$firstSample${if (detail.isEmpty()) "" else "; $detail"}"
    }

    enum class Check(val hard: Boolean, val what: String) {
        DTS_BACK(true, "decode time goes back"),
        DTS_REPEAT(true, "decode time repeats (zero duration)"),
        PTS_BEFORE_DTS(true, "presented before decoded"),
        CTS_RANGE(true, "composition offset outside 32 bits"),
        PTS_REPEAT(true, "presentation time repeats"),
        INPUT_PTS_REPEAT(false, "presentation time repeats in the input's own timing"),
        UNMARKED_JUMP(false, "small backward jump repaired in a stream not marked discontinuous"),
        GUESSED_TIMESTAMPS(false, "many frames with guessed timestamps"),
    }

    /**
     * Logs the findings for [tracks] and returns them. With [enforce] on, a hard one throws when the
     * caller has a lossless [fallback] (TS join/merge, fMP4 join), so the download is saved that way;
     * without one the remux is kept with a warning (a slightly-off file beats none). [mp4]: the writer
     * stores composition offsets as 32-bit `ctts` values.
     */
    fun review(tracks: List<Track>, mp4: Boolean, fallback: (() -> Boolean)? = null): List<Finding> {
        val findings = run(tracks, mp4)
        for (f in findings) Logger.info("XDM", "Remux check: ${if (f.hard) "" else "(soft) "}$f")
        val hard = findings.firstOrNull { it.hard } ?: return findings
        if (!enforce) {
            Logger.info("XDM", "Remux check: report only, remux kept")
        } else if (fallback != null && fallback()) {
            throw RemuxCheckException(hard.toString())
        } else {
            Logger.info("XDM", "Remux check: no lossless fallback for these segments; remux kept despite: $hard")
        }
        return findings
    }

    /** The findings for [tracks], in track order; empty when the timing is sound. */
    fun run(tracks: List<Track>, mp4: Boolean): List<Finding> {
        val out = ArrayList<Finding>()
        for ((index, t) in tracks.withIndex()) {
            if (t.samples.isEmpty()) continue
            checkTrack(index + 1, t, mp4, out)
        }
        return out
    }

    private class Tally(val check: Check) {
        var first = -1
        var count = 0
        var worst = 0L
        fun hit(i: Int, size: Long) {
            if (first < 0) first = i
            count++
            if (size > worst) worst = size
        }
    }

    private fun checkTrack(number: Int, t: Track, mp4: Boolean, out: MutableList<Finding>) {
        val s = t.samples
        val n = s.size
        val back = Tally(Check.DTS_BACK)
        val repeat = Tally(Check.DTS_REPEAT)
        val early = Tally(Check.PTS_BEFORE_DTS)
        val range = Tally(Check.CTS_RANGE)
        val ptsRepeat = Tally(if (t.compositionMayPrecedeDecode) Check.INPUT_PTS_REPEAT else Check.PTS_REPEAT)
        // Presentation-only tracks (Matroska input) store DTS = PTS, out of order between B-frames.
        val decodeChecks = !t.ptsOnly
        val ring = LongArray(PTS_WINDOW)
        var prevDts = 0L
        for (i in 0 until n) {
            val pts = s.pts(i)
            val dts = s.dts(i)
            if (decodeChecks && i > 0) {
                if (dts < prevDts) back.hit(i, prevDts - dts)
                else if (dts == prevDts) repeat.hit(i, 0)
            }
            if (decodeChecks && pts < dts && !t.compositionMayPrecedeDecode) early.hit(i, dts - pts)
            if (mp4) {
                val cts = pts - dts
                if (cts > Int.MAX_VALUE || cts < Int.MIN_VALUE) range.hit(i, Math.abs(cts))
            }
            for (k in 1..minOf(i, PTS_WINDOW - 1)) {
                if (ring[(i - k) % PTS_WINDOW] == pts) { ptsRepeat.hit(i, k.toLong()); break }
            }
            ring[i % PTS_WINDOW] = pts
            prevDts = dts
        }
        fun ticks(v: Long) = "%.3f s".format(v.toDouble() / maxOf(1, t.timescale))
        for (tally in listOf(back, repeat, early, range, ptsRepeat)) {
            if (tally.count == 0) continue
            val detail = when (tally.check) {
                Check.DTS_BACK, Check.PTS_BEFORE_DTS -> "up to ${ticks(tally.worst)}"
                Check.PTS_REPEAT, Check.INPUT_PTS_REPEAT -> "within ${tally.worst} samples"
                else -> ""
            }
            out += Finding(number, t.codec, tally.check, tally.first, tally.count, detail)
        }
        val jump = t.unmarkedJumpBack
        if (jump in 1 until t.timescale.toLong()) {
            out += Finding(number, t.codec, Check.UNMARKED_JUMP, -1, 1, ticks(jump))
        }
        val guessed = t.guessedTimestamps
        if (t.codec.isVideo && guessed > 0 && guessed < n && guessed.toLong() * 100 > n) {
            out += Finding(number, t.codec, Check.GUESSED_TIMESTAMPS, -1, guessed, "of $n frames")
        }
    }

    /** Samples back in decode order a repeated presentation time is looked for. */
    private const val PTS_WINDOW = 32
}

/** The remux would store broken timing; the caller falls back to a lossless one-file output. */
class RemuxCheckException(message: String) : IOException(message)
