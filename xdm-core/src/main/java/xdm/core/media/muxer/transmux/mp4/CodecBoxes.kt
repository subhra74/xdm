// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.mp4

import xdm.core.media.muxer.transmux.io.NalUnitUtil
import xdm.core.media.muxer.transmux.sample.Track

/** Builders for the codec-specific configuration boxes embedded inside the stsd sample entry. */
object CodecBoxes {

    /** avcC payload + box, from the H.264 SPS/PPS NAL units (each including its NAL header byte). */
    fun writeAvcC(buf: BoxBuf, track: Track) {
        val spsList = track.parameterSets.filter { (it[0].toInt() and 0x1F) == 7 }
        val ppsList = track.parameterSets.filter { (it[0].toInt() and 0x1F) == 8 }
        val sps = spsList.firstOrNull() ?: ByteArray(4)
        buf.box("avcC") {
            u8(1)                       // configurationVersion
            u8(sps[1].toInt() and 0xFF) // AVCProfileIndication
            u8(sps[2].toInt() and 0xFF) // profile_compatibility
            u8(sps[3].toInt() and 0xFF) // AVCLevelIndication
            u8(0xFF)                    // reserved(6)=1 + lengthSizeMinusOne=3
            u8(0xE0 or (spsList.size and 0x1F))
            for (s in spsList) { u16(s.size); bytes(s) }
            u8(ppsList.size)
            for (p in ppsList) { u16(p.size); bytes(p) }
            // ISO/IEC 14496-15 5.3.3.1: every profile but Baseline/Main/Extended adds these (as ffmpeg).
            if ((sps[1].toInt() and 0xFF) !in intArrayOf(66, 77, 88)) {
                u8(0xFC or (track.chromaFormat and 3))
                u8(0xF8 or ((track.bitDepthLuma - 8) and 7))
                u8(0xF8 or ((track.bitDepthChroma - 8) and 7))
                u8(0)                   // numOfSequenceParameterSetExt
            }
        }
    }

    /** hvcC payload + box, from H.265 VPS/SPS/PPS. General PTL is byte-aligned at SPS RBSP[1..12]. */
    fun writeHvcC(buf: BoxBuf, track: Track) {
        val vps = track.parameterSets.filter { ((it[0].toInt() shr 1) and 0x3F) == 32 }
        val sps = track.parameterSets.filter { ((it[0].toInt() shr 1) and 0x3F) == 33 }
        val pps = track.parameterSets.filter { ((it[0].toInt() shr 1) and 0x3F) == 34 }
        val spsNal = sps.firstOrNull()
        val rbsp = if (spsNal != null) NalUnitUtil.unescapeRbsp(spsNal, 2, spsNal.size - 2) else ByteArray(13)
        val profileByte = rbsp.getOrElse(1) { 0 }.toInt() and 0xFF
        val compat = ByteArray(4) { rbsp.getOrElse(2 + it) { 0 } }
        val constraints = ByteArray(6) { rbsp.getOrElse(6 + it) { 0 } }
        val levelIdc = rbsp.getOrElse(12) { 0 }.toInt() and 0xFF

        buf.box("hvcC") {
            u8(1)                       // configurationVersion
            u8(profileByte)             // profile_space/tier/profile_idc
            bytes(compat)               // general_profile_compatibility_flags
            bytes(constraints)          // general_constraint_indicator_flags
            u8(levelIdc)                // general_level_idc
            u16(0xF000)                 // min_spatial_segmentation_idc (reserved 1111 + 0)
            u8(0xFC)                    // parallelismType
            u8(0xFC or (track.chromaFormat and 3))
            u8(0xF8 or ((track.bitDepthLuma - 8) and 7))
            u8(0xF8 or ((track.bitDepthChroma - 8) and 7))
            u16(0)                      // avgFrameRate
            // constantFrameRate(2) = 0, numTemporalLayers(3), temporalIdNested(1), lengthSizeMinusOne(2) = 3
            u8(((track.temporalLayers and 7) shl 3) or ((if (track.temporalIdNested) 1 else 0) shl 2) or 3)
            // numOfArrays + arrays for VPS/SPS/PPS that are present.
            val arrays = listOf(32 to vps, 33 to sps, 34 to pps).filter { it.second.isNotEmpty() }
            u8(arrays.size)
            for ((type, list) in arrays) {
                // array_completeness 0: hev1 keeps parameter sets in-band too (ffmpeg writes 0 for hev1).
                u8(type)
                u16(list.size)
                for (n in list) { u16(n.size); bytes(n) }
            }
        }
    }

    const val OTI_AAC = 0x40
    /** MPEG-4 Part 2 visual (ISO/IEC 14496-2). */
    const val OTI_MPEG4_VISUAL = 0x20
    /** MPEG-2 video, Main profile (ISO/IEC 13818-2); the type ffmpeg writes for any MPEG-2 video. */
    const val OTI_MPEG2_VIDEO = 0x61
    /** MPEG-1 video (ISO/IEC 11172-2). */
    const val OTI_MPEG1_VIDEO = 0x6A
    /** DTS (mp4ra.org registration), as ffmpeg writes DTS in MP4: an mp4a entry without a ddts box. */
    const val OTI_DTS = 0xA9
    /** MPEG-1 audio (ISO/IEC 11172-3, any layer). */
    const val OTI_MPEG1_AUDIO = 0x6B
    /** MPEG-2 low-sample-rate audio (ISO/IEC 13818-3: 16/22.05/24 kHz and MPEG-2.5 rates). */
    const val OTI_MPEG2_AUDIO = 0x69

    /**
     * dOps box (Opus in ISO-BMFF §4.3.2) from an Ogg/Matroska `OpusHead`: the same fields, but
     * big-endian and without the magic, and version 0.
     */
    fun writeDOps(buf: BoxBuf, opusHead: ByteArray) {
        fun le16(o: Int) = (opusHead[o].toInt() and 0xFF) or ((opusHead[o + 1].toInt() and 0xFF) shl 8)
        val channels = opusHead[9].toInt() and 0xFF
        val family = opusHead[18].toInt() and 0xFF
        buf.box("dOps") {
            u8(0)                                   // Version
            u8(channels)                            // OutputChannelCount
            u16(le16(10))                           // PreSkip
            u32(le16(12).toLong() or (le16(14).toLong() shl 16)) // InputSampleRate
            u16(le16(16))                           // OutputGain
            u8(family)                              // ChannelMappingFamily
            if (family != 0 && opusHead.size >= 21 + channels) {
                bytes(opusHead.copyOfRange(19, 21 + channels)) // StreamCount, CoupledCount, ChannelMapping
            }
        }
    }

    /** Inverse of [writeDOps]: rebuilds an `OpusHead` (Matroska's A_OPUS CodecPrivate) from dOps contents. */
    fun opusHeadFromDOps(dOps: ByteArray): ByteArray? {
        if (dOps.size < 11) return null
        val tail = dOps.copyOfRange(10, dOps.size)   // ChannelMappingFamily [+ StreamCount, CoupledCount, mapping]
        val h = ByteArray(18 + tail.size)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(h)
        h[8] = 1                                      // version
        h[9] = dOps[1]                                // channel count
        h[10] = dOps[3]; h[11] = dOps[2]              // pre-skip, BE -> LE
        h[12] = dOps[7]; h[13] = dOps[6]; h[14] = dOps[5]; h[15] = dOps[4] // input sample rate
        h[16] = dOps[9]; h[17] = dOps[8]              // output gain
        tail.copyInto(h, 18)
        return h
    }

    /** esds box with an ES/DecoderConfig/SL descriptor chain. ASC is optional (absent for MP3). */
    /**
     * esds (ISO/IEC 14496-14) for an MPEG-4 Systems object type: [dsi] is the DecoderSpecificInfo
     * (AudioSpecificConfig, MPEG-1/2 sequence header, MPEG-4 Part 2 VOL...). Descriptor sizes use the
     * 4-byte expandable form, so a long DecoderSpecificInfo stays valid (a 1-byte size only goes to 127).
     */
    fun writeEsds(buf: BoxBuf, objectTypeIndication: Int, dsi: ByteArray?, video: Boolean = false, esId: Int = 0) {
        fun descriptor(tag: Int, size: Int) {
            buf.u8(tag)
            for (i in 3 downTo 1) buf.u8(((size ushr (7 * i)) and 0x7F) or 0x80)
            buf.u8(size and 0x7F)
        }
        buf.box("esds") {
            fullBoxHeader(0, 0)
            val dsiLen = if (dsi != null) 5 + dsi.size else 0
            descriptor(0x03, 3 + 5 + 13 + dsiLen + 5 + 1) // ES_Descriptor
            u16(esId)
            u8(0)                       // stream priority/flags
            descriptor(0x04, 13 + dsiLen) // DecoderConfigDescriptor
            u8(objectTypeIndication)
            u8(if (video) 0x11 else 0x15) // streamType visual(4)/audio(5) << 2 | upstream 0 | reserved 1
            u24(0)                      // bufferSizeDB
            u32(0)                      // maxBitrate
            u32(0)                      // avgBitrate
            if (dsi != null) {
                descriptor(0x05, dsi.size)
                bytes(dsi)
            }
            descriptor(0x06, 1)         // SLConfigDescriptor
            u8(0x02)
        }
    }
}
