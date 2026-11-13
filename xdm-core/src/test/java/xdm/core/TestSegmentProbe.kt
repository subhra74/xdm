// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Test
import xdm.core.PrefixBytes.ac3
import xdm.core.PrefixBytes.adts
import xdm.core.PrefixBytes.bmp
import xdm.core.PrefixBytes.ebml
import xdm.core.PrefixBytes.gif
import xdm.core.PrefixBytes.id3
import xdm.core.PrefixBytes.jpeg
import xdm.core.PrefixBytes.junk
import xdm.core.PrefixBytes.png
import xdm.core.PrefixBytes.tsPackets
import xdm.core.media.muxer.impl.SegmentProbe
import xdm.core.media.muxer.impl.SegmentProbe.Container
import java.io.File

/** Where [SegmentProbe] finds the media, with pictures, tags and junk in front of it. No ffmpeg needed. */
class TestSegmentProbe {
    private val dir = File(System.getProperty("java.io.tmpdir"), "xdm-segment-probe-test").apply { mkdirs() }

    private fun locate(bytes: ByteArray): SegmentProbe.Located? {
        val f = File.createTempFile("seg", ".bin", dir)
        try {
            f.writeBytes(bytes)
            return SegmentProbe.locate(f.path)
        } finally {
            f.delete()
        }
    }

    private fun assertAt(expected: Container, offset: Int, bytes: ByteArray) {
        val found = locate(bytes)
        assert(found == SegmentProbe.Located(expected, offset.toLong())) { "expected $expected at $offset, got $found" }
    }

    @Test
    fun mediaAtTheStart() {
        assertAt(Container.TS, 0, tsPackets(10))
        assertAt(Container.TS, 0, tsPackets(10, 192))
        assertAt(Container.TS, 0, tsPackets(10, 204))
        assertAt(Container.PACKED_AUDIO, 0, adts(10))
        assertAt(Container.PACKED_AUDIO, 0, ac3(10))
        assertAt(Container.PACKED_AUDIO, 0, id3(40) + adts(10))
        assertAt(Container.MKV, 0, ebml())
    }

    @Test
    fun gifInFrontIsNotTakenForTs() {
        // GIF89a starts with 0x47, the TS sync byte.
        assertAt(Container.TS, gif.size, gif + tsPackets(10))
        assertAt(Container.PACKED_AUDIO, gif.size, gif + adts(10))
        assertAt(Container.MKV, gif.size, gif + ebml())
    }

    @Test
    fun picturesOfAnySizeAreSkippedExactly() {
        // 300 KB, past the search limit of the old probe, with TS-like sync bytes and a fake box inside.
        val big = png(300_000)
        assertAt(Container.TS, big.size, big + tsPackets(10))
        val j = jpeg()
        assertAt(Container.TS, j.size, j + tsPackets(10))
        assertAt(Container.PACKED_AUDIO, j.size, j + ac3(10))
        val b = bmp()
        assertAt(Container.TS, b.size, b + tsPackets(10, 192))
        // Pictures in a row.
        assertAt(Container.TS, gif.size + j.size, gif + j + tsPackets(10))
    }

    @Test
    fun id3InFrontOfAContainerIsSkipped() {
        val tag = id3(100)
        assertAt(Container.TS, tag.size, tag + tsPackets(10))
        assertAt(Container.MKV, tag.size, tag + ebml())
        // In front of audio the tag stays: it carries the packed-audio timestamp.
        assertAt(Container.PACKED_AUDIO, 0, tag + adts(10))
    }

    @Test
    fun junkInFrontIsSearched() {
        val j = junk(1000)
        assertAt(Container.TS, j.size, j + tsPackets(10))
        assertAt(Container.TS, j.size, j + tsPackets(10, 192))
        assertAt(Container.PACKED_AUDIO, j.size, j + adts(10))
        assertAt(Container.PACKED_AUDIO, j.size, j + ac3(10))
        assertAt(Container.MKV, j.size, j + ebml())
        // A malformed `free` box (size smaller than its header) in front of a moov.
        val moov = byteArrayOf(0, 0, 0, 16) + "moov".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val free = byteArrayOf(0, 0, 0, 4) + "free".toByteArray(Charsets.US_ASCII)
        assertAt(Container.MP4, j.size, j + free + moov)
    }

    @Test
    fun loneSyncBytesAreNotMedia() {
        // 0x47 / 0xFFF1 / 0x0B77 / EBML magic with nothing confirming them.
        val fake = byteArrayOf(0x47) + junk(600, 2) + byteArrayOf(0xFF.toByte(), 0xF1.toByte()) + junk(600, 3) +
            byteArrayOf(0x0B, 0x77) + junk(600, 4) + byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + junk(600, 5)
        val found = locate(fake)
        assert(found == null) { "found $found in junk" }
    }

    @Test
    fun aPictureAloneIsNoMedia() {
        assert(locate(gif) == null) { "a GIF alone was taken for media" }
        assert(locate(png(2000)) == null) { "a PNG alone was taken for media" }
    }

    @Test
    fun containerFromTheFileName() {
        assert(SegmentProbe.fromName("/tmp/temp-3.tmp.ts") == Container.TS) { "ts" }
        assert(SegmentProbe.fromName("/tmp/temp-3.tmp.m4s.enc") == Container.MP4) { "m4s.enc" }
        assert(SegmentProbe.fromName("C:\\t\\temp-3.tmp.webm") == Container.MKV) { "webm" }
        assert(SegmentProbe.fromName("/tmp/temp-3.tmp.aac") == Container.PACKED_AUDIO) { "aac" }
        assert(SegmentProbe.fromName("/tmp/temp-3.tmp.") == null) { "no extension" }
    }
}
