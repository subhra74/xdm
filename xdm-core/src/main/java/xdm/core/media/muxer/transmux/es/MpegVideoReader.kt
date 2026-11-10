// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.io.ParsableBitArray
import xdm.core.media.muxer.transmux.mp4.CodecBoxes
import xdm.core.media.muxer.transmux.mp4.SampleEntries
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.util.Logger

/**
 * MPEG-1/2 video (ISO/IEC 11172-2 / 13818-2, TS stream types 0x01/0x02) and MPEG-4 Part 2 visual
 * (ISO/IEC 14496-2, stream type 0x10) elementary stream reader. These are start-code streams:
 * `00 00 01 xx` with xx naming the unit. An MP4 `mp4v` sample is a whole picture with its start
 * codes; the stream headers before the first picture (MPEG-1/2 sequence header + extensions,
 * MPEG-4 VOS/VO/VOL) are the DecoderSpecificInfo, as ffmpeg's parsers split them.
 *
 * As for H.264, the byte stream is continuous across PES packets: pictures are delimited by start
 * codes, a PES PTS belongs to the first picture starting in that PES, and a picture starting in a
 * PES without one gets the previous frame interval added.
 */
class MpegVideoReader(private val sink: SampleSink, private val mpeg4: Boolean) : ElementaryStreamReader {
    override val track = Track(if (mpeg4) Codec.MPEG4_VISUAL else Codec.MPEG_VIDEO)

    /** Bytes from the current unit's start code on (start code included). */
    private val pending = ByteArrayBuilder(256 * 1024)
    private var synced = false
    private var unitPts = NO_TS
    private var unitDts = NO_TS
    private var pesPts = NO_TS
    private var pesDts = NO_TS

    // ---- picture being assembled ----
    private val picture = ByteArrayBuilder(256 * 1024)
    private var hasPicture = false
    private var keyframe = false
    private var pts = NO_TS
    private var dts = NO_TS
    /** Bytes of [picture] before its picture/VOP start code: stream headers, if it begins with them. */
    private var headerBytes = -1

    private var lastPts = NO_TS
    private var lastDts = NO_TS
    private var lastInterval = 0L
    private var descriptionIndex = 1
    private var config: ByteArray? = null

    override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) {
        if (pts >= 0) {
            pesPts = pts
            pesDts = if (dts >= 0) dts else pts
        }
        var scanFrom = maxOf(0, pending.size - 3)
        pending.write(data, offset, length)
        val buf = pending.buffer
        val end = pending.size
        var unitStart = if (synced) 0 else -1
        while (true) {
            val sc = findStartCode(buf, scanFrom, end)
            if (sc < 0 || sc + 3 >= end) break // the code byte must be here too
            if (unitStart >= 0 && sc > unitStart) onUnit(buf, unitStart, sc)
            synced = true
            unitStart = sc
            scanFrom = sc + 3
            unitPts = pesPts; unitDts = pesDts
            pesPts = NO_TS; pesDts = NO_TS
        }
        if (unitStart < 0) pending.compact(maxOf(0, end - 3)) else pending.compact(unitStart)
        pesPts = NO_TS; pesDts = NO_TS
    }

    override fun finish() {
        if (synced && pending.size > 4) onUnit(pending.buffer, 0, pending.size)
        pending.reset()
        emitPicture()
    }

    /** One unit, `00 00 01 code ...` in [b] from [s] to [e]. */
    private fun onUnit(b: ByteArray, s: Int, e: Int) {
        val code = b[s + 3].toInt() and 0xFF
        val isPicture = if (mpeg4) code == 0xB6 else code == 0x00
        // Stream headers (sequence/GOP, VOS/VO/VOL/GOV) after a picture open the next picture's unit.
        val isHeader = if (mpeg4) code == 0xB0 || code == 0xB3 || code == 0xB5 || code <= 0x2F else code == 0xB3 || code == 0xB8
        if ((isPicture || isHeader) && hasPicture) emitPicture()
        if (unitPts != NO_TS && pts == NO_TS) { pts = unitPts; dts = unitDts }
        unitPts = NO_TS; unitDts = NO_TS
        if (picture.size == 0) headerBytes = if (isPicture) -1 else 0
        if (isPicture) {
            if (headerBytes == 0) headerBytes = picture.size
            hasPicture = true
            if (e - s > 5) {
                keyframe = if (mpeg4) ((b[s + 4].toInt() shr 6) and 3) == 0     // vop_coding_type I
                else ((b[s + 5].toInt() shr 3) and 7) == 1                       // picture_coding_type I
            }
        }
        picture.write(b, s, e - s)
    }

    private fun emitPicture() {
        if (!hasPicture) { picture.reset(); return }
        if (headerBytes > 0) updateConfig(picture.buffer.copyOf(headerBytes))
        if (config != null) {
            var p = pts
            var d = dts
            if (p == NO_TS) {
                val step = if (lastInterval > 0) lastInterval else 3600L
                d = if (lastDts != NO_TS) lastDts + step else 0
                p = if (lastPts != NO_TS) d + (lastPts - lastDts) else d
            }
            if (lastDts != NO_TS && d > lastDts) lastInterval = d - lastDts
            lastPts = p; lastDts = d
            val at = sink.writeSampleData(picture.buffer, 0, picture.size)
            track.samples.add(at, picture.size, p, d, keyframe, descriptionIndex)
        }
        // Pictures before the first stream header can't be decoded: dropped.
        picture.reset()
        hasPicture = false
        keyframe = false
        pts = NO_TS; dts = NO_TS
        headerBytes = -1
    }

    /** Stream headers seen before a picture: the first set configures the track; a changed set adds a sample entry. */
    private fun updateConfig(headers: ByteArray) {
        val dsi = if (mpeg4) mpeg4Headers(headers) else mpeg12Headers(headers)
        if (config?.contentEquals(dsi) == true) return
        val probe = Track(track.codec)
        val ok = try { if (mpeg4) parseVol(dsi, probe) else parseSequence(dsi, probe) } catch (_: Exception) { false }
        if (!ok) return
        probe.decoderConfigRecord = dsi
        if (config == null) {
            track.width = probe.width; track.height = probe.height
            track.objectTypeIndication = probe.objectTypeIndication
            track.sarNum = probe.sarNum; track.sarDen = probe.sarDen
            track.decoderConfigRecord = dsi
        } else {
            if (track.sampleEntryBox == null) track.sampleEntryBox = SampleEntries.build(track)
            descriptionIndex = track.descriptionIndexFor(SampleEntries.build(probe)!!)
            Logger.info("XDM", "${track.codec} stream headers changed to ${probe.width}x${probe.height} (sample entry $descriptionIndex)")
        }
        config = dsi
    }

    /** VOS/VO/VOL (and user data) up to a GOV header, as ffmpeg's MPEG-4 parser splits them. */
    private fun mpeg4Headers(h: ByteArray): ByteArray {
        var i = 0
        while (true) {
            val sc = findStartCode(h, i, h.size)
            if (sc < 0 || sc + 3 >= h.size) return h
            if ((h[sc + 3].toInt() and 0xFF) == 0xB3) return h.copyOf(sc)
            i = sc + 3
        }
    }

    /** The sequence header and its extensions, without a GOP header or user data that follow them. */
    private fun mpeg12Headers(h: ByteArray): ByteArray {
        var i = 0
        var end = h.size
        while (true) {
            val sc = findStartCode(h, i + 1, h.size)
            if (sc < 0 || sc + 3 >= h.size) break
            val code = h[sc + 3].toInt() and 0xFF
            if (code != 0xB5) { end = sc; break }
            i = sc
        }
        return h.copyOf(end)
    }

    /** MPEG-1/2 sequence header (+ sequence extension for MPEG-2): picture size and object type. */
    private fun parseSequence(h: ByteArray, t: Track): Boolean {
        if (h.size < 12 || (h[3].toInt() and 0xFF) != 0xB3) return false
        val bits = ParsableBitArray(h.copyOfRange(4, h.size))
        var width = bits.readBits(12)
        var height = bits.readBits(12)
        val aspectCode = bits.readBits(4)
        var mpeg2 = false
        // A sequence extension (extension_start_code_identifier 1) makes it MPEG-2.
        var i = 4
        while (true) {
            val sc = findStartCode(h, i, h.size)
            if (sc < 0 || sc + 6 > h.size) break
            if ((h[sc + 3].toInt() and 0xFF) == 0xB5 && ((h[sc + 4].toInt() shr 4) and 0xF) == 1) {
                mpeg2 = true
                val ext = ParsableBitArray(h.copyOfRange(sc + 4, h.size))
                ext.skipBits(4 + 8 + 1 + 2) // id, profile_and_level, progressive, chroma_format
                width = width or (ext.readBits(2) shl 12)
                height = height or (ext.readBits(2) shl 12)
                break
            }
            i = sc + 3
        }
        if (width == 0 || height == 0) return false
        t.width = width; t.height = height
        t.objectTypeIndication = if (mpeg2) CodecBoxes.OTI_MPEG2_VIDEO else CodecBoxes.OTI_MPEG1_VIDEO
        // MPEG-2 codes the display aspect ratio (13818-2 table 6-3); the sample aspect follows from the size.
        if (mpeg2) {
            val dar = when (aspectCode) { 1 -> 0 to 0; 2 -> 4 to 3; 3 -> 16 to 9; 4 -> 221 to 100; else -> null }
            if (dar != null) setSar(t, if (dar.first == 0) 1 else dar.first * height, if (dar.first == 0) 1 else dar.second * width)
        } else if (aspectCode in 1..14) {
            // MPEG-1 codes the pel aspect ratio (height/width, 11172-2 2.4.3.2); as ffmpeg: 1/ratio, terms <= 255.
            val (n, d) = rational(1.0 / MPEG1_PEL_ASPECT[aspectCode - 1], 255)
            setSar(t, n, d)
        }
        return true
    }

    /** MPEG-4 Part 2: picture size from the video_object_layer header (ISO/IEC 14496-2 6.2.3). */
    private fun parseVol(h: ByteArray, t: Track): Boolean {
        var i = 0
        while (true) {
            val sc = findStartCode(h, i, h.size)
            if (sc < 0 || sc + 4 >= h.size) return false
            val code = h[sc + 3].toInt() and 0xFF
            if (code in 0x20..0x2F) {
                val b = ParsableBitArray(h.copyOfRange(sc + 4, h.size))
                b.skipBits(1)                               // random_accessible_vol
                b.skipBits(8)                               // video_object_type_indication
                if (b.readBit()) b.skipBits(4 + 3)          // is_object_layer_identifier: verid, priority
                when (val aspect = b.readBits(4)) {         // aspect_ratio_info (14496-2 table 6-12)
                    0xF -> setSar(t, b.readBits(8), b.readBits(8))
                    in 1..5 -> PAR[aspect - 1].let { setSar(t, it.first, it.second) }
                }
                if (b.readBit()) {                          // vol_control_parameters
                    b.skipBits(2 + 1)                       // chroma_format, low_delay
                    if (b.readBit()) b.skipBits(15 + 1 + 15 + 1 + 15 + 1 + 3 + 11 + 1 + 15 + 1) // vbv_parameters
                }
                if (b.readBits(2) != 0) return false        // video_object_layer_shape: rectangular only
                b.skipBits(1)                               // marker
                val resolution = b.readBits(16)             // vop_time_increment_resolution
                b.skipBits(1)                               // marker
                if (b.readBit()) {                          // fixed_vop_rate
                    var n = 0
                    var r = resolution - 1
                    while (r > 0) { n++; r = r shr 1 }
                    b.skipBits(maxOf(1, n))                 // fixed_vop_time_increment
                }
                b.skipBits(1)
                val width = b.readBits(13)
                b.skipBits(1)
                val height = b.readBits(13)
                if (width == 0 || height == 0) return false
                t.width = width; t.height = height
                t.objectTypeIndication = CodecBoxes.OTI_MPEG4_VISUAL
                return true
            }
            i = sc + 3
        }
    }

    /** Closest fraction to [x] with numerator and denominator at most [max] (continued fractions). */
    private fun rational(x: Double, max: Int): Pair<Int, Int> {
        var bestN = Math.round(x).toInt().coerceIn(1, max); var bestD = 1
        var h0 = 0L; var h1 = 1L; var k0 = 1L; var k1 = 0L
        var v = x
        repeat(20) {
            val a = Math.floor(v).toLong()
            val h2 = a * h1 + h0; val k2 = a * k1 + k0
            if (h2 > max || k2 > max) return bestN to bestD
            bestN = h2.toInt(); bestD = k2.toInt()
            h0 = h1; h1 = h2; k0 = k1; k1 = k2
            val frac = v - a
            if (frac < 1e-9) return bestN to bestD
            v = 1 / frac
        }
        return bestN to bestD
    }

    private fun setSar(t: Track, num: Int, den: Int) {
        if (num <= 0 || den <= 0) return
        var a = num; var b = den
        while (b != 0) { val r = a % b; a = b; b = r }
        t.sarNum = num / a; t.sarDen = den / a
    }

    private fun findStartCode(data: ByteArray, start: Int, end: Int): Int {
        var i = start
        while (i + 2 < end) {
            val b2 = data[i + 2].toInt() and 0xFF
            if (b2 > 1) i += 3
            else if (b2 == 1 && data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
            else i++
        }
        return -1
    }

    private companion object {
        const val NO_TS = Long.MIN_VALUE
        /** MPEG-4 Part 2 pixel aspect ratios for aspect_ratio_info 1..5. */
        val PAR = arrayOf(1 to 1, 12 to 11, 10 to 11, 16 to 11, 40 to 33)
        /** MPEG-1 pel aspect ratios (height/width) for aspect_ratio_information 1..14. */
        val MPEG1_PEL_ASPECT = doubleArrayOf(
            1.0, 0.6735, 0.7031, 0.7615, 0.8055, 0.8437, 0.8935, 0.9157, 0.9815, 1.0255, 1.0695, 1.0950, 1.1575, 1.2015
        )
    }
}
