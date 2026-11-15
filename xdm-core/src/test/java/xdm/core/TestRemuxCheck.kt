// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.RemuxCheck
import xdm.core.media.muxer.transmux.sample.RemuxCheck.Check
import xdm.core.media.muxer.transmux.sample.RemuxCheckException
import xdm.core.media.muxer.transmux.sample.SampleSpill
import xdm.core.media.muxer.transmux.sample.Timeline
import xdm.core.media.muxer.transmux.sample.Track
import java.io.File

class TestRemuxCheck {
    @TempDir
    lateinit var dir: File

    /** 25 fps video with B-frames (I P B B, PTS two frames after DTS), 90 kHz. */
    private fun video(n: Int = 300, edit: (Int, Long, Long) -> Pair<Long, Long> = { _, p, d -> p to d }): Track {
        val t = Track(Codec.H264)
        val order = intArrayOf(0, 3, 1, 2)
        for (i in 0 until n) {
            val dts = i * 3600L
            val shown = (i / 4) * 4 + order[i % 4]
            val (pts, d) = edit(i, shown * 3600L + 7200, dts)
            t.samples.add(i * 1000L, 1000, pts, d, i % 4 == 0)
        }
        return t
    }

    /** AAC at 48 kHz, 1024 samples a frame. */
    private fun audio(n: Int = 500, edit: (Int, Long) -> Long = { _, ts -> ts }): Track {
        val t = Track(Codec.AAC)
        t.timescale = 48000
        for (i in 0 until n) {
            val ts = edit(i, i * 1024L)
            t.samples.add(i * 300L, 300, ts, ts, true)
        }
        return t
    }

    private fun checks(tracks: List<Track>, mp4: Boolean = true) = RemuxCheck.run(tracks, mp4).map { it.check }

    @Test
    fun cleanStreamsHaveNoFindings() {
        assertEquals(emptyList<Check>(), checks(listOf(video(), audio())), "B-frame video and audio")
        assertEquals(emptyList<Check>(), checks(listOf(video(), audio()), mp4 = false), "the same for Matroska")
        // One-tick steps (ffmpeg writes those at segment joins too) are not a repeat.
        val joins = audio { i, ts -> if (i == 100) 99 * 1024L + 1 else ts }
        assertEquals(emptyList<Check>(), checks(listOf(joins)), "one-tick step")
    }

    @Test
    fun decodeTimeGoingBack() {
        val t = video { i, p, d -> if (i == 50) p to d - 7200 else p to d }
        val f = RemuxCheck.run(listOf(t), true)
        assertEquals(Check.DTS_BACK, f.first().check, "step back: $f")
        assertEquals(50, f.first().firstSample, "where")
        assertTrue(f.first().hard, "hard")
    }

    @Test
    fun decodeTimeRepeating() {
        val t = audio { i, ts -> if (i == 40) 39 * 1024L else ts }
        assertTrue(Check.DTS_REPEAT in checks(listOf(t)), "zero duration")
    }

    @Test
    fun presentedBeforeDecoded() {
        val early = { i: Int, p: Long, d: Long -> if (i == 10) d - 3600 to d else p to d }
        assertTrue(Check.PTS_BEFORE_DTS in checks(listOf(video(edit = early))), "from an elementary stream")
        val mp4Input = video(edit = early).also { it.compositionMayPrecedeDecode = true }
        assertTrue(Check.PTS_BEFORE_DTS !in checks(listOf(mp4Input)), "MP4 input may present before decode")
    }

    @Test
    fun compositionOffsetOutside32Bits() {
        val t = video { i, p, d -> if (i == 7) p + (1L shl 32) to d else p to d }
        assertTrue(Check.CTS_RANGE in checks(listOf(t), mp4 = true), "MP4 ctts")
        assertTrue(Check.CTS_RANGE !in checks(listOf(t), mp4 = false), "Matroska stores PTS only")
    }

    @Test
    fun presentationTimeRepeatingNearby() {
        // Sample 20's PTS is the one sample 17 has.
        val near = video { i, p, d -> if (i == 20) (17 / 4 * 4 + 3) * 3600L + 7200 to d else p to d }
        assertTrue(Check.PTS_REPEAT in checks(listOf(near)), "within the window")
        // In an MP4 input's own timing (ffmpeg's DASH segments have one at a cut) it is copied as it is: soft.
        val input = video { i, p, d -> if (i == 20) (17 / 4 * 4 + 3) * 3600L + 7200 to d else p to d }.also { it.compositionMayPrecedeDecode = true }
        val f = RemuxCheck.run(listOf(input), true)
        assertEquals(listOf(Check.INPUT_PTS_REPEAT), f.map { it.check }, "input's own repeat")
        assertTrue(!f.first().hard, "soft")
        // The same PTS 40 samples later is outside it (a stream restarting after a repaired section).
        val far = audio { i, ts -> if (i == 200) 160 * 1024L else if (i > 200) ts + 1_000_000 else ts }
        assertTrue(Check.PTS_REPEAT !in checks(listOf(far)), "outside the window")
    }

    @Test
    fun presentationOnlyTracksSkipDecodeChecks() {
        // Matroska input: DTS = PTS, out of order between B-frames.
        val t = video { _, p, _ -> p to p }.also { it.ptsOnly = true }
        assertEquals(emptyList<Check>(), checks(listOf(t), mp4 = false), "reordered PTS as DTS")
    }

    @Test
    fun smallUnmarkedJumpIsSoft() {
        // Decode time steps back by 0.2 s in a stream the caller didn't call discontinuous.
        val t = video { i, p, d -> if (i >= 100) p - 18000 to d - 18000 else p to d }
        Timeline.prepare(listOf(t), false)
        val f = RemuxCheck.run(listOf(t), true).filter { it.check == Check.UNMARKED_JUMP }
        assertEquals(1, f.size, "one soft finding")
        assertTrue(!f.first().hard, "soft")

        val big = video { i, p, d -> if (i >= 100) p - 900_000 to d - 900_000 else p to d }
        Timeline.prepare(listOf(big), false)
        assertTrue(Check.UNMARKED_JUMP !in checks(listOf(big)), "10 s back is a real discontinuity")
    }

    @Test
    fun manyGuessedTimestampsIsSoft() {
        val t = video().also { it.guessedTimestamps = 10 }
        val f = RemuxCheck.run(listOf(t), true)
        assertEquals(listOf(Check.GUESSED_TIMESTAMPS), f.map { it.check }, "over 1 %")
        assertTrue(!f.first().hard, "soft")
        assertEquals(emptyList<Check>(), checks(listOf(video().also { it.guessedTimestamps = 2 })), "under 1 %")
        assertEquals(emptyList<Check>(), checks(listOf(video().also { it.guessedTimestamps = 300 })), "no PES timestamps at all")
    }

    @Test
    fun enforcedHardFindingThrows() {
        val bad = audio { i, ts -> if (i == 40) 39 * 1024L else ts }
        val saved = RemuxCheck.enforce
        try {
            RemuxCheck.enforce = false
            assertEquals(listOf(Check.DTS_REPEAT, Check.PTS_REPEAT), RemuxCheck.review(listOf(bad), true).map { it.check }, "report only")
            RemuxCheck.enforce = true
            assertThrows(RemuxCheckException::class.java, { RemuxCheck.review(listOf(bad), true) { true } }, "enforced, with a fallback")
            assertEquals(2, RemuxCheck.review(listOf(bad), true) { false }.size, "no lossless fallback: kept")
            assertEquals(2, RemuxCheck.review(listOf(bad), true).size, "no fallback given: kept")
            val soft = video().also { it.guessedTimestamps = 10 }
            assertEquals(1, RemuxCheck.review(listOf(soft), true).size, "soft findings never throw")
        } finally {
            RemuxCheck.enforce = saved
        }
    }

    @Test
    fun spilledLongTrackIsChecked() {
        SampleSpill.open(dir).use {
            // Two hours at 25 fps: spilled blocks, read once through the table's cache.
            val clean = video(n = 180_000)
            assertEquals(emptyList<Check>(), checks(listOf(clean)), "clean")
            val bad = video(n = 180_000) { i, p, d -> if (i == 150_000) p to d - 7200 else p to d }
            assertEquals(150_000, RemuxCheck.run(listOf(bad), true).first().firstSample, "found late in the table")
        }
    }
}
