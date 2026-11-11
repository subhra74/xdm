// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.MuxContainer

/** The container rule: Matroska whenever WebM/Matroska is involved or MP4 can't carry a track, else MP4. */
class TestMuxContainer {

    @Test
    fun upFrontFromMimeTypesAndCodecs() {
        assertEquals("mp4", MuxContainer.forStreams("video/mp4", "audio/mp4"))
        assertEquals("mkv", MuxContainer.forStreams("video/webm", "audio/webm"), "WebM pair")
        assertEquals("mkv", MuxContainer.forStreams("video/mp4", "audio/webm"), "WebM audio")
        assertEquals("mkv", MuxContainer.forStreams("video/mp4", null), "unknown MIME type")
        assertEquals("mkv", MuxContainer.forStreams("video/mp4", "audio/mp4", codecs = listOf("avc1.64001f", "vorbis")),
            "Vorbis has no MP4 sample entry here")
        assertEquals("mp4", MuxContainer.forStreams("video/mp4", "audio/mp4", codecs = listOf("vp09.00.10.08", "opus")),
            "VP9 and Opus fit MP4")
    }

    @Test
    fun codecsMp4CanCarry() {
        for (c in listOf("avc1.42E01E", "hvc1.1.6.L93.B0", "av01.0.05M.08", "vp09.00.10.08", "mp4a.40.2", "ec-3", "opus", "flac"))
            assertTrue(MuxContainer.mp4CanCarry(c), c)
        for (c in listOf("vorbis", "theora", "vp8", "vp08.00.41.08", "avc1.42E01E,vorbis"))
            assertFalse(MuxContainer.mp4CanCarry(c), c)
    }

    @Test
    fun atMuxTime() {
        assertEquals("mp4", MuxContainer.plan("mp4", matroskaInput = false))
        assertEquals("m4a", MuxContainer.plan("m4a", matroskaInput = false), "an MP4-family request is kept")
        assertEquals("mp4", MuxContainer.plan("xdm-part", matroskaInput = false), "no media extension: MP4")
        assertEquals("mp4", MuxContainer.plan(null, matroskaInput = false))
        assertEquals("mkv", MuxContainer.plan("mp4", matroskaInput = true), "WebM input is never written as MP4")
        assertEquals("mka", MuxContainer.plan("m4a", matroskaInput = true), "audio-only stays audio-only")
        assertEquals("mkv", MuxContainer.plan("mp4", matroskaInput = false, mp4Incapable = true))
        assertEquals("mkv", MuxContainer.plan("webm", matroskaInput = true), "a merge is never WebM")
        assertEquals("mka", MuxContainer.plan(".weba", matroskaInput = true))
        assertEquals("mkv", MuxContainer.plan("mkv", matroskaInput = false), "Matroska is never turned into MP4")
        assertEquals("mka", MuxContainer.plan("MKA", matroskaInput = false))
    }

    @Test
    fun publishedNameTakesTheExtension() {
        assertEquals("talk.mkv", MuxContainer.withExtension("talk.mp4", "mkv"))
        assertEquals("talk.mkv", MuxContainer.withExtension("talk.webm", "mkv"))
        assertEquals("talk.MP4", MuxContainer.withExtension("talk.MP4", "mp4"), "already right: unchanged")
        assertEquals("talk", MuxContainer.withExtension("talk", "mp4").removeSuffix(".mp4"))
        assertEquals("talk.mp4", MuxContainer.withExtension("talk", "mp4"), "no extension: added")
        assertEquals("Part 1. Intro.mkv", MuxContainer.withExtension("Part 1. Intro", "mkv"), "a dot that isn't an extension stays")
        assertEquals(".hidden.mkv", MuxContainer.withExtension(".hidden", "mkv"))
    }
}
