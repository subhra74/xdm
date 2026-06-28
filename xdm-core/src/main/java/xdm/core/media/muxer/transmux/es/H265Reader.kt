package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.io.NalUnitUtil
import xdm.core.media.muxer.transmux.io.ParsableBitArray
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Sample
import xdm.core.media.muxer.transmux.sample.Track

/**
 * H.265/HEVC elementary stream reader. Same shape as [H264Reader] but HEVC NAL headers are two
 * bytes (type = (firstByte >> 1) & 0x3F), parameter sets are VPS(32)/SPS(33)/PPS(34), and an
 * IRAP NAL (16..23) marks a keyframe.
 */
class H265Reader(private val sink: SampleSink) : ElementaryStreamReader {
    override val track = Track(Codec.H265)
    private val out = ByteArrayBuilder()

    override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) {
        val end = offset + length
        out.reset()
        var isKeyframe = false
        var hasVcl = false

        var pos = NalUnitUtil.findStartCode(data, offset, end)
        while (pos < end) {
            val nalStart = pos + 3
            val next = NalUnitUtil.findStartCode(data, nalStart, end)
            val nalEnd = if (next < end && next > nalStart && data[next - 1].toInt() == 0) next - 1 else next
            if (nalStart < nalEnd) {
                val nalType = (data[nalStart].toInt() shr 1) and 0x3F
                when (nalType) {
                    32, 33, 34 -> {
                        val nal = data.copyOfRange(nalStart, nalEnd)
                        if (track.parameterSets.none { it.contentEquals(nal) }) {
                            track.parameterSets.add(nal)
                            if (nalType == 33) try { parseSps(nal) } catch (_: Exception) {}
                        }
                        appendLengthPrefixed(data, nalStart, nalEnd)
                    }
                    35, 38 -> { /* drop AUD / filler */ }
                    else -> {
                        if (nalType in 16..23) isKeyframe = true
                        if (nalType in 0..31) hasVcl = true
                        appendLengthPrefixed(data, nalStart, nalEnd)
                    }
                }
            }
            pos = next
        }

        if (out.size == 0 && !hasVcl) return
        val fileOffset = sink.writeSampleData(out.buffer, 0, out.size)
        val sampleDts = if (dts >= 0) dts else pts
        track.samples.add(Sample(fileOffset, out.size, pts, sampleDts, isKeyframe))
    }

    override fun finish() {}

    private fun appendLengthPrefixed(data: ByteArray, start: Int, end: Int) {
        val len = end - start
        out.writeInt(len)
        out.write(data, start, len)
    }

    private fun parseSps(nal: ByteArray) {
        // Skip the 2-byte NAL header.
        val rbsp = NalUnitUtil.unescapeRbsp(nal, 2, nal.size - 2)
        val bit = ParsableBitArray(rbsp)
        bit.skipBits(4)            // sps_video_parameter_set_id
        val maxSubLayersMinus1 = bit.readBits(3)
        bit.skipBits(1)            // sps_temporal_id_nesting_flag
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
