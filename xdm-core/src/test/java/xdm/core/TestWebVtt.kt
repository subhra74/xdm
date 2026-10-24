// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.core.downloaders.subtitleFileName
import xdm.core.media.subtitle.WebVtt

class TestWebVtt {

    private fun cues(vararg segments: String, origin: Double? = null) =
        WebVtt.parseSegments(segments.map { it.trimIndent() }, origin).let(WebVtt::merge)

    @Test
    fun plainFile_convertsToSrt() {
        val srt = WebVtt.toSrt(
            listOf(
                """
                WEBVTT

                1
                00:00:01.000 --> 00:00:02.500 align:start line:90%
                Hello

                NOTE a comment --> not a cue

                01:02.000 --> 01:03.000
                <v Bob>Second</v> &amp; <i>more</i>
                line two
                """.trimIndent()
            )
        )
        val expected = "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\n\r\n" +
            "2\r\n00:01:02,000 --> 00:01:03,000\r\nSecond & <i>more</i>\r\nline two\r\n\r\n"
        assertEquals(expected, srt)
    }

    @Test
    fun timestampMap_movesCuesOntoMuxedTimeline() {
        // Video starts at PTS 10 s; the cue is 2 s into the media clock's local time.
        val segment = """
            WEBVTT
            X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000

            00:00:02.000 --> 00:00:03.000
            Two
        """
        assertEquals(listOf(WebVtt.Cue(2000, 3000, "Two")), cues(segment, origin = 10.0))
        // Video starts at 9 s: the cue is a second later in the output.
        assertEquals(listOf(WebVtt.Cue(3000, 4000, "Two")), cues(segment, origin = 9.0))
        // Unknown origin: the first segment's MPEGTS stands in.
        assertEquals(listOf(WebVtt.Cue(2000, 3000, "Two")), cues(segment))
    }

    @Test
    fun hlsWithoutMap_cueTimeIsPts() {
        // As the HLS spec and hls.js read it: cue time 0 is PTS 0.
        val segment = """
            WEBVTT

            00:00:12.000 --> 00:00:13.000
            Twelve
        """
        assertEquals(listOf(WebVtt.Cue(2000, 3000, "Twelve")), cues(segment, origin = 10.0))
        assertEquals(
            listOf(WebVtt.Cue(12000, 13000, "Twelve")), cues(segment),
            "unknown origin: times kept"
        )
    }

    @Test
    fun dashOrPlainFile_keepsTimes() {
        val vtt = "WEBVTT\n\n00:00:12.000 --> 00:00:13.000\nTwelve\n"
        assertEquals(
            listOf(WebVtt.Cue(12000, 13000, "Twelve")),
            WebVtt.merge(WebVtt.parseSegments(listOf(vtt), timelineStartSec = 10.0, hls = false))
        )
    }

    @Test
    fun timestampMap_withLocalOffset() {
        val segment = """
            WEBVTT
            X-TIMESTAMP-MAP=LOCAL:00:00:10.000,MPEGTS:900000

            00:00:12.000 --> 00:00:13.000
            Two
        """
        assertEquals(listOf(WebVtt.Cue(2000, 3000, "Two")), cues(segment, origin = 10.0))
    }

    @Test
    fun timestampMap_across33BitWrap() {
        val wrap = 1L shl 33
        val segment = """
            WEBVTT
            X-TIMESTAMP-MAP=MPEGTS:90000,LOCAL:00:00:00.000

            00:00:00.000 --> 00:00:01.000
            After wrap
        """
        // The video starts a second before the wrap; its unwrapped origin is past 2^33 ticks.
        val origin = (wrap - 90_000).toDouble() / 90_000
        assertEquals(listOf(WebVtt.Cue(2000, 3000, "After wrap")), cues(segment, origin = origin))
    }

    @Test
    fun segments_repeatedAndSplitCuesAreMerged() {
        val a = """
            WEBVTT

            00:00:01.000 --> 00:00:02.000
            One

            00:00:05.500 --> 00:00:06.000
            Across
        """
        val b = """
            WEBVTT

            00:00:05.500 --> 00:00:06.000
            Across

            00:00:06.000 --> 00:00:07.000
            Across

            00:00:07.000 --> 00:00:08.000
            Three
        """
        assertEquals(
            listOf(WebVtt.Cue(1000, 2000, "One"), WebVtt.Cue(5500, 7000, "Across"), WebVtt.Cue(7000, 8000, "Three")),
            cues(a, b)
        )
    }

    @Test
    fun cueBeforeTimelineStart_isClampedOrDropped() {
        val segment = """
            WEBVTT
            X-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000

            00:00:00.000 --> 00:00:00.500
            Gone

            00:00:00.500 --> 00:00:02.000
            Clamped
        """
        assertEquals(listOf(WebVtt.Cue(0, 1000, "Clamped")), cues(segment, origin = 1.0))
    }

    @Test
    fun noCues_givesEmptyText() {
        assertEquals("", WebVtt.toSrt(listOf("WEBVTT\n\nNOTE nothing here\n")))
    }

    @Test
    fun sidecarName_followsVideoName() {
        assertEquals("Movie.en.srt", subtitleFileName("Movie.mp4", "en"))
        assertEquals("Movie (1).pt-BR.srt", subtitleFileName("Movie (1).mkv", "pt-BR"))
        assertEquals("Movie.srt", subtitleFileName("Movie.mp4", ""))
        assertEquals("a.b.en.srt", subtitleFileName("a.b.mp4", "../en"), "path characters are dropped from the language")
    }
}
