// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.io.NalUnitUtil
import xdm.core.media.muxer.transmux.io.ParsableBitArray
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track

/**
 * H.264/AVC elementary stream reader: harvests SPS/PPS for the avcC config, marks IDR access units
 * as keyframes and lets [AnnexBReader] split the stream into length-prefixed MP4 samples.
 */
class H264Reader(sink: SampleSink) : AnnexBReader(sink) {
    override val track = Track(Codec.H264)

    override fun headerSize() = 1

    private fun type(buf: ByteArray, start: Int) = buf[start].toInt() and 0x1F

    override fun isVcl(buf: ByteArray, start: Int) = type(buf, start) in 1..5

    override fun isKeyframe(buf: ByteArray, start: Int) = type(buf, start) == 5

    /** first_mb_in_slice of the previous slice (or data partition A), -1 before the first. */
    private var lastFirstMb = -1

    /**
     * A slice starts a new picture when its first_mb_in_slice is 0 or not past the previous slice's,
     * as ffmpeg's parser decides (`h264_find_frame_end`): a picture whose first slice was lost still
     * gets its own sample. Only slices and data partition A carry it (B and C start with slice_id).
     */
    override fun isFirstSliceOfPicture(buf: ByteArray, start: Int, end: Int): Boolean {
        if (type(buf, start) !in intArrayOf(1, 2, 5)) return false
        val firstMb = firstMbInSlice(buf, start + 1, end)
        if (firstMb < 0) return false
        val previous = lastFirstMb
        lastFirstMb = firstMb
        return firstMb == 0 || (previous >= 0 && firstMb <= previous)
    }

    /** The ue(v) first_mb_in_slice at [from], read over emulation prevention bytes; -1 if cut off. */
    private fun firstMbInSlice(buf: ByteArray, from: Int, end: Int): Int {
        var zeros = 0
        var bits = 0
        var value = 0
        var leading = -1
        var p = from
        var run = 0 // zero bytes in a row, for 00 00 03
        while (p < end && p < from + 12) {
            val b = buf[p].toInt() and 0xFF
            if (run >= 2 && b == 3) { run = 0; p++; continue }
            run = if (b == 0) run + 1 else 0
            for (k in 7 downTo 0) {
                val bit = (b shr k) and 1
                if (leading < 0) {
                    if (bit == 0) { zeros++; if (zeros > 31) return -1 } else { leading = zeros; if (leading == 0) return 0 }
                } else {
                    value = (value shl 1) or bit
                    if (++bits == leading) return (1 shl leading) - 1 + value
                }
            }
            p++
        }
        return -1
    }

    /**
     * A recovery point SEI (payload type 6) makes its picture a random access point: how open-GOP
     * and intra-refresh streams mark where decoding can start, which ffmpeg's parser treats as a
     * keyframe too. Without it such streams would have a single sync sample.
     */
    override fun marksRandomAccess(buf: ByteArray, start: Int, end: Int): Boolean {
        if (type(buf, start) != 6) return false
        val rbsp = NalUnitUtil.unescapeRbsp(buf, start + 1, end - start - 1)
        var i = 0
        while (i < rbsp.size && (rbsp[i].toInt() and 0xFF) != 0x80) { // 0x80: rbsp_trailing_bits
            var payloadType = 0
            while (i < rbsp.size && (rbsp[i].toInt() and 0xFF) == 0xFF) { payloadType += 255; i++ }
            if (i >= rbsp.size) return false
            payloadType += rbsp[i++].toInt() and 0xFF
            var size = 0
            while (i < rbsp.size && (rbsp[i].toInt() and 0xFF) == 0xFF) { size += 255; i++ }
            if (i >= rbsp.size) return false
            size += rbsp[i++].toInt() and 0xFF
            if (payloadType == 6) return true
            i += size
        }
        return false
    }

    /** SEI, SPS, PPS, AUD and types 14..18 may only open an access unit (H.264 7.4.1.2.3). */
    override fun startsAccessUnit(buf: ByteArray, start: Int) = when (type(buf, start)) {
        6, 7, 8, 9, in 14..18 -> true
        else -> false
    }

    override fun keepNal(buf: ByteArray, start: Int, end: Int): Boolean = when (val t = type(buf, start)) {
        // SPS, PPS and SPS extension (auxiliary/alpha pictures): kept for the avcC as well.
        7, 8, 13 -> { val nal = buf.copyOfRange(start, end); offerParameterSet(t, parameterSetId(nal), nal); true }
        9, 12 -> false // AUD / filler
        else -> true
    }

    override fun parameterSetType(nal: ByteArray) = nal[0].toInt() and 0x1F

    override val spsType = 7

    override fun onParameterSet(type: Int, nal: ByteArray, into: Track) {
        if (type == 7) parseSps(nal, into) // PPS: no fields we need
    }

    /** seq_parameter_set_id (after profile, constraint flags and level) or pic_parameter_set_id (first). */
    private fun parameterSetId(nal: ByteArray): Int = try {
        val bit = ParsableBitArray(NalUnitUtil.unescapeRbsp(nal, 1, minOf(nal.size - 1, 16)))
        if (type(nal, 0) == 7) bit.skipBits(24)
        bit.readUe()
    } catch (_: Exception) { 0 }

    companion object {
        /** Reads an SPS NAL unit (header included) into [track]: size, bit depth, chroma format, VUI. */
        internal fun parseSps(nal: ByteArray, track: Track) {
            // nal[0] is the NAL header; RBSP starts at index 1.
            val rbsp = NalUnitUtil.unescapeRbsp(nal, 1, nal.size - 1)
            val bit = ParsableBitArray(rbsp)
            val profileIdc = bit.readBits(8)
            bit.skipBits(8)            // constraint flags + reserved
            bit.skipBits(8)            // level_idc
            bit.readUe()               // seq_parameter_set_id

            var chromaFormatIdc = 1
            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                chromaFormatIdc = bit.readUe()
                if (chromaFormatIdc == 3) bit.skipBits(1) // separate_colour_plane_flag
                track.bitDepthLuma = bit.readUe() + 8
                track.bitDepthChroma = bit.readUe() + 8
                bit.skipBits(1)        // qpprime_y_zero_transform_bypass_flag
                if (bit.readBit()) {   // seq_scaling_matrix_present_flag
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until count) {
                        if (bit.readBit()) skipScalingList(bit, if (i < 6) 16 else 64)
                    }
                }
            }
            bit.readUe()               // log2_max_frame_num_minus4
            val picOrderCntType = bit.readUe()
            if (picOrderCntType == 0) {
                bit.readUe()           // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                bit.skipBits(1)        // delta_pic_order_always_zero_flag
                bit.readSe()           // offset_for_non_ref_pic
                bit.readSe()           // offset_for_top_to_bottom_field
                val n = bit.readUe()
                repeat(n) { bit.readSe() }
            }
            bit.readUe()               // max_num_ref_frames
            bit.skipBits(1)            // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbs = bit.readUe() + 1
            val picHeightInMapUnits = bit.readUe() + 1
            val frameMbsOnly = bit.readBit()
            if (!frameMbsOnly) bit.skipBits(1) // mb_adaptive_frame_field_flag
            bit.skipBits(1)            // direct_8x8_inference_flag

            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            if (bit.readBit()) {       // frame_cropping_flag
                cropLeft = bit.readUe(); cropRight = bit.readUe()
                cropTop = bit.readUe(); cropBottom = bit.readUe()
            }

            val width = picWidthInMbs * 16
            val height = picHeightInMapUnits * 16 * (if (frameMbsOnly) 1 else 2)
            // Chroma subsampling for crop units.
            val subWidthC = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
            val subHeightC = if (chromaFormatIdc == 1) 2 else 1
            val cropUnitX = if (chromaFormatIdc == 0) 1 else subWidthC
            val cropUnitY = (if (chromaFormatIdc == 0) 1 else subHeightC) * (if (frameMbsOnly) 1 else 2)
            track.width = width - cropUnitX * (cropLeft + cropRight)
            track.height = height - cropUnitY * (cropTop + cropBottom)
            track.chromaFormat = chromaFormatIdc
            if (bit.readBit()) Vui.read(bit, track) // vui_parameters_present_flag
        }

        /** Skips an H.264/H.265 scaling list of [size] coefficients. */
        fun skipScalingList(bit: ParsableBitArray, size: Int) {
            var lastScale = 8
            var nextScale = 8
            for (j in 0 until size) {
                if (nextScale != 0) {
                    val deltaScale = bit.readSe()
                    nextScale = (lastScale + deltaScale + 256) % 256
                }
                lastScale = if (nextScale == 0) lastScale else nextScale
            }
        }
    }
}
