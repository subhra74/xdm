// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.io.NalUnitUtil
import xdm.core.media.muxer.transmux.io.ParsableBitArray
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track

/**
 * H.265/HEVC elementary stream reader. Same shape as [H264Reader] but HEVC NAL headers are two
 * bytes (type = (firstByte >> 1) & 0x3F), parameter sets are VPS(32)/SPS(33)/PPS(34), and an
 * IRAP NAL (16..23) marks a keyframe.
 */
class H265Reader(sink: SampleSink) : AnnexBReader(sink) {
    override val track = Track(Codec.H265)

    override fun headerSize() = 2

    /** nuh_layer_id 0: enhancement layers (MV-HEVC, SHVC) are carried along, never split frames (as ffmpeg). */
    override fun isBaseLayer(buf: ByteArray, start: Int): Boolean =
        (((buf[start].toInt() and 1) shl 5) or ((buf[start + 1].toInt() and 0xFF) shr 3)) == 0

    private fun type(buf: ByteArray, start: Int) = (buf[start].toInt() shr 1) and 0x3F

    override fun isVcl(buf: ByteArray, start: Int) = type(buf, start) in 0..31

    override fun isKeyframe(buf: ByteArray, start: Int) = type(buf, start) in 16..23

    /** first_slice_segment_in_pic_flag: the first bit after the 2-byte NAL header. */
    override fun isFirstSliceOfPicture(buf: ByteArray, start: Int, end: Int) =
        start + 2 < end && (buf[start + 2].toInt() and 0x80) != 0

    /** VPS/SPS/PPS/AUD, prefix SEI and types 41..44, 48..55 open an access unit (H.265 7.4.2.4.4). */
    override fun startsAccessUnit(buf: ByteArray, start: Int) = when (type(buf, start)) {
        in 32..35, 39, in 41..44, in 48..55 -> true
        else -> false
    }

    override fun keepNal(buf: ByteArray, start: Int, end: Int): Boolean {
        val t = type(buf, start)
        if (t in 32..34) {
            val nal = buf.copyOfRange(start, end)
            if (track.parameterSets.none { it.contentEquals(nal) }) {
                track.parameterSets.add(nal)
                if (t == 33) try { parseSps(nal) } catch (_: Exception) {}
            }
        }
        return t != 35 && t != 38 // AUD / filler
    }

    private fun parseSps(nal: ByteArray) {
        // Skip the 2-byte NAL header.
        val rbsp = NalUnitUtil.unescapeRbsp(nal, 2, nal.size - 2)
        val bit = ParsableBitArray(rbsp)
        bit.skipBits(4)            // sps_video_parameter_set_id
        val maxSubLayersMinus1 = bit.readBits(3)
        val temporalIdNesting = bit.readBit()
        skipProfileTierLevel(bit, maxSubLayersMinus1)
        bit.readUe()               // sps_seq_parameter_set_id
        val chromaFormatIdc = bit.readUe()
        if (chromaFormatIdc == 3) bit.skipBits(1) // separate_colour_plane_flag
        val picWidth = bit.readUe()
        val picHeight = bit.readUe()
        var left = 0; var right = 0; var top = 0; var bottom = 0
        if (bit.readBit()) {       // conformance_window_flag
            left = bit.readUe(); right = bit.readUe()
            top = bit.readUe(); bottom = bit.readUe()
        }
        val subWidthC = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
        val subHeightC = if (chromaFormatIdc == 1) 2 else 1
        track.width = picWidth - subWidthC * (left + right)
        track.height = picHeight - subHeightC * (top + bottom)
        track.chromaFormat = chromaFormatIdc
        track.bitDepthLuma = bit.readUe() + 8
        track.bitDepthChroma = bit.readUe() + 8
        track.temporalLayers = maxSubLayersMinus1 + 1
        track.temporalIdNested = temporalIdNesting

        // The rest of the SPS (H.265 7.3.2.2) only to reach the VUI: aspect ratio and colour.
        val log2MaxPocLsb = bit.readUe() + 4
        val orderingAll = bit.readBit()                    // sps_sub_layer_ordering_info_present_flag
        for (i in (if (orderingAll) 0 else maxSubLayersMinus1)..maxSubLayersMinus1) {
            bit.readUe(); bit.readUe(); bit.readUe()
        }
        repeat(6) { bit.readUe() }                         // block sizes, transform hierarchy depths
        if (bit.readBit() && bit.readBit()) skipScalingListData(bit) // scaling_list_enabled, data present
        bit.skipBits(2)                                    // amp, sample_adaptive_offset
        if (bit.readBit()) {                               // pcm_enabled_flag
            bit.skipBits(8); bit.readUe(); bit.readUe(); bit.skipBits(1)
        }
        skipShortTermRefPicSets(bit)
        if (bit.readBit()) {                               // long_term_ref_pics_present_flag
            repeat(bit.readUe()) { bit.skipBits(log2MaxPocLsb + 1) }
        }
        bit.skipBits(2)                                    // temporal_mvp, strong_intra_smoothing
        if (bit.readBit()) Vui.read(bit, track)            // vui_parameters_present_flag
    }

    private fun skipScalingListData(bit: ParsableBitArray) {
        for (sizeId in 0 until 4) {
            var matrixId = 0
            while (matrixId < 6) {
                if (!bit.readBit()) {
                    bit.readUe()                           // scaling_list_pred_matrix_id_delta
                } else {
                    val coefNum = minOf(64, 1 shl (4 + (sizeId shl 1)))
                    if (sizeId > 1) bit.readSe()           // scaling_list_dc_coef_minus8
                    repeat(coefNum) { bit.readSe() }
                }
                matrixId += if (sizeId == 3) 3 else 1
            }
        }
    }

    /** st_ref_pic_set() for every set in the SPS (7.3.7), including inter-RPS prediction. */
    private fun skipShortTermRefPicSets(bit: ParsableBitArray) {
        val count = bit.readUe()
        var previousDeltaPocs = 0
        for (idx in 0 until count) {
            var deltaPocs = 0
            if (idx != 0 && bit.readBit()) {               // inter_ref_pic_set_prediction_flag
                bit.skipBits(1); bit.readUe()              // delta_rps_sign, abs_delta_rps_minus1
                for (j in 0..previousDeltaPocs) {
                    val used = bit.readBit()               // used_by_curr_pic_flag
                    val useDelta = if (!used) bit.readBit() else true
                    if (used || useDelta) deltaPocs++
                }
            } else {
                val negative = bit.readUe()
                val positive = bit.readUe()
                repeat(negative + positive) { bit.readUe(); bit.skipBits(1) } // delta_poc_minus1, used
                deltaPocs = negative + positive
            }
            previousDeltaPocs = deltaPocs
        }
    }

    private fun skipProfileTierLevel(bit: ParsableBitArray, maxSubLayersMinus1: Int) {
        // general profile/tier/level: 2+1+5 + 32 + 4 + 44 + 8 = 96 bits.
        bit.skipBits(8)            // profile_space(2)+tier(1)+profile_idc(5)
        bit.skipBits(32)           // profile_compatibility_flags
        bit.skipBits(48)           // 4 source flags + 44 reserved/constraint bits
        bit.skipBits(8)            // general_level_idc
        val subProfilePresent = BooleanArray(maxSubLayersMinus1)
        val subLevelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            subProfilePresent[i] = bit.readBit()
            subLevelPresent[i] = bit.readBit()
        }
        if (maxSubLayersMinus1 > 0) {
            for (i in maxSubLayersMinus1 until 8) bit.skipBits(2) // reserved_zero_2bits
        }
        for (i in 0 until maxSubLayersMinus1) {
            if (subProfilePresent[i]) bit.skipBits(88)
            if (subLevelPresent[i]) bit.skipBits(8)
        }
    }
}
