// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.core.media.muxer.transmux.es.Ac3Reader
import xdm.core.media.muxer.transmux.es.AdtsReader
import xdm.core.media.muxer.transmux.es.DtsReader
import xdm.core.media.muxer.transmux.es.ElementaryStreamReader
import xdm.core.media.muxer.transmux.es.LatmReader
import xdm.core.media.muxer.transmux.es.MpegAudioReader
import xdm.core.media.muxer.transmux.es.MpegVideoReader
import xdm.core.media.muxer.transmux.mp4.CodecBoxes
import xdm.core.media.muxer.transmux.mp4.Mp4Writer
import xdm.core.media.muxer.transmux.mp4.alignSections
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Codecs and stream layouts beyond plain H.264/AAC, built synthetically: audio whose config changes
 * mid-stream (an ad at another rate), E-AC-3 substreams and short frames, LATM, DTS, MPEG-1/2 and
 * MPEG-4 Part 2 video, PMTs spanning packets, Blu-ray stream types, probing, bounded PES, and the
 * MP4 writer's 64-bit boxes, ctts versions and discontinuity alignment.
 */
class TestTsCodecs {

    @TempDir
    lateinit var dir: File

    private val rnd = Random(11)

    /** Bytes 1..255 avoiding [avoid] (so no start codes or sync words appear by accident). */
    private fun body(n: Int, avoid: Set<Int> = setOf(0x0B, 0x7F, 0x64, 0x56, 0xFF)) =
        ByteArray(n) { var b: Int; do { b = rnd.nextInt(1, 256) } while (b in avoid); b.toByte() }

    /** Feeds [frames] concatenated, as PES payloads of [chunk] bytes with the PTS of the first frame starting in each. */
    private fun feed(reader: ElementaryStreamReader, frames: List<ByteArray>, chunk: Int, pts90k: List<Long>) {
        val starts = ArrayList<Int>()
        val all = ByteArrayOutputStream()
        for (f in frames) { starts += all.size(); all.write(f) }
        val b = all.toByteArray()
        var k = 0
        while (k < b.size) {
            val n = minOf(chunk, b.size - k)
            val first = starts.indexOfFirst { it >= k && it < k + n }
            reader.consume(b, k, n, if (first >= 0) pts90k[first] else -1, -1)
            k += n
        }
        reader.finish()
    }

    /** 90 kHz start of each frame for frames of the given (samples, rate). */
    private fun ptsOf(frames: List<Pair<Int, Int>>): List<Long> {
        var t = 0.0
        return frames.map { (samples, rate) -> Math.round(t).also { t += samples * 90_000.0 / rate } }
    }

    // ---- audio config changing mid-stream ----

    private fun adts(freqIndex: Int, channelConfig: Int, raw: Int): ByteArray {
        val len = raw + 7
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((1 shl 6) or (freqIndex shl 2) or (channelConfig shr 2)).toByte(),
            (((channelConfig and 3) shl 6) or ((len shr 11) and 3)).toByte(),
            ((len shr 3) and 0xFF).toByte(), (((len and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte()
        ) + body(raw)
    }

    @Test
    fun adtsRateChangeGetsItsOwnSampleEntryAndExactTiming() {
        val plan = List(10) { 3 } + List(10) { 4 } + List(5) { 3 } // 48 kHz, 44.1 kHz, 48 kHz
        val frames = plan.map { adts(it, 2, 200) }
        val sink = RecordingSink(); val r = AdtsReader(sink)
        feed(r, frames, 150, ptsOf(plan.map { 1024 to if (it == 3) 48000 else 44100 }))
        val s = r.track.samples
        assertEquals(25, s.size, "every frame kept")
        assertEquals(48000, r.track.timescale, "timescale stays the first rate")
        assertEquals(List(10) { 1 } + List(10) { 2 } + List(5) { 1 }, s.map { it.sampleDescriptionIndex }, "entries follow the config")
        assertNotNull(r.track.sampleEntryBox, "first config frozen")
        assertEquals(1, r.track.additionalSampleEntries.size, "one more entry, reused when 48 kHz returns")
        for (k in 0 until 10) assertEquals(10240L + k * 1024L * 48000 / 44100, s[10 + k].pts, "44.1 kHz frame $k in 48 kHz ticks")
    }

    private fun mp3(sampleRateIndex: Int, size: Int): ByteArray {
        // MPEG-1 Layer III, 128 kbps, no CRC, mono.
        val h = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), ((9 shl 4) or (sampleRateIndex shl 2)).toByte(), 0xC0.toByte())
        return h + body(size - 4)
    }

    @Test
    fun mpegAudioRateChangeKeepsTheFramesAfterIt() {
        val frames = List(8) { mp3(1, 384) } + List(8) { mp3(0, 417) } // 48 kHz, then 44.1 kHz
        val sink = RecordingSink(); val r = MpegAudioReader(sink)
        feed(r, frames, 500, ptsOf(List(8) { 1152 to 48000 } + List(8) { 1152 to 44100 }))
        assertEquals(16, r.track.samples.size, "frames after the change are not dropped")
        assertEquals(List(8) { 1 } + List(8) { 2 }, r.track.samples.map { it.sampleDescriptionIndex }, "new entry for 44.1 kHz")
    }

    private fun ac3Frame(fscod: Int, frmsizecod: Int, words: Int): ByteArray {
        val f = body(words * 2, setOf(0x0B))
        f[0] = 0x0B; f[1] = 0x77
        f[4] = ((fscod shl 6) or frmsizecod).toByte()
        f[5] = (8 shl 3).toByte()       // bsid 8
        f[6] = (2 shl 5).toByte()       // acmod 2
        return f
    }

    @Test
    fun ac3At32kHzUsesTheRightFrameSize() {
        // frmsizecod 8 (64 kbps): 128 words at 48 kHz but 192 at 32 kHz (A/52 table 5.18).
        val frames = List(20) { ac3Frame(2, 8, 192) }
        val sink = RecordingSink(); val r = Ac3Reader(sink, eac3 = false)
        feed(r, frames, 100, ptsOf(List(20) { 1536 to 32000 }))
        assertEquals(20, r.track.samples.size, "every 32 kHz frame read")
        assertEquals(32000, r.track.sampleRate, "rate")
    }

    private fun eac3Frame(dependent: Boolean, numblkscod: Int, bytes: Int): ByteArray {
        val f = body(bytes, setOf(0x0B))
        val frmsiz = bytes / 2 - 1
        f[0] = 0x0B; f[1] = 0x77
        f[2] = (((if (dependent) 1 else 0) shl 6) or (frmsiz shr 8)).toByte()
        f[3] = frmsiz.toByte()
        f[4] = ((0 shl 6) or (numblkscod shl 4) or (2 shl 1)).toByte() // 48 kHz, acmod 2, no LFE
        f[5] = (16 shl 3).toByte()                                     // bsid 16
        return f
    }

    @Test
    fun eac3DependentSubstreamsJoinTheirFrameAndShortFramesAreTimedRight() {
        val frames = ArrayList<ByteArray>()
        repeat(10) { frames += eac3Frame(false, 0, 256); frames += eac3Frame(true, 0, 128) }
        val sink = RecordingSink(); val r = Ac3Reader(sink, eac3 = true)
        // PTS per frame: independent frames 256 samples (480 ticks) apart; a dependent shares its frame's.
        feed(r, frames, 90, frames.indices.map { 480L * (it / 2) })
        val s = r.track.samples
        assertEquals(10, s.size, "one sample per access unit, dependents included")
        assertTrue(s.all { it.size == 384 }, "independent + dependent bytes in one sample")
        assertEquals((0 until 10).map { it * 256L }, s.map { it.pts }, "1-block frames are 256 samples, not 1536")
    }

    // ---- LATM ----

    private fun loas(payload: ByteArray, withConfig: Boolean): ByteArray {
        val w = BitWriter()
        w.bit(!withConfig)                                   // useSameStreamMux
        if (withConfig) {
            w.bits(0, 1).bit(true).bits(0, 6).bits(0, 4).bits(0, 3) // version 0, same framing, 1 subframe/program/layer
            w.bits(2, 5).bits(3, 4).bits(2, 4).bits(0, 3)    // AudioSpecificConfig: AAC LC, 48 kHz, stereo, GASpecificConfig
            w.bits(0, 3).bits(0xFF, 8).bit(false).bit(false) // frameLengthType 0, fullness, no other data, no CRC
        }
        var left = payload.size
        while (left >= 255) { w.bits(255, 8); left -= 255 }
        w.bits(left, 8)
        w.bytes(payload)
        val element = w.toByteArray()
        return byteArrayOf(0x56, (0xE0 or (element.size shr 8)).toByte(), element.size.toByte()) + element
    }

    @Test
    fun latmBecomesRawAacWithItsAudioSpecificConfig() {
        val payloads = List(12) { body(100 + it * 30) }       // some over 255 bytes: multi-byte length
        val frames = payloads.mapIndexed { i, p -> loas(p, withConfig = i % 5 == 0) }
        val sink = RecordingSink(); val r = LatmReader(sink)
        feed(r, frames, 170, ptsOf(List(12) { 1024 to 48000 }))
        val t = r.track
        assertEquals(Codec.AAC, t.codec, "LATM is written as plain AAC")
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), t.audioSpecificConfig, "AudioSpecificConfig from StreamMuxConfig")
        assertEquals(12, t.samples.size, "one sample per AudioMuxElement")
        val all = sink.bytes.toByteArray()
        for ((i, s) in t.samples.withIndex()) {
            assertArrayEquals(payloads[i], all.copyOfRange(s.fileOffset.toInt(), s.fileOffset.toInt() + s.size), "payload $i (not byte aligned in the stream)")
        }
    }

    // ---- DTS ----

    private fun dtsCore(size: Int): ByteArray {
        val w = BitWriter().bits(0x7FFE8001L, 32)
        w.bits(1, 1).bits(31, 5).bits(0, 1).bits(15, 7).bits(size - 1, 14) // FTYPE, SHORT, CPF, NBLKS (512 samples), FSIZE
        w.bits(2, 6).bits(13, 4).bits(15, 5)                                // AMODE stereo, 48 kHz, RATE
        w.bits(0, 1 + 1 + 1 + 1 + 1 + 3 + 1 + 1).bits(0, 2)                 // flags .. ASPF, LFF none
        val h = w.toByteArray()
        return h + body(size - h.size, setOf(0x7F, 0x64))
    }

    private fun dtsExt(size: Int): ByteArray {
        val w = BitWriter().bits(0x64582025L, 32).bits(0, 8).bits(0, 2).bits(0, 1).bits(15, 8).bits(size - 1, 16)
        val h = w.toByteArray()
        return h + body(size - h.size, setOf(0x7F, 0x64))
    }

    @Test
    fun dtsCoreWithExtensionSubstreamIsOneSample() {
        val frames = ArrayList<ByteArray>()
        repeat(8) { frames += dtsCore(1024); frames += dtsExt(200) }
        val sink = RecordingSink(); val r = DtsReader(sink)
        var t = 0L
        feed(r, frames, 300, frames.indices.map { i -> if (i % 2 == 0) t.also { t += 960 } else t - 960 })
        val s = r.track.samples
        assertEquals(8, s.size, "one sample per core frame")
        assertTrue(s.all { it.size == 1224 }, "core + extension substream")
        assertEquals((0 until 8).map { it * 512L }, s.map { it.pts }, "512 samples per frame")
        assertEquals(48000, r.track.sampleRate, "rate")
        assertEquals(2, r.track.channelCount, "channels")
    }

    // ---- MPEG-1/2 and MPEG-4 Part 2 video ----

    private fun sc(code: Int) = byteArrayOf(0, 0, 1, code.toByte())

    private fun mpeg2SequenceHeader(): ByteArray =
        sc(0xB3) + BitWriter().bits(720, 12).bits(576, 12).bits(2, 4).bits(3, 4).bits(0x3FFFF, 18).bits(1, 1)
            .bits(112, 10).bits(0, 3).toByteArray() +
            sc(0xB5) + BitWriter().bits(1, 4).bits(0x48, 8).bits(0, 1).bits(1, 2).bits(0, 2).bits(0, 2).bits(0, 12)
            .bits(1, 1).bits(0, 8).bits(0, 1).bits(0, 2).bits(0, 5).toByteArray()

    private fun mpeg2Picture(type: Int, temporalRef: Int): ByteArray =
        sc(0x00) + BitWriter().bits(temporalRef, 10).bits(type, 3).bits(0xFFFF, 16).bits(0, 3).toByteArray() +
            sc(0x01) + body(900, setOf(0x00, 0x01))

    @Test
    fun mpeg2VideoPicturesWithSequenceHeaderAsDecoderConfig() {
        val seq = mpeg2SequenceHeader()
        val gop = sc(0xB8) + byteArrayOf(0x00, 0x08, 0x00, 0x40)
        val pictures = ArrayList<ByteArray>()
        pictures += mpeg2Picture(2, 0)                                   // before any sequence header: dropped
        for (g in 0 until 2) {
            pictures += seq + gop + mpeg2Picture(1, 0)
            repeat(4) { pictures += mpeg2Picture(2, it + 1) }
        }
        for (chunk in intArrayOf(Int.MAX_VALUE, 7)) {
            val sink = RecordingSink(); val r = MpegVideoReader(sink, mpeg4 = false)
            val all = pictures.fold(ByteArray(0)) { a, b -> a + b }
            if (chunk == Int.MAX_VALUE) {
                var t = 0L
                for (p in pictures) { r.consume(p, 0, p.size, t, t); t += 3600 }
                r.finish()
            } else {
                feed(r, listOf(all), chunk, listOf(0L))
            }
            val t = r.track
            assertEquals(10, t.samples.size, "pictures after the first sequence header (chunk $chunk)")
            assertEquals(listOf(true, false, false, false, false, true, false, false, false, false), t.samples.map { it.isKeyframe }, "I pictures")
            assertEquals(720, t.width, "width"); assertEquals(576, t.height, "height")
            assertEquals(CodecBoxes.OTI_MPEG2_VIDEO, t.objectTypeIndication, "MPEG-2: has a sequence extension")
            assertArrayEquals(seq, t.decoderConfigRecord, "sequence header + extension, without the GOP header")
        }
    }

    @Test
    fun mpeg4PartTwoSizeFromVol() {
        val vol = sc(0x20) + BitWriter().bits(0, 1).bits(1, 8).bits(0, 1).bits(1, 4).bits(0, 1).bits(0, 2).bits(1, 1)
            .bits(25, 16).bits(1, 1).bits(0, 1).bits(1, 1).bits(352, 13).bits(1, 1).bits(288, 13).bits(1, 1)
            .bits(0, 2).bits(0, 6).toByteArray()
        val headers = sc(0xB0) + byteArrayOf(0x01) + sc(0x00) + vol
        fun vop(i: Boolean) = sc(0xB6) + byteArrayOf((if (i) 0x00 else 0x40).toByte()) + body(500, setOf(0x00, 0x01))
        val sink = RecordingSink(); val r = MpegVideoReader(sink, mpeg4 = true)
        var t = 0L
        for (p in listOf(headers + vop(true), vop(false), vop(false), headers + vop(true), vop(false))) {
            r.consume(p, 0, p.size, t, t); t += 3600
        }
        r.finish()
        assertEquals(5, r.track.samples.size, "VOPs")
        assertEquals(352, r.track.width, "width from VOL"); assertEquals(288, r.track.height, "height from VOL")
        assertArrayEquals(headers, r.track.decoderConfigRecord, "VOS/VO/VOL as DecoderSpecificInfo")
        assertEquals(listOf(true, false, false, true, false), r.track.samples.map { it.isKeyframe }, "I-VOPs")
    }

    // ---- transport stream level ----

    private fun aacFrames(n: Int) = List(n) { adts(3, 2, 150) }

    private fun h264Picture(key: Boolean) =
        byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) +
            (if (key) byteArrayOf(0, 0, 0, 1, 0x67) + body(10, setOf(0)) + byteArrayOf(0, 0, 0, 1, 0x68) + body(4, setOf(0)) else ByteArray(0)) +
            byteArrayOf(0, 0, 0, 1, (if (key) 0x65 else 0x41).toByte(), 0x88.toByte()) + body(300, setOf(0))

    private fun program(b: TsBuilder, streams: List<TsStream>, audio: List<Pair<Int, List<ByteArray>>>, programDescriptors: ByteArray = ByteArray(0)) {
        b.pat(0x1000)
        b.pmt(0x1000, streams, programDescriptors)
        for (i in 0 until 10) {
            b.pes(streams[0].pid, 0xE0, 3000L * i, h264Picture(i % 5 == 0))
            for ((pid, frames) in audio) b.pes(pid, 0xC0, 3000L * i, frames[i])
        }
    }

    @Test
    fun pmtSpanningSeveralPacketsKeepsEveryStream() {
        // 30 teletext-like streams with long descriptors push the PMT past one packet.
        val filler = (0 until 30).map { TsStream(0x06, 0x300 + it, byteArrayOf(0x56, 10) + body(10)) }
        val streams = listOf(TsStream(0x1B, 0x100)) + filler + TsStream(0x0F, 0x101)
        val b = TsBuilder()
        program(b, streams, listOf(0x101 to aacFrames(10)))
        val d = demuxTs(b.out.toByteArray())
        assertEquals(listOf(Codec.H264, Codec.AAC), d.tracks.map { it.codec }, "the AAC stream after the first packet's worth of PMT")
        assertEquals(10, d.tracks[1].samples.size, "its frames")
    }

    @Test
    fun bluRayTypesNeedTheProgramRegistration() {
        val hdmv = byteArrayOf(0x05, 4) + "HDMV".toByteArray()
        val eac3 = List(10) { eac3Frame(false, 3, 256) }
        val b = TsBuilder()
        // 0x84 is E-AC-3 only under HDMV; 0x83 (TrueHD) is left out without failing the rest.
        program(b, listOf(TsStream(0x1B, 0x100), TsStream(0x84, 0x101), TsStream(0x83, 0x102)),
            listOf(0x101 to eac3, 0x102 to List(10) { body(100) }), hdmv)
        val d = demuxTs(b.out.toByteArray())
        assertEquals(listOf(Codec.H264, Codec.EAC3), d.tracks.map { it.codec }, "E-AC-3 kept, TrueHD left out")
        assertEquals(10, d.tracks[1].samples.size, "E-AC-3 frames")
    }

    @Test
    fun unknownStreamTypeIsIdentifiedFromContent() {
        // ffmpeg writes DTS as 0x82 without an HDMV registration; it is recognised by its sync word.
        val dts = List(10) { dtsCore(1024) }
        val b = TsBuilder()
        program(b, listOf(TsStream(0x1B, 0x100), TsStream(0x82, 0x101)), listOf(0x101 to dts))
        val d = demuxTs(b.out.toByteArray())
        assertEquals(listOf(Codec.H264, Codec.DTS), d.tracks.map { it.codec }, "DTS from content")
        assertEquals(10, d.tracks[1].samples.size, "DTS frames")
    }

    @Test
    fun latmUnderTheAdtsStreamTypeIsDetected() {
        val latm = List(10) { loas(body(200), withConfig = it == 0) }
        val b = TsBuilder()
        program(b, listOf(TsStream(0x1B, 0x100), TsStream(0x0F, 0x101)), listOf(0x101 to latm))
        val d = demuxTs(b.out.toByteArray())
        assertEquals(Codec.AAC, d.tracks[1].codec, "AAC track")
        assertEquals(10, d.tracks[1].samples.size, "LATM frames read although 0x0F says ADTS")
    }

    @Test
    fun boundedPesIgnoresBytesAfterItsLength() {
        val b = TsBuilder()
        b.pat(0x1000)
        b.pmt(0x1000, listOf(0x1B to 0x100, 0x0F to 0x101))
        for (i in 0 until 10) {
            b.pes(0x100, 0xE0, 3000L * i, h264Picture(i % 5 == 0))
            // A whole extra ADTS frame after the PES's declared end must not become a sample.
            b.pes(0x101, 0xC0, 3000L * i, adts(3, 2, 150), bounded = true, trailing = adts(3, 2, 150))
        }
        val d = demuxTs(b.out.toByteArray())
        assertEquals(10, d.tracks[1].samples.size, "only the frames inside each PES")
    }

    // ---- MP4 writer ----

    private fun boxVersion(file: File, type: String): Int {
        val d = file.readBytes()
        val t = type.toByteArray()
        for (i in 4 until d.size - 4) if (d[i] == t[0] && d[i + 1] == t[1] && d[i + 2] == t[2] && d[i + 3] == t[3]) return d[i + 4].toInt()
        return -1
    }

    private fun aacTrack(writer: Mp4Writer, dts: List<Long>): Track = Track(Codec.AAC).also { t ->
        t.sampleRate = 48000; t.channelCount = 2; t.timescale = 48000; t.audioSpecificConfig = byteArrayOf(0x11, 0x90.toByte())
        for (x in dts) {
            val at = writer.writeSampleData(ByteArray(10), 0, 10)
            t.samples.add(at, 10, x, x, true)
        }
    }

    @Test
    fun longDurationsUse64BitBoxes() {
        val f = File(dir, "long.mp4")
        val w = Mp4Writer(f.absolutePath)
        // 25 hours at 48 kHz is past 2^32 ticks.
        val t = aacTrack(w, listOf(0L, 1024L, 25L * 3600 * 48000))
        assertTrue(w.finish(listOf(t)), "written")
        assertEquals(1, boxVersion(f, "mdhd"), "mdhd version 1")
        assertEquals(0, boxVersion(f, "mvhd"), "mvhd still fits (movie timescale 1000)")
    }

    private fun mpeg4Track(writer: Mp4Writer, ctsOffsets: List<Long>): Track = Track(Codec.MPEG4_VISUAL).also { t ->
        t.width = 320; t.height = 240; t.decoderConfigRecord = sc(0xB0) + byteArrayOf(1)
        for ((i, c) in ctsOffsets.withIndex()) {
            val at = writer.writeSampleData(ByteArray(10), 0, 10)
            t.samples.add(at, 10, 3000L * i + c, 3000L * i, i == 0)
        }
    }

    @Test
    fun cttsVersionZeroUnlessAnOffsetIsNegative() {
        val a = File(dir, "pos.mp4")
        Mp4Writer(a.absolutePath).let { w -> assertTrue(w.finish(listOf(mpeg4Track(w, listOf(3000, 9000, 0, 0)))), "written") }
        assertEquals(0, boxVersion(a, "ctts"), "non-negative offsets: version 0")
        val b = File(dir, "neg.mp4")
        Mp4Writer(b.absolutePath).let { w -> assertTrue(w.finish(listOf(mpeg4Track(w, listOf(0, 6000, -3000, -3000)))), "written") }
        assertEquals(1, boxVersion(b, "ctts"), "a negative offset: version 1")
    }

    @Test
    fun discontinuitySectionsKeepTheirAvOffset() {
        fun track(ts: Int, step: Long, starts: List<Long>, perSection: Int) = Track(Codec.AAC).also { t ->
            t.timescale = ts
            for (s in starts) for (k in 0 until perSection) t.samples.add(0, 1, s + k * step, s + k * step, true)
        }
        // Section 2 restarts its clock; there its audio starts 0.1 s after its video.
        val video = track(90_000, 3000, listOf(90_000L, 0L), 100)        // 30 fps
        val audio = track(48_000, 1024, listOf(48_000L, 4800L), 141)     // ~3 s each
        assertTrue(alignSections(listOf(video, audio)), "sections found on both tracks")
        val v2 = video.samples[100].pts / 90_000.0
        val a2 = audio.samples[141].pts / 48_000.0
        assertEquals(0.1, a2 - v2, 0.001, "section 2 keeps its 0.1 s A/V offset")
        val vEnd1 = video.samples[99].pts / 90_000.0 + 3000 / 90_000.0
        val aEnd1 = audio.samples[140].pts / 48_000.0 + 1024 / 48_000.0
        assertTrue(v2 >= vEnd1 - 1e-6 && a2 >= aEnd1 - 1e-6, "no track overlaps its previous section")
        assertTrue(minOf(v2 - vEnd1, a2 - aEnd1) < 0.001, "and the section follows right after")
    }
}
