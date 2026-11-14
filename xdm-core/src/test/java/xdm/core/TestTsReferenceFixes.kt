// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.transmux.es.AdtsReader
import xdm.core.media.muxer.transmux.es.H264Reader
import xdm.core.media.muxer.transmux.mp4.BoxBuf
import xdm.core.media.muxer.transmux.mp4.CodecBoxes
import xdm.core.media.muxer.OutputKind
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Timeline
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.media.muxer.transmux.ts.TimestampUnwrapper
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Transport stream reading cases from the reference review against ffmpeg / hls.js / mux.js
 * (33-bit wrap, packets before the first PMT, untimed leading frames, languages): each is what
 * ffmpeg's demuxer makes of the same bytes.
 */
class TestTsReferenceFixes {

    private val rnd = Random(7)
    private val wrap = 1L shl 33

    private fun payload(n: Int) = ByteArray(n) { (rnd.nextInt(1, 256)).toByte() }

    /** Annex-B H.264 access unit: AUD, SPS/PPS on keyframes, one slice starting the picture. */
    private fun h264Au(key: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        fun nal(b: ByteArray) { out.write(byteArrayOf(0, 0, 0, 1)); out.write(b) }
        nal(byteArrayOf(0x09, 0xF0.toByte()))
        if (key) { nal(byteArrayOf(0x67) + payload(12)); nal(byteArrayOf(0x68) + payload(4)) }
        nal(byteArrayOf((if (key) 0x65 else 0x41).toByte(), 0x88.toByte()) + payload(if (key) 3000 else 600))
        return out.toByteArray()
    }

    /** ADTS AAC-LC 48 kHz stereo frames (1024 samples = 1920 ticks at 90 kHz). */
    private fun adtsFrame(): ByteArray {
        val raw = payload(200 + rnd.nextInt(100))
        val len = raw.size + 7
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (3 shl 2)).toByte(),
            ((2 shl 6) or ((len shr 11) and 0x3)).toByte(), ((len shr 3) and 0xFF).toByte(),
            (((len and 0x7) shl 5) or 0x1F).toByte(), 0xFC.toByte()
        ) + raw
    }

    // ---- R1 / R2: 33-bit wrap ----

    @Test
    fun unwrapperFollowsFfmpegsWrapReference() {
        // ffmpeg (demux.c update_wrap_reference / wrap_timestamp): one reference 60 s before the first
        // timestamp; a value below it has wrapped (+2^33).
        val u = TimestampUnwrapper()
        assertEquals(wrap - 3600, u.unwrap(wrap - 3600), "first value as it is")
        assertEquals(wrap + 3600, u.unwrap(3600), "after the wrap")
        assertEquals(wrap - 1, u.unwrap(wrap - 1), "a reordered frame from before the wrap stays before it")
        assertEquals(wrap + 7200, u.unwrap(7200), "no second wrap")
        assertEquals(-1L, u.unwrap(-1), "no timestamp stays none")
        u.reset()
        val first = 111L * 90_000
        assertEquals(first, u.unwrap(first), "a new section starts afresh")
        assertEquals(first - 11 * 90_000, u.unwrap(first - 11 * 90_000), "less than 60 s before the first: not wrapped")
        assertEquals(first - 100 * 90_000 + wrap, u.unwrap(first - 100 * 90_000), "more than 60 s before the first: wrapped")
        u.reset()
        assertEquals(500L, u.unwrap(500), "first timestamp under 60 s")
        assertEquals(wrap - 1000, u.unwrap(wrap - 1000), "its reference is negative: nothing wraps, as in ffmpeg")
    }

    @Test
    fun ptsOnlyBFramesAcrossTheWrapStayOnOneTimeline() {
        // Decode order I P B B P B B ..., PTS only; the 33-bit clock wraps 10 frames in.
        val t0 = wrap - 10 * 3000L
        val b = TsBuilder()
        b.pat(0x1000); b.pmt(0x1000, listOf(0x1B to 0x100))
        val expected = ArrayList<Long>()
        for (i in 0 until 40) {
            // Display index: I0 P3 B1 B2 P6 B4 B5 ...
            val display = when { i == 0 -> 0; i % 3 == 1 -> i + 2; else -> i - 1 }
            val pts = t0 + 6000 + display * 3000L
            expected += pts
            b.pes(0x100, 0xE0, pts and (wrap - 1), h264Au(i % 30 == 0))
        }
        val v = demuxTs(b.out.toByteArray()).tracks.single()
        assertEquals(40, v.samples.size, "every picture")
        for (i in 0 until 40) assertEquals(expected[i], v.samples[i].pts, "picture $i PTS")
        for (i in 1 until 40) assertTrue(v.samples[i].dts > v.samples[i - 1].dts, "decode times increase at $i")
    }

    private fun avAcrossWrap(audioFirst: Boolean, boundedAudio: Boolean = false): Pair<Double, Double> {
        val b = TsBuilder()
        b.pat(0x1000); b.pmt(0x1000, listOf(0x1B to 0x100, 0x0F to 0x101))
        val aStart = wrap - 1000
        val vStart = 500L
        for (i in 0 until 20) {
            val audio = { b.pes(0x101, 0xC0, (aStart + i * 1920L) and (wrap - 1), adtsFrame(), bounded = boundedAudio) }
            if (audioFirst) audio()
            b.pes(0x100, 0xE0, vStart + i * 3000L, h264Au(i == 0))
            if (!audioFirst) audio()
        }
        val d = demuxTs(b.out.toByteArray())
        val v = d.tracks.first { it.codec == Codec.H264 }
        val a = d.tracks.first { it.codec == Codec.AAC }
        return v.samples[0].pts / 90_000.0 to a.samples[0].pts / a.timescale.toDouble()
    }

    @Test
    fun audioBeforeTheWrapAndVideoAfterItStayTogether() {
        val (v, a) = avAcrossWrap(audioFirst = true)
        assertEquals(1500 / 90_000.0, v - a, 1e-3, "video 1500 ticks after the audio (video $v s, audio $a s)")
    }

    @Test
    fun boundedAudioPesSetsTheWrapReferenceBeforeTheVideoAheadOfIt() {
        // Video from after the wrap first in the file, audio from before it in bounded PES (as muxers write
        // audio): the audio PES is complete first, so it sets the reference, as ffmpeg's first packet out.
        val (v, a) = avAcrossWrap(audioFirst = false, boundedAudio = true)
        assertEquals(1500 / 90_000.0, v - a, 1e-3, "video 1500 ticks after the audio (video $v s, audio $a s)")
    }

    // ---- R9: packets before the first PMT ----

    @Test
    fun packetsBeforeTheFirstPmtAreKept() {
        val b = TsBuilder()
        b.pat(0x1000); b.pmt(0x1000, listOf(0x1B to 0x100, 0x0F to 0x101))
        for (i in 0 until 30) {
            b.pes(0x100, 0xE0, 900_000L + i * 3000L, h264Au(i % 10 == 0))
            b.pes(0x101, 0xC0, 900_000L + i * 1920L, adtsFrame())
        }
        val ts = b.out.toByteArray()
        val reference = demuxTs(ts).tracks.map { it.samples.size }
        // PAT and PMT moved behind the first 100 audio/video packets.
        val packets = (0 until ts.size / 188).map { ts.copyOfRange(it * 188, it * 188 + 188) }
        fun pid(p: ByteArray) = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val psi = packets.filter { pid(it) == 0 || pid(it) == 0x1000 }
        val av = packets.filter { pid(it) != 0 && pid(it) != 0x1000 }
        val late = ByteArrayOutputStream()
        for (p in av.take(100)) late.write(p)
        for (p in psi) late.write(p)
        for (p in av.drop(100)) late.write(p)
        val d = demuxTs(late.toByteArray())
        assertEquals(listOf(30, 30), reference, "the stream as built")
        assertEquals(reference, d.tracks.map { it.samples.size }, "every frame, as with the PMT in front")
    }

    // ---- R3: frames before the first PTS ----

    @Test
    fun audioBeforeTheFirstPtsEndsWhereItStarts() {
        val r = AdtsReader(RecordingSink())
        for (i in 0 until 3) { val f = adtsFrame(); r.consume(f, 0, f.size, -1, -1) }
        for (i in 0 until 3) { val f = adtsFrame(); r.consume(f, 0, f.size, if (i == 0) 900_000L else -1, -1) }
        r.finish()
        val s = r.track.samples
        assertEquals(6, s.size, "every frame")
        // 10 s at 48 kHz = 480000; the three untimed frames lead up to it.
        assertEquals((0 until 6).map { 480_000L + (it - 3) * 1024L }, (0 until 6).map { s.pts(it) }, "frame times")
    }

    @Test
    fun videoBeforeTheFirstPtsEndsWhereItStarts() {
        val r = H264Reader(RecordingSink())
        for (i in 0 until 5) {
            val au = h264Au(i == 0)
            r.consume(au, 0, au.size, if (i >= 2) 900_000L + (i - 2) * 3600L else -1, -1)
        }
        r.finish()
        val s = r.track.samples
        assertEquals(5, s.size, "every picture")
        // The cadence isn't known yet: 3000 ticks (30 fps) per picture.
        assertEquals(listOf(900_000L - 6000, 900_000L - 3000), (0 until 2).map { s.dts(it) }, "untimed pictures placed back from the first timestamp")
        assertEquals(900_000L, s.dts(2), "first timed picture")
    }

    // ---- R7: section joins in decode time ----

    @Test
    fun aSectionWithADeeperReorderDelayStartsDecodingAfterThePreviousOne() {
        val v = Track(Codec.H264)
        // Section 1: one frame of reorder delay (pts = dts + 3000). Section 2 restarts its clock with
        // two frames of delay: its first DTS is 6000 before its first PTS.
        for (i in 0 until 30) v.samples.add(0, 1, i * 3000L + 3000, i * 3000L, i == 0)
        v.sectionBreaks += 30
        for (i in 0 until 30) v.samples.add(0, 1, 900_000L + i * 3000L + 6000, 900_000L + i * 3000L, i == 0)
        val a = Track(Codec.AAC).apply { timescale = 48000 }
        for (i in 0 until 47) a.samples.add(0, 1, i * 1024L, i * 1024L, true)
        a.sectionBreaks += 47
        for (i in 0 until 47) a.samples.add(0, 1, 480_000L + i * 1024L, 480_000L + i * 1024L, true)
        Timeline.prepare(listOf(v, a), true)
        for (i in 1 until v.samples.size) assertTrue(v.samples.dts(i) > v.samples.dts(i - 1), "video decode time goes on at $i: ${v.samples.dts(i - 1)} -> ${v.samples.dts(i)}")
        for (i in 1 until a.samples.size) assertTrue(a.samples.dts(i) > a.samples.dts(i - 1), "audio decode time goes on at $i")
        // Both tracks moved by the same amount: the section's own A/V offset (6000 ticks) is kept.
        val vStart = v.samples.pts(30) / 90_000.0
        val aStart = a.samples.pts(47) / 48_000.0
        assertEquals(6000 / 90_000.0, vStart - aStart, 1.0 / 48_000 + 1e-9, "A/V offset of section 2")
    }

    // ---- R10: PSI CRC ----

    private fun corruptPsiCrcs(ts: ByteArray, pids: Set<Int>, count: Int = Int.MAX_VALUE): ByteArray {
        val out = ts.copyOf()
        var n = 0
        for (o in 0 until out.size step 188) {
            val pid = ((out[o + 1].toInt() and 0x1F) shl 8) or (out[o + 2].toInt() and 0xFF)
            if (pid !in pids || n >= count) continue
            val p = o + 5 + (out[o + 4].toInt() and 0xFF)
            val end = p + 3 + (((out[p + 1].toInt() and 0x0F) shl 8) or (out[p + 2].toInt() and 0xFF))
            out[end - 1] = (out[end - 1].toInt() xor 0x5A).toByte()
            n++
        }
        return out
    }

    private val eng = byteArrayOf(0x0A, 4, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0)

    @Test
    fun aPmtWithABadCrcIsIgnored() {
        val b = TsBuilder()
        b.pat(0x1000); b.pmt(0x1000, listOf(TsStream(0x1B, 0x100), TsStream(0x0F, 0x101, eng)))
        val good = b.out.size()
        // A damaged PMT that would drop the audio and declare a new PID.
        b.pmt(0x1000, listOf(TsStream(0x1B, 0x100), TsStream(0x0F, 0x102)))
        val damagedEnd = b.out.size()
        for (i in 0 until 20) {
            b.pes(0x100, 0xE0, 900_000L + i * 3000L, h264Au(i % 10 == 0))
            b.pes(0x101, 0xC0, 900_000L + i * 1920L, adtsFrame())
        }
        val ts = b.out.toByteArray()
        // Only the second PMT's CRC is broken.
        val damaged = ts.copyOfRange(0, good) + corruptPsiCrcs(ts.copyOfRange(good, damagedEnd), setOf(0x1000)) + ts.copyOfRange(damagedEnd, ts.size)
        val d = demuxTs(damaged)
        assertEquals(listOf(20, 20), d.tracks.map { it.samples.size }, "audio kept on its PID")
        assertEquals("eng", d.tracks[1].language, "language from the good PMT")
    }

    @Test
    fun sectionsThatOnlyHaveBadCrcsAreBelievedAfterTen() {
        val b = TsBuilder()
        for (k in 0 until 12) { b.pat(0x1000); b.pmt(0x1000, listOf(TsStream(0x1B, 0x100), TsStream(0x0F, 0x101, eng))) }
        for (i in 0 until 20) {
            b.pes(0x100, 0xE0, 900_000L + i * 3000L, h264Au(i % 10 == 0))
            b.pes(0x101, 0xC0, 900_000L + i * 1920L, adtsFrame())
        }
        val d = demuxTs(corruptPsiCrcs(b.out.toByteArray(), setOf(0, 0x1000)))
        assertEquals(listOf(20, 20), d.tracks.map { it.samples.size }, "every frame")
        assertEquals("eng", d.tracks[1].language, "the PMT was believed (its descriptor read)")
    }

    // ---- R11: programs ----

    @Test
    fun theFirstProgramWithAudioOrVideoIsUsed() {
        val b = TsBuilder()
        // Program 1: data only (stream type 0x05, private sections); program 2: H.264 + AAC.
        val pat = byteArrayOf(0, 1, (0xE0 or (0x1000 shr 8)).toByte(), 0x00, 0, 2, (0xE0 or (0x1100 shr 8)).toByte(), 0x00)
        repeat(3) {
            b.section(0, 0, pat)
            b.pmt(0x1000, listOf(TsStream(0x05, 0x300)))
            b.pmt(0x1100, listOf(TsStream(0x1B, 0x100), TsStream(0x0F, 0x101)))
        }
        for (i in 0 until 20) {
            b.pes(0x100, 0xE0, 900_000L + i * 3000L, h264Au(i % 10 == 0))
            b.pes(0x101, 0xC0, 900_000L + i * 1920L, adtsFrame())
        }
        val d = demuxTs(b.out.toByteArray())
        assertEquals(listOf(Codec.H264, Codec.AAC), d.tracks.map { it.codec }, "program 2's streams")
        assertEquals(listOf(20, 20), d.tracks.map { it.samples.size }, "every frame")
    }

    // ---- R12: H.264 picture starts ----

    /** A non-IDR slice NAL (Annex-B) starting at macroblock [firstMb]. */
    private fun slice(firstMb: Int, key: Boolean = false): ByteArray {
        val w = BitWriter()
        val x = firstMb + 1
        val n = 32 - Integer.numberOfLeadingZeros(x)
        w.bits(0, n - 1); w.bits(x, n)  // first_mb_in_slice ue(v)
        w.bits(1, 1)                     // slice_type ue(v) = 0
        return byteArrayOf(0, 0, 0, 1, (if (key) 0x65 else 0x41).toByte()) + w.toByteArray() + payload(300)
    }

    @Test
    fun aPictureWhoseFirstSliceIsLostStillStartsANewSample() {
        val r = H264Reader(RecordingSink())
        val sps = byteArrayOf(0, 0, 0, 1, 0x67) + payload(12) + byteArrayOf(0, 0, 0, 1, 0x68) + payload(4)
        val pictures = listOf(
            sps + slice(0, key = true) + slice(50, key = true),
            slice(0) + slice(50),
            slice(50),                 // its slice at 0 lost
            slice(0) + slice(50),
        )
        for ((i, p) in pictures.withIndex()) r.consume(p, 0, p.size, 900_000L + i * 3000L, -1)
        r.finish()
        assertEquals(4, r.track.samples.size, "one sample per picture, as ffmpeg's parser splits them")
    }

    // ---- R13: ADTS with several raw data blocks and CRC ----

    @Test
    fun adtsBlocksWithCrcBecomeOneSampleEach() {
        val blocks = List(3) { payload(150 + it * 20) }
        // Positions from the start of raw_data_block(0), each block followed by its 2-byte CRC.
        val pos1 = blocks[0].size + 2
        val pos2 = pos1 + blocks[1].size + 2
        val body = byteArrayOf((pos1 shr 8).toByte(), pos1.toByte(), (pos2 shr 8).toByte(), pos2.toByte(), 0x12, 0x34) +
            blocks[0] + byteArrayOf(1, 2) + blocks[1] + byteArrayOf(3, 4) + blocks[2] + byteArrayOf(5, 6)
        val len = 7 + body.size
        val header = byteArrayOf(
            0xFF.toByte(), 0xF0.toByte(), ((1 shl 6) or (3 shl 2)).toByte(),        // CRC present; LC, 48 kHz
            ((2 shl 6) or ((len shr 11) and 0x3)).toByte(), ((len shr 3) and 0xFF).toByte(),
            (((len and 0x7) shl 5) or 0x1F).toByte(), (0xFC or 2).toByte()           // 3 raw data blocks
        )
        val frame = header + body
        val sink = RecordingSink(); val r = AdtsReader(sink)
        val two = frame + frame
        r.consume(two, 0, two.size, 90_000L, -1); r.finish()
        val s = r.track.samples
        assertEquals(6, s.size, "one sample per raw data block")
        assertEquals((0 until 6).map { 48_000L + it * 1024L }, (0 until 6).map { s.pts(it) }, "1024 samples each")
        val expected = ByteArrayOutputStream()
        repeat(2) { for (b in blocks) expected.write(b) }
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected.toByteArray(), sink.bytes.toByteArray(), "blocks without positions or CRCs")
    }

    // ---- R16: avcC / hvcC ----

    @Test
    fun avcCListsSpsExtensions() {
        val t = Track(Codec.H264)
        val ext = byteArrayOf(0x6D, 0x88.toByte(), 0x40)  // NAL 13, seq_parameter_set_id 0
        t.parameterSets += byteArrayOf(0x67, 100, 0, 30) + payload(8)
        t.parameterSets += byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        t.parameterSets += ext
        t.chromaFormat = 1; t.bitDepthLuma = 8; t.bitDepthChroma = 8
        val b = BoxBuf().also { CodecBoxes.writeAvcC(it, t) }.toByteArray()
        val tail = b.copyOfRange(b.size - 1 - 2 - ext.size, b.size)
        assertEquals(listOf(1, 0, ext.size) + ext.map { it.toInt() and 0xFF },
            tail.map { it.toInt() and 0xFF }, "numOfSequenceParameterSetExt 1, then the extension")
    }

    /** An H.265 parameter set NAL with general PTL ([b0] space/tier/profile, [compat], [level]) at RBSP [at]. */
    private fun hevcPs(type: Int, at: Int, b0: Int, compat: Int, constraint: Int, level: Int): ByteArray {
        val rbsp = ByteArray(at + 12 + 4) { 0x11 }
        rbsp[at] = b0.toByte()
        for (k in 0 until 4) rbsp[at + 1 + k] = (compat ushr (24 - 8 * k)).toByte()
        for (k in 0 until 6) rbsp[at + 5 + k] = constraint.toByte()
        rbsp[at + 11] = level.toByte()
        return byteArrayOf((type shl 1).toByte(), 1) + rbsp
    }

    @Test
    fun hvcCProfileTierLevelMergedOverVpsAndEverySps() {
        val t = Track(Codec.H265)
        t.parameterSets += hevcPs(32, 4, 0x01, 0x60000000, 0x90, 90)    // VPS: Main, level 3
        t.parameterSets += hevcPs(33, 1, 0x01, 0x60000000, 0xB0, 93)    // SPS: Main, level 3.1
        t.parameterSets += hevcPs(33, 1, 0x02, 0x20000000, 0x90, 120)   // SPS: Main 10, level 4
        t.chromaFormat = 1; t.bitDepthLuma = 10; t.bitDepthChroma = 10
        val b = BoxBuf().also { CodecBoxes.writeHvcC(it, t) }.toByteArray()
        assertEquals(0x02, b[9].toInt() and 0xFF, "highest profile, tier 0")
        assertEquals(listOf(0x20, 0, 0, 0), (10 until 14).map { b[it].toInt() and 0xFF }, "compatibility flags set in all")
        assertEquals(List(6) { 0x90 }, (14 until 20).map { b[it].toInt() and 0xFF }, "constraint flags set in all")
        assertEquals(120, b[20].toInt() and 0xFF, "highest level")
    }

    // ---- R17: PES without a header extension ----

    @Test
    fun pesWithoutAHeaderExtensionIsNotParsedAsOne() {
        val reader = object : xdm.core.media.muxer.transmux.es.ElementaryStreamReader {
            override val track = Track(Codec.UNKNOWN)
            var calls = 0
            override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) { calls++ }
            override fun finish() {}
        }
        val asm = xdm.core.media.muxer.transmux.ts.PesAssembler(reader, TimestampUnwrapper())
        for (id in listOf(0xBC, 0xF0, 0xF1, 0xF2, 0xF8, 0xFF)) {
            // Payload at byte 6; bytes 6-8 read as a header would claim a PTS and a 9-byte payload offset.
            val pes = byteArrayOf(0, 0, 1, id.toByte(), 0, 20, 0x80.toByte(), 0x80.toByte(), 5) + payload(14)
            asm.consumePacket(pes, 0, pes.size, true)
            asm.endPes()
        }
        assertEquals(0, reader.calls, "nothing handed to the reader")
    }

    // ---- safety net enforced ----

    @Test
    fun tsWhoseTimingCantBeWrittenIsSavedAsTs() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "xdm-remux-enforce").apply { deleteRecursively(); mkdirs() }
        try {
            val b = TsBuilder()
            b.pat(0x1000); b.pmt(0x1000, listOf(0x1B to 0x100, 0x0F to 0x101))
            for (i in 0 until 40) {
                // Two pictures presented at the same time (an encoder bug the remux can't express).
                val pts = 900_000L + (if (i == 21) 20 else i) * 3000L
                b.pes(0x100, 0xE0, pts, h264Au(i % 20 == 0))
                b.pes(0x101, 0xC0, 900_000L + i * 1920L, adtsFrame())
            }
            val seg = java.io.File(dir, "s0.ts").apply { writeBytes(b.out.toByteArray()) }
            val out = java.io.File(dir, "out.mp4").path
            val m = TransmuxingMuxer("/nonexistent-appdir", "mp4")
            assertTrue(m.mux(listOf(seg.path), out, {}, dir.path, false, false), "mux failed: ${m.lastError}")
            assertEquals(OutputKind.TS_FALLBACK, m.outputKind, "saved as the TS it was")
        } finally {
            dir.deleteRecursively()
        }
    }
}
