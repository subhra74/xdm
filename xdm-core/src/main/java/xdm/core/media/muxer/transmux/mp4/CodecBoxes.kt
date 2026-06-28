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
    const val OTI_MP3 = 0x6B

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
