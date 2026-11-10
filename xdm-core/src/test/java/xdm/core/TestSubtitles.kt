package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.media.subtitle.Subtitles
import xdm.core.media.subtitle.Subtitles.Format
import xdm.core.media.subtitle.Ttml
import xdm.core.media.subtitle.WebVtt

class TestSubtitles {
    private val vtt = """
        WEBVTT
        Language: pt-BR

        NOTE a comment

        1
        00:00:01.000 --> 00:00:02.500 align:start
        <v Roger>Hello</v> <i>there</i>

        00:01:00.000 --> 00:01:03.000
        Second
        line
    """.trimIndent()

    @Test
    fun detect_byContent() {
        assertEquals(Format.VTT, Subtitles.detect("﻿WEBVTT\n\n00:00.000 --> 00:01.000\nx"))
        assertEquals(Format.TTML, Subtitles.detect("<?xml version=\"1.0\"?>\n<tt xmlns=\"http://www.w3.org/ns/ttml\"></tt>"))
        assertEquals(Format.TTML, Subtitles.detect("<tt:tt xmlns:tt=\"http://www.w3.org/ns/ttml\"/>"))
        assertEquals(Format.SRT, Subtitles.detect("1\r\n00:00:01,000 --> 00:00:02,000\r\nHi\r\n"))
        assertNull(Subtitles.detect("<html><body>not found</body></html>"))
        assertNull(Subtitles.detect("{\"events\":[]}"))
    }

    @Test
    fun summarize_vttFile() {
        val s = Subtitles.summarize(vtt)!!
        assertEquals(Format.VTT, s.format)
        assertEquals(2, s.cues)
        assertEquals(63_000L, s.lastEndMs)
        assertEquals("pt-BR", s.language)
        assertFalse(s.hlsSegment)
        assertFalse(s.imageTrack)
    }

    @Test
    fun summarize_hlsSegmentIsMarked() {
        val seg = "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n00:00:10.000 --> 00:00:12.000\nHi\n"
        assertTrue(Subtitles.summarize(seg)!!.hlsSegment)
    }

    @Test
    fun summarize_thumbnailTrackIsMarked() {
        val thumbs = """
            WEBVTT

            00:00:00.000 --> 00:00:05.000
            sprite-0.jpg#xywh=0,0,160,90

            00:00:05.000 --> 00:00:10.000
            https://cdn.example.com/thumbs/sprite-0.jpg#xywh=160,0,160,90
        """.trimIndent()
        assertTrue(Subtitles.summarize(thumbs)!!.imageTrack)
        assertFalse(Subtitles.summarize(vtt)!!.imageTrack)
    }

    @Test
    fun vttToSrt_keepsTimesAndBasicStyling() {
        val srt = Subtitles.toSrt(vtt)!!
        assertEquals(
            "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello <i>there</i>\r\n\r\n" +
                "2\r\n00:01:00,000 --> 00:01:03,000\r\nSecond\r\nline\r\n\r\n",
            srt,
        )
    }

    @Test
    fun srtIsNotConvertedAgain() {
        assertNull(Subtitles.toSrt("1\n00:00:01,000 --> 00:00:02,000\nHi\n"))
        assertEquals(1, Subtitles.summarize("1\n00:00:01,000 --> 00:00:02,000\nHi\n")!!.cues)
    }

    @Test
    fun ttmlToSrt_clockTimesSpansAndBreaks() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling" xml:lang="de">
              <body><div>
                <p begin="00:00:01.500" end="00:00:03.000">Erste<br/>Zeile</p>
                <p begin="00:00:04.000" dur="2s"><span tts:fontStyle="italic">kursiv</span>   und
                   normal</p>
              </div></body>
            </tt>
        """.trimIndent()
        assertEquals("de", Subtitles.summarize(ttml)!!.language)
        assertEquals(
            "1\r\n00:00:01,500 --> 00:00:03,000\r\nErste\r\nZeile\r\n\r\n" +
                "2\r\n00:00:04,000 --> 00:00:06,000\r\n<i>kursiv</i> und normal\r\n\r\n",
            Subtitles.toSrt(ttml),
        )
    }

    @Test
    fun ttml_ticksAndNestedOffsets() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:tickRate="10000000">
              <body><div begin="10s">
                <p begin="10000000t" end="25000000t">Ticks</p>
              </div></body>
            </tt>
        """.trimIndent()
        assertEquals(listOf(WebVtt.Cue(11_000, 12_500, "Ticks")), Ttml.parse(ttml))
    }

    @Test
    fun ttml_frameTimes() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:frameRate="25">
              <body><div><p begin="00:00:01:12" end="00:00:02:00">Frames</p></div></body>
            </tt>
        """.trimIndent()
        assertEquals(listOf(WebVtt.Cue(1_480, 2_000, "Frames")), Ttml.parse(ttml))
    }

    @Test
    fun ttml_doctypeIsRefused() {
        val evil = """<?xml version="1.0"?><!DOCTYPE tt [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0s" end="1s">&x;</p></div></body></tt>"""
        assertEquals(emptyList<WebVtt.Cue>(), Ttml.parse(evil))
    }

    @Test
    fun decode_handlesBomsAndUtf16() {
        assertEquals("WEBVTT", Subtitles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "WEBVTT".toByteArray()))
        assertEquals("WEBVTT", Subtitles.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "WEBVTT".toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun srtFileName_replacesTheExtension() {
        assertEquals("movie.en.srt", Subtitles.srtFileName("movie.en.vtt"))
        assertEquals("movie.srt", Subtitles.srtFileName("movie"))
        assertEquals("a.srt", Subtitles.srtFileName("a.srt"))
    }
}
