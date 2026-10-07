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
            u8(0xFC or 1)               // chromaFormat (assume 4:2:0)
            u8(0xF8)                    // bitDepthLumaMinus8 = 0
            u8(0xF8)                    // bitDepthChromaMinus8 = 0
            u16(0)                      // avgFrameRate
            u8(0x0B)                    // constFrameRate(0)+numTempLayers(1)+nested(0)+lenSizeMinus1(3)
            // numOfArrays + arrays for VPS/SPS/PPS that are present.
            val arrays = listOf(32 to vps, 33 to sps, 34 to pps).filter { it.second.isNotEmpty() }
            u8(arrays.size)
            for ((type, list) in arrays) {
                u8(0x80 or type)        // array_completeness=1 + NAL_unit_type
                u16(list.size)
                for (n in list) { u16(n.size); bytes(n) }
            }
        }
    }

    const val OTI_AAC = 0x40
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
    fun writeEsds(buf: BoxBuf, objectTypeIndication: Int, asc: ByteArray?) {
        buf.box("esds") {
            fullBoxHeader(0, 0)
            // ES_Descriptor
            val dsi = asc
            val decoderSpecific = if (dsi != null) 2 + dsi.size else 0
            val decoderConfigLen = 13 + decoderSpecific
            val esLen = 3 + (2 + decoderConfigLen) + (2 + 1)
            u8(0x03); u8(esLen)
            u16(0)                      // ES_ID
            u8(0)                       // stream priority/flags
            // DecoderConfigDescriptor
            u8(0x04); u8(decoderConfigLen)
            u8(objectTypeIndication)
            u8(0x15)                    // streamType=audio(5)<<2 | upstream0 | reserved1
            u24(0)                      // bufferSizeDB
            u32(0)                      // maxBitrate
            u32(0)                      // avgBitrate
            if (dsi != null) {
                u8(0x05); u8(dsi.size); bytes(dsi)
            }
            // SLConfigDescriptor
            u8(0x06); u8(1); u8(0x02)
        }
    }
}
