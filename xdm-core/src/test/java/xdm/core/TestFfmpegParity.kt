// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.core.media.muxer.transmux.es.Ac3Reader
import xdm.core.media.muxer.transmux.es.AdtsReader
import xdm.core.media.muxer.transmux.es.H264Reader
import xdm.core.media.muxer.transmux.es.H265Reader
import xdm.core.media.muxer.transmux.es.MpegVideoReader
import xdm.core.media.muxer.transmux.mp4.Mp4Writer
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Behaviour matched to ffmpeg's parsers and MP4 muxer: keyframes from recovery-point SEIs,
 * timestamps interpolated between real PTS, enhancement layers and data partitions not splitting
 * frames, VUI aspect ratio and colour (pasp/colr/tkhd), avcC/hvcC fields, AAC PCE and multi-block
 * frames, AC-3 cores in E-AC-3 streams and half-rate AC-3, MPEG-2 aspect ratio.
 */
class TestFfmpegParity {

    @TempDir
    lateinit var dir: File

    private val rnd = Random(3)
    private fun body(n: Int) = ByteArray(n) { rnd.nextInt(2, 256).toByte() }
    private fun sc() = byteArrayOf(0, 0, 0, 1)

    /** A Main-profile 320x240 SPS with a VUI giving SAR 64:45 and BT.709 colours, as a NAL unit. */
    private fun sps(): ByteArray {
        val w = BitWriter().bits(77, 8).bits(0, 8).bits(30, 8)
        fun ue(v: Int) { val x = v + 1; val n = 32 - Integer.numberOfLeadingZeros(x); w.bits(0, n - 1); w.bits(x, n) }
        ue(0); ue(0); ue(0); ue(0); ue(1); w.bits(0, 1)           // sps_id, log2_max_frame_num, poc type 0, poc lsb, max_ref_frames, gaps
        ue(19); ue(14); w.bits(1, 1); w.bits(1, 1); w.bits(0, 1)  // 320x240, frame_mbs_only, direct_8x8, no cropping
        w.bits(1, 1)                                              // vui_parameters_present_flag
        w.bits(1, 1).bits(255, 8).bits(64, 16).bits(45, 16)       // aspect ratio: Extended_SAR 64:45
        w.bits(0, 1)                                              // no overscan info
        w.bits(1, 1).bits(5, 3).bits(0, 1).bits(1, 1).bits(1, 8).bits(1, 8).bits(1, 8) // signal type, BT.709 colours
        w.bits(0, 1 + 1 + 1 + 1 + 1).bits(1, 1)                   // remaining VUI flags off, stop bit
        return byteArrayOf(0x67) + w.toByteArray()
    }

    private fun slice(idr: Boolean, firstMb: Boolean = true) =
        byteArrayOf((if (idr) 0x65 else 0x41).toByte(), (if (firstMb) 0x88 else 0x40).toByte()) + body(200)

    private fun recoverySei() = byteArrayOf(0x06, 0x06, 0x01, 0x84.toByte(), 0x80.toByte()) // payload 6 (recovery point), size 1

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        for (n in nals) { o.write(sc()); o.write(n) }
        return o.toByteArray()
    }

    @Test
    fun recoveryPointSeiMarksKeyframes() {
        val r = H264Reader(RecordingSink())
        val spsNal = sps(); val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        for (i in 0 until 12) {
            val au = when {
                i == 0 -> annexB(spsNal, pps, slice(idr = true))
                i % 4 == 0 -> annexB(recoverySei(), slice(idr = false))      // open-GOP style I picture, no IDR
                else -> annexB(slice(idr = false))
            }
            r.consume(au, 0, au.size, 3000L * i, 3000L * i)
        }
        r.finish()
        assertEquals(listOf(0, 4, 8), r.track.samples.indices.filter { r.track.samples[it].isKeyframe }, "IDR and recovery points")
    }

    @Test
    fun spsVuiGivesAspectRatioAndColour() {
        val r = H264Reader(RecordingSink())
        val au = annexB(sps(), byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte()), slice(true))
        r.consume(au, 0, au.size, 0, 0); r.finish()
        val t = r.track
        assertEquals(320, t.width, "width"); assertEquals(240, t.height, "height")
        assertEquals(64 to 45, t.sarNum to t.sarDen, "SAR from VUI")
        assertEquals(listOf(1, 1, 1), listOf(t.colourPrimaries, t.transferCharacteristics, t.matrixCoefficients), "BT.709")
    }

    @Test
    fun picturesWithoutPtsAreSpreadEvenlyBetweenRealOnes() {
        // Interlaced: each PES holds a frame as two field pictures, only the first with a PTS.
        val r = H264Reader(RecordingSink())
        for (i in 0 until 6) {
            val pes = annexB(slice(i == 0), slice(false))           // two first-slices: two pictures (fields)
            r.consume(pes, 0, pes.size, 3600L * i, 3600L * i)
        }
        r.finish()
        val dts = r.track.samples.map { it.dts }
        assertEquals((0 until 11).map { it * 1800L }, dts.take(11), "second fields half way between frames")
    }

    @Test
    fun hevcEnhancementLayerDoesNotSplitPictures() {
        val r = H265Reader(RecordingSink())
        fun nal(type: Int, layer: Int, first: Boolean) =
            byteArrayOf(((type shl 1) or (layer shr 5)).toByte(), (((layer and 31) shl 3) or 1).toByte(), (if (first) 0x80 else 0).toByte()) + body(100)
        for (i in 0 until 5) {
            val au = annexB(nal(if (i == 0) 19 else 1, 0, true), nal(if (i == 0) 19 else 1, 1, true)) // base + layer 1
            r.consume(au, 0, au.size, 3000L * i, 3000L * i)
        }
        r.finish()
        assertEquals(5, r.track.samples.size, "one sample per base picture, enhancement layer inside")
    }

    @Test
    fun h264DataPartitionsBAndCStayInTheirPicture() {
        val r = H264Reader(RecordingSink())
        for (i in 0 until 4) {
            // DPA starts the picture; DPB/DPC begin with slice_id (here 0, which reads like first_mb 0).
            val au = annexB(byteArrayOf(0x22, 0x88.toByte()) + body(50), byteArrayOf(0x23, 0x80.toByte()) + body(50), byteArrayOf(0x24, 0x80.toByte()) + body(50))
            r.consume(au, 0, au.size, 3000L * i, 3000L * i)
        }
        r.finish()
        assertEquals(4, r.track.samples.size, "partitions B and C don't start pictures")
    }

    private fun boxPayload(file: File, type: String): ByteArray? {
        val d = file.readBytes(); val t = type.toByteArray()
        for (i in 4 until d.size - 4) if (d[i] == t[0] && d[i + 1] == t[1] && d[i + 2] == t[2] && d[i + 3] == t[3]) {
            val size = ((d[i - 4].toInt() and 0xFF) shl 24) or ((d[i - 3].toInt() and 0xFF) shl 16) or ((d[i - 2].toInt() and 0xFF) shl 8) or (d[i - 1].toInt() and 0xFF)
            return d.copyOfRange(i + 4, i - 4 + size)
        }
        return null
    }

    private fun writeVideo(t: Track, name: String): File {
        val f = File(dir, name)
        val w = Mp4Writer(f.absolutePath)
        for (k in 0 until 3) {
            val at = w.writeSampleData(ByteArray(16), 0, 16)
            t.samples.add(at, 16, 3000L * k, 3000L * k, k == 0)
        }
        assertTrue(w.finish(listOf(t)), "written")
        return f
    }

    @Test
    fun writerOutputsPaspColrDisplayWidthAndAvcCHighExtension() {
        val r = H264Reader(RecordingSink())
        // High profile SPS: same as sps() but profile 100 with chroma/bit depth fields.
        val w = BitWriter().bits(100, 8).bits(0, 8).bits(30, 8)
        fun ue(v: Int) { val x = v + 1; val n = 32 - Integer.numberOfLeadingZeros(x); w.bits(0, n - 1); w.bits(x, n) }
        ue(0); ue(1); ue(0); ue(0); w.bits(0, 1).bits(0, 1)        // sps_id, chroma 4:2:0, 8-bit, 8-bit, no bypass, no scaling
        ue(0); ue(0); ue(0); ue(1); w.bits(0, 1)
        ue(19); ue(14); w.bits(1, 1); w.bits(1, 1); w.bits(0, 1); w.bits(1, 1)
        w.bits(1, 1).bits(255, 8).bits(4, 16).bits(3, 16).bits(0, 1).bits(1, 1).bits(5, 3).bits(0, 1).bits(1, 1).bits(9, 8).bits(16, 8).bits(9, 8)
        w.bits(0, 5).bits(1, 1)
        val au = annexB(byteArrayOf(0x67) + w.toByteArray(), byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte()), slice(true))
        r.consume(au, 0, au.size, 0, 0); r.finish()
        val t = Track(Codec.H264).also {
            it.width = r.track.width; it.height = r.track.height; it.parameterSets.addAll(r.track.parameterSets)
            it.sarNum = r.track.sarNum; it.sarDen = r.track.sarDen; it.chromaFormat = r.track.chromaFormat
            it.colourPrimaries = r.track.colourPrimaries; it.transferCharacteristics = r.track.transferCharacteristics
            it.matrixCoefficients = r.track.matrixCoefficients
        }
        val f = writeVideo(t, "high.mp4")
        assertArrayEquals(byteArrayOf(0, 0, 0, 4, 0, 0, 0, 3), boxPayload(f, "pasp"), "pasp 4:3")
        assertArrayEquals("nclx".toByteArray() + byteArrayOf(0, 9, 0, 16, 0, 9, 0), boxPayload(f, "colr"), "colr BT.2020 / PQ")
        val avcC = boxPayload(f, "avcC")!!
        assertArrayEquals(byteArrayOf(0xFD.toByte(), 0xF8.toByte(), 0xF8.toByte(), 0), avcC.copyOfRange(avcC.size - 4, avcC.size), "High-profile avcC extension: 4:2:0, 8-bit, no SPS ext")
        val tkhd = boxPayload(f, "tkhd")!!
        assertEquals(320 * 4 / 3, ((tkhd[tkhd.size - 8].toInt() and 0xFF) shl 8) or (tkhd[tkhd.size - 7].toInt() and 0xFF), "tkhd display width")
    }

    @Test
    fun hvcCFieldsComeFromTheSps() {
        val t = Track(Codec.H265).also {
            it.width = 64; it.height = 64; it.chromaFormat = 1; it.bitDepthLuma = 10; it.bitDepthChroma = 10
            it.temporalLayers = 2; it.temporalIdNested = true
            it.parameterSets += byteArrayOf(0x40, 1) + ByteArray(20) { 1 }   // VPS
            it.parameterSets += byteArrayOf(0x42, 1) + ByteArray(20) { 1 }   // SPS
            it.parameterSets += byteArrayOf(0x44, 1, 1)                     // PPS
        }
        val hvcC = boxPayload(writeVideo(t, "hevc.mp4"), "hvcC")!!
        assertEquals(0xFD, hvcC[16].toInt() and 0xFF, "chroma 4:2:0")
        assertEquals(0xFA, hvcC[17].toInt() and 0xFF, "10-bit luma"); assertEquals(0xFA, hvcC[18].toInt() and 0xFF, "10-bit chroma")
        assertEquals((2 shl 3) or (1 shl 2) or 3, hvcC[21].toInt() and 0xFF, "temporal layers, nesting, 4-byte lengths")
        assertEquals(32, hvcC[23].toInt() and 0xFF, "array_completeness 0 for hev1 (VPS array)")
    }

    @Test
    fun adtsPceGoesIntoTheAudioSpecificConfig() {
        // 7 channels via a PCE: front SCE + CPE, side CPE, back CPE.
        val pce = BitWriter().bits(5, 3)                                   // ID_PCE
            .bits(0, 4).bits(1, 2).bits(3, 4)                              // tag, object type LC, 48 kHz
            .bits(2, 4).bits(1, 4).bits(1, 4).bits(0, 2).bits(0, 3).bits(0, 4) // 2 front, 1 side, 1 back, no lfe/assoc/cc
            .bits(0, 1).bits(0, 1).bits(0, 1)                              // no mixdowns
            .bits(0, 1).bits(0, 4).bits(1, 1).bits(0, 4)                   // front: SCE, CPE
            .bits(1, 1).bits(1, 4).bits(1, 1).bits(2, 4)                   // side CPE, back CPE
        while (pce.bitCount % 8 != 0) pce.bits(0, 1)
        pce.bits(0, 8)                                                     // comment_field_bytes
        val pceBytes = pce.toByteArray()
        fun frame(withPce: Boolean, raw: Int): ByteArray {
            val payload = (if (withPce) pceBytes else ByteArray(0)) + body(raw)
            val len = payload.size + 7
            return byteArrayOf(0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (3 shl 2)).toByte(), ((len shr 11) and 3).toByte(),
                ((len shr 3) and 0xFF).toByte(), (((len and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte()) + payload
        }
        val r = AdtsReader(RecordingSink())
        val frames = listOf(frame(true, 300)) + List(4) { frame(false, 300) }
        for ((i, f) in frames.withIndex()) r.consume(f, 0, f.size, 1920L * i, -1)
        val asc = r.track.audioSpecificConfig!!
        assertEquals(0, (asc[1].toInt() shr 3) and 0xF, "channel_config 0 in the AudioSpecificConfig")
        assertTrue(asc.size > 2, "PCE appended to the AudioSpecificConfig")
        assertEquals(7, r.track.channelCount, "channels counted from the PCE")
        assertEquals(300, r.track.samples[0].size, "PCE taken off the first frame")
    }

    @Test
    fun adtsFrameWithSeveralRawBlocksLastsThatMuchLonger() {
        val r = AdtsReader(RecordingSink())
        fun frame(blocks: Int) = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (3 shl 2)).toByte(), (2 shl 6).toByte(),
            ((207 shr 3) and 0xFF).toByte(), (((207 and 7) shl 5) or 0x1F).toByte(), (0xFC or (blocks - 1)).toByte()) + body(200)
        for (i in 0 until 4) { val f = frame(2); r.consume(f, 0, f.size, if (i == 0) 0 else -1, -1) }
        assertEquals(listOf(0L, 2048L, 4096L, 6144L), r.track.samples.map { it.pts }, "2 blocks = 2048 samples per frame")
    }

    @Test
    fun ac3CoreWithEac3DependentIsOneSampleOnAnEac3Track() {
        fun ac3(): ByteArray { val f = body(384); f[0] = 0x0B; f[1] = 0x77; f[4] = (0 shl 6 or 12).toByte(); f[5] = (8 shl 3).toByte(); f[6] = (2 shl 5).toByte(); return f } // 48 kHz, 384 bytes
        fun eac3Dep(): ByteArray { val f = body(256); f[0] = 0x0B; f[1] = 0x77; f[2] = ((1 shl 6) or 0).toByte(); f[3] = 127; f[4] = ((3 shl 4) or (2 shl 1)).toByte(); f[5] = (16 shl 3).toByte(); return f }
        val r = Ac3Reader(RecordingSink(), eac3 = true)
        val all = (0 until 6).fold(ByteArray(0)) { a, _ -> a + ac3() + eac3Dep() }
        r.consume(all, 0, all.size, 0, -1); r.finish()
        assertEquals(6, r.track.samples.size, "AC-3 core opens each access unit")
        assertTrue(r.track.samples.all { it.size == 640 }, "core + dependent substream in one sample")
        assertEquals(5, r.track.decoderConfigRecord!!.size, "a dec3 for the ec-3 track")
    }

    @Test
    fun halfRateAc3() {
        fun f(): ByteArray { val x = body(256); x[0] = 0x0B; x[1] = 0x77; x[4] = 8; x[5] = (9 shl 3).toByte(); x[6] = (2 shl 5).toByte(); return x } // bsid 9
        val r = Ac3Reader(RecordingSink(), eac3 = false)
        val all = f() + f() + f()
        r.consume(all, 0, all.size, 0, -1); r.finish()
        assertEquals(24000, r.track.sampleRate, "bsid 9 halves the rate")
    }

    @Test
    fun mpeg2AspectRatioBecomesSampleAspect() {
        val seq = byteArrayOf(0, 0, 1, 0xB3.toByte()) + BitWriter().bits(720, 12).bits(576, 12).bits(3, 4).bits(3, 4)
            .bits(0x3FFFF, 18).bits(1, 1).bits(112, 10).bits(0, 3).toByteArray() +
            byteArrayOf(0, 0, 1, 0xB5.toByte()) + BitWriter().bits(1, 4).bits(0x48, 8).bits(0, 1).bits(1, 2).bits(0, 4)
            .bits(0, 12).bits(1, 1).bits(0, 8).bits(0, 1).bits(0, 7).toByteArray()
        val pic = byteArrayOf(0, 0, 1, 0) + BitWriter().bits(0, 10).bits(1, 3).bits(0xFFFF, 16).bits(0, 3).toByteArray() + byteArrayOf(0, 0, 1, 1) + body(300)
        val r = MpegVideoReader(RecordingSink(), mpeg4 = false)
        val pes = seq + pic
        r.consume(pes, 0, pes.size, 0, 0); r.consume(pic, 0, pic.size, 3600, 3600); r.finish()
        assertEquals(64 to 45, r.track.sarNum to r.track.sarDen, "16:9 at 720x576 = SAR 64:45")
    }
}
