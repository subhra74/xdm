// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.transmux.es.AdtsReader
import xdm.core.media.muxer.transmux.es.Ac3Reader
import xdm.core.media.muxer.transmux.es.ElementaryStreamReader
import xdm.core.media.muxer.transmux.es.H264Reader
import xdm.core.media.muxer.transmux.es.H265Reader
import xdm.core.media.muxer.transmux.mp4.presentationEnd
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.SampleTable
import xdm.core.media.muxer.transmux.ts.TsDemuxer
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Encoder layouts of PES packets inside MPEG-TS, built synthetically: frames split across PES
 * (with and without PTS on the continuations, start codes cut in half), audio frames straddling PES
 * boundaries, several pictures in one PES, PIDs changing between segments, M2TS/204-byte packets
 * and private-data audio identified by content. Each must yield exactly the samples of the input.
 */
class TestTsPesLayouts {

    private val rnd = Random(42)

    /** Payload bytes with no zeros, so they never form a start code. */
    private fun payload(n: Int) = ByteArray(n) { (rnd.nextInt(1, 256)).toByte() }

    // ---- H.264 / H.265 access units ----

    private class Au(val nals: List<ByteArray>, val pts: Long, val dts: Long, val key: Boolean)

    /** Annex-B bytes of [au], every NAL behind a 4-byte start code. */
    private fun annexB(au: Au): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in au.nals) { out.write(byteArrayOf(0, 0, 0, 1)); out.write(n) }
        return out.toByteArray()
    }

    /** What the reader must emit for [au]: every NAL but the AUD, 4-byte length prefixed. */
    private fun expectedSample(au: Au, isAud: (ByteArray) -> Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in au.nals) {
            if (isAud(n)) continue
            out.write(byteArrayOf((n.size ushr 24).toByte(), (n.size ushr 16).toByte(), (n.size ushr 8).toByte(), n.size.toByte()))
            out.write(n)
        }
        return out.toByteArray()
    }

    /** H.264: AUD, SPS/PPS on keyframes, then one or two slices (the second not starting the picture). */
    private fun h264Aus(count: Int, slices: Int = 1): List<Au> = List(count) { i ->
        val key = i % 10 == 0
        val nals = ArrayList<ByteArray>()
        nals += byteArrayOf(0x09, 0xF0.toByte())
        if (key) { nals += byteArrayOf(0x67) + payload(12); nals += byteArrayOf(0x68) + payload(4) }
        for (s in 0 until slices) {
            val header = if (key) 0x65 else 0x41
            // first_mb_in_slice: 0 => first bit '1' (0x88); a later slice starts with a '0' bit (0x40).
            nals += byteArrayOf(header.toByte(), (if (s == 0) 0x88 else 0x40).toByte()) + payload(if (key) 9000 else 1500 + rnd.nextInt(500))
        }
        // B-frame-like reordering: pts runs 2 frames behind dts on non-keyframes.
        Au(nals, 3000L * i + (if (key) 0 else 6000), 3000L * i, key)
    }

    private fun h265Aus(count: Int): List<Au> = List(count) { i ->
        val key = i % 10 == 0
        val nals = ArrayList<ByteArray>()
        nals += byteArrayOf((35 shl 1).toByte(), 1, 0x50)          // AUD
        if (key) {
            nals += byteArrayOf((32 shl 1).toByte(), 1) + payload(10) // VPS
            nals += byteArrayOf((33 shl 1).toByte(), 1) + payload(20) // SPS
            nals += byteArrayOf((34 shl 1).toByte(), 1) + payload(6)  // PPS
        }
        val type = if (key) 19 else 1                                 // IDR_W_RADL / TRAIL_R
        nals += byteArrayOf((type shl 1).toByte(), 1, 0x80.toByte()) + payload(if (key) 9000 else 1500)
        Au(nals, 3000L * i, 3000L * i, key)
    }

    /** Feeds [aus] as PES payloads: [chunk] bytes per PES, PTS on the first or on every chunk. */
    private fun feedVideo(reader: ElementaryStreamReader, aus: List<Au>, chunk: Int, ptsOnContinuations: Boolean) {
        for (au in aus) {
            val b = annexB(au)
            var k = 0
            while (k < b.size) {
                val n = minOf(chunk, b.size - k)
                val withPts = k == 0 || ptsOnContinuations
                reader.consume(b, k, n, if (withPts) au.pts else -1, if (withPts) au.dts else -1)
                k += n
            }
        }
        reader.finish()
    }

    private fun assertSamples(aus: List<Au>, reader: ElementaryStreamReader, sink: RecordingSink, isAud: (ByteArray) -> Boolean) {
        val samples = reader.track.samples
        assertEquals(aus.size, samples.size, "one sample per access unit")
        val expected = ByteArrayOutputStream()
        for ((i, au) in aus.withIndex()) {
            val e = expectedSample(au, isAud)
            expected.write(e)
            assertEquals(e.size, samples[i].size, "sample $i size")
            assertEquals(au.pts, samples[i].pts, "sample $i pts")
            assertEquals(au.dts, samples[i].dts, "sample $i dts")
            assertEquals(au.key, samples[i].isKeyframe, "sample $i keyframe flag")
        }
        assertArrayEquals(expected.toByteArray(), sink.bytes.toByteArray(), "sample bytes")
    }

    private val h264Aud = { n: ByteArray -> (n[0].toInt() and 0x1F) == 9 }
    private val h265Aud = { n: ByteArray -> ((n[0].toInt() shr 1) and 0x3F) == 35 }

    @Test
    fun h264OneAccessUnitPerPes() {
        val aus = h264Aus(30)
        val sink = RecordingSink(); val r = H264Reader(sink)
        feedVideo(r, aus, Int.MAX_VALUE, false)
        assertSamples(aus, r, sink, h264Aud)
    }

    @Test
    fun h264AccessUnitSplitAcrossPesWithoutPts() {
        val aus = h264Aus(30)
        val sink = RecordingSink(); val r = H264Reader(sink)
        feedVideo(r, aus, 4000, ptsOnContinuations = false)
        assertSamples(aus, r, sink, h264Aud)
    }

    @Test
    fun h264AccessUnitSplitAcrossPesRepeatingPts() {
        val aus = h264Aus(30)
        val sink = RecordingSink(); val r = H264Reader(sink)
        feedVideo(r, aus, 4000, ptsOnContinuations = true)
        assertSamples(aus, r, sink, h264Aud)
    }

    @Test
    fun h264TinyPesCutsStartCodes() {
        // 7-byte PES payloads cut start codes and NAL headers at every possible offset.
        val aus = h264Aus(12)
        val sink = RecordingSink(); val r = H264Reader(sink)
        feedVideo(r, aus, 7, ptsOnContinuations = false)
        assertSamples(aus, r, sink, h264Aud)
    }

    @Test
    fun h264MultiSlicePicturesStayOneSample() {
        val aus = h264Aus(20, slices = 2)
        val sink = RecordingSink(); val r = H264Reader(sink)
        feedVideo(r, aus, 1000, ptsOnContinuations = false)
        assertSamples(aus, r, sink, h264Aud)
    }

    @Test
    fun h264SeveralPicturesInOnePesGetExtrapolatedTimestamps() {
        val aus = h264Aus(20).map { Au(it.nals, it.dts, it.dts, it.key) } // no reordering
        val sink = RecordingSink(); val r = H264Reader(sink)
        // Two access units per PES; only the first one's PTS is signalled.
        for (i in aus.indices step 2) {
            val b = annexB(aus[i]) + (if (i + 1 < aus.size) annexB(aus[i + 1]) else ByteArray(0))
            r.consume(b, 0, b.size, aus[i].pts, aus[i].dts)
        }
        r.finish()
        assertSamples(aus, r, sink, h264Aud)
    }

    @Test
    fun h264BytesBeforeFirstStartCodeAreDropped() {
        val aus = h264Aus(5)
        val sink = RecordingSink(); val r = H264Reader(sink)
        val junk = payload(50)
        r.consume(junk, 0, junk.size, -1, -1)
        feedVideo(r, aus, Int.MAX_VALUE, false)
        assertSamples(aus, r, sink, h264Aud)
    }

    @Test
    fun h265AccessUnitSplitAcrossPesWithoutPts() {
        val aus = h265Aus(30)
        val sink = RecordingSink(); val r = H265Reader(sink)
        feedVideo(r, aus, 3333, ptsOnContinuations = false)
        assertSamples(aus, r, sink, h265Aud)
    }

    // ---- audio frames straddling PES packets ----

    /** ADTS frames (48 kHz, stereo, LC) of varying size with no CRC. */
    private fun adtsFrames(count: Int): List<ByteArray> = List(count) {
        val raw = payload(200 + rnd.nextInt(300))
        val len = raw.size + 7
        val h = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((1 shl 6) or (3 shl 2) or 0).toByte(),            // LC, 48 kHz, channel bit 2 = 0
            ((2 shl 6) or ((len shr 11) and 0x3)).toByte(),    // 2 channels
            ((len shr 3) and 0xFF).toByte(),
            (((len and 0x7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
        h + raw
    }

    /**
     * Concatenates [frames] (each [ticksPerFrame] 90 kHz ticks long) and cuts the stream into
     * [chunk]-byte PES payloads; each PES carries the PTS of the first frame starting in it.
     */
    private fun feedAudio(reader: ElementaryStreamReader, frames: List<ByteArray>, chunk: Int, ticksPerFrame: Long, prefix: ByteArray = ByteArray(0)) {
        val starts = ArrayList<Int>()
        val all = ByteArrayOutputStream()
        all.write(prefix)
        for (f in frames) { starts += all.size(); all.write(f) }
        val b = all.toByteArray()
        var k = 0
        while (k < b.size) {
            val n = minOf(chunk, b.size - k)
            val first = starts.indexOfFirst { it >= k && it < k + n }
            reader.consume(b, k, n, if (first >= 0) 900_000L + first * ticksPerFrame else -1, -1)
            k += n
        }
        reader.finish()
    }

    @Test
    fun adtsFramesStraddlingPesAreKept() {
        val frames = adtsFrames(200)
        val sink = RecordingSink(); val r = AdtsReader(sink)
        feedAudio(r, frames, 333, 1920) // 1024 samples @ 48 kHz = 1920 ticks
        val samples = r.track.samples
        assertEquals(frames.size, samples.size, "every ADTS frame becomes a sample")
        val expected = ByteArrayOutputStream()
        for (f in frames) expected.write(f, 7, f.size - 7)
        assertArrayEquals(expected.toByteArray(), sink.bytes.toByteArray(), "raw AAC bytes")
        for (i in samples.indices) assertEquals(480_000L + 1024L * i, samples[i].pts, "frame $i timestamp")
    }

    @Test
    fun adtsFalseSyncInLeadingJunkIsRejected() {
        val frames = adtsFrames(50)
        val sink = RecordingSink(); val r = AdtsReader(sink)
        // Junk that looks like an ADTS header claiming a 300-byte frame, which no header follows.
        val junk = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x4C, 0x80.toByte(), 0x25, 0x9F.toByte(), 0xFC.toByte()) + payload(20)
        feedAudio(r, frames, 400, 1920, prefix = junk)
        assertEquals(frames.size, r.track.samples.size, "the fake header in the junk produced no sample")
    }

    /** AC-3 frames: 48 kHz, frmsizecod 8 => 128 words = 256 bytes. */
    private fun ac3Frames(count: Int): List<ByteArray> = List(count) {
        val f = payload(256)
        f[0] = 0x0B; f[1] = 0x77
        f[4] = 8                        // fscod 0 (48 kHz), frmsizecod 8
        f[5] = ((8 shl 3) or 0).toByte() // bsid 8, bsmod 0
        f[6] = (2 shl 5).toByte()       // acmod 2 (stereo)
        // Keep 0x0B77 out of the frame body.
        for (j in 2 until f.size - 1) if (f[j] == 0x0B.toByte() && f[j + 1] == 0x77.toByte()) f[j + 1] = 0x78
        f
    }

    @Test
    fun ac3FramesStraddlingPesAreKept() {
        val frames = ac3Frames(100)
        val sink = RecordingSink(); val r = Ac3Reader(sink, eac3 = false)
        feedAudio(r, frames, 100, 2880) // 1536 samples @ 48 kHz
        assertEquals(frames.size, r.track.samples.size, "every AC-3 frame becomes a sample")
        assertArrayEquals(frames.reduce { a, b -> a + b }, sink.bytes.toByteArray(), "AC-3 bytes")
    }

    // ---- transport stream level ----

    /** One segment: PAT/PMT, then interleaved video access units and ADTS frames. */
    private fun segment(b: TsBuilder, pmtPid: Int, vPid: Int, aPid: Int, aus: List<Au>, frames: List<ByteArray>, t0: Long, aType: Int = 0x0F) {
        b.pat(pmtPid)
        b.pmt(pmtPid, listOf(0x1B to vPid, aType to aPid))
        val perAu = (frames.size + aus.size - 1) / aus.size
        for ((i, au) in aus.withIndex()) {
            b.pes(vPid, 0xE0, t0 + au.pts, annexB(au))
            val fs = frames.drop(i * perAu).take(perAu)
            if (fs.isNotEmpty()) b.pes(aPid, 0xC0, t0 + 3000L * i, fs.reduce { x, y -> x + y })
        }
    }

    private fun demux(ts: ByteArray, chunk: Int = 4096): TsDemuxer = demuxTs(ts, chunk)

    private fun videoAus(count: Int) = h264Aus(count).map { Au(it.nals, it.dts, it.dts, it.key) }

    @Test
    fun pidsChangingAfterDiscontinuityContinueTheSameTracks() {
        val b = TsBuilder()
        segment(b, 0x1000, 0x100, 0x101, videoAus(20), adtsFrames(30), 0)
        // Second encoder: other PMT PID, other ES PIDs, timestamps restarting.
        segment(b, 0x1100, 0x200, 0x201, videoAus(20), adtsFrames(30), 0)
        val d = demux(b.out.toByteArray())
        assertEquals(listOf(Codec.H264, Codec.AAC), d.tracks.map { it.codec }, "still one video and one audio track")
        assertEquals(40, d.tracks[0].samples.size, "video from both encoders")
        assertEquals(60, d.tracks[1].samples.size, "audio from both encoders")
    }

    private fun simpleStream(): ByteArray {
        val b = TsBuilder()
        segment(b, 0x1000, 0x100, 0x101, videoAus(20), adtsFrames(30), 0)
        return b.out.toByteArray()
    }

    @Test
    fun m2tsPacketsWithTimestampHeader() {
        val ts = simpleStream()
        val m2ts = ByteArrayOutputStream()
        for (k in ts.indices step 188) { m2ts.write(byteArrayOf(0x0E, 0xBF.toByte(), 0x46, 0x22)); m2ts.write(ts, k, 188) }
        val d = demux(m2ts.toByteArray(), chunk = 1000)
        assertEquals(listOf(20, 30), d.tracks.map { it.samples.size }, "all samples from 192-byte packets")
    }

    @Test
    fun packetsWithFecTrailer() {
        val ts = simpleStream()
        val fec = ByteArrayOutputStream()
        for (k in ts.indices step 188) { fec.write(ts, k, 188); fec.write(ByteArray(16)) }
        val d = demux(fec.toByteArray(), chunk = 1000)
        assertEquals(listOf(20, 30), d.tracks.map { it.samples.size }, "all samples from 204-byte packets")
    }

    @Test
    fun privateDataAudioIsIdentifiedByContent() {
        val b = TsBuilder()
        segment(b, 0x100, 0x1011, 0x1100, videoAus(20), adtsFrames(30), 0, aType = 0x06)
        val d = demux(b.out.toByteArray())
        assertEquals(listOf(Codec.H264, Codec.AAC), d.tracks.map { it.codec }, "0x06 stream with ADTS content read as AAC")
        assertEquals(30, d.tracks[1].samples.size, "all AAC frames")
    }

    // ---- MP4 edit list ----

    @Test
    fun presentationEndCoversReorderedTail() {
        // I P B B decode order; pts runs two frames ahead at the end.
        val samples = SampleTable()
        for ((dts, pts) in listOf(0L to 6000L, 3000L to 15000L, 6000L to 9000L, 9000L to 12000L)) samples.add(0, 1, pts, dts, false)
        samples.deriveDurations() // 3000 each
        // Presented: pts 6000..15000 + 3000 => ends 18000 - first dts 0 = 18000; decode span is only 12000.
        assertEquals(18000L, presentationEnd(samples), "edit must reach the last presented frame's end")
    }
}
