package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.io.NalUnitUtil
import xdm.core.media.muxer.transmux.io.ParsableBitArray
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Sample
import xdm.core.media.muxer.transmux.sample.Track

/**
 * H.264/AVC elementary stream reader.
 *
 * For each PES (one access unit in HLS) it scans Annex-B NAL units, harvests SPS/PPS for the
 * avcC config, detects IDR frames as keyframes, and re-emits the NAL units length-prefixed
 * (4-byte) into the mdat as a single MP4 sample carrying the PES timestamps.
 */
class H264Reader(private val sink: SampleSink) : ElementaryStreamReader {
    override val track = Track(Codec.H264)

    private val out = ByteArrayBuilder()

    override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) {
        val end = offset + length
        out.reset()
        var isKeyframe = false
        var hasVcl = false

        var pos = NalUnitUtil.findStartCode(data, offset, end)
        while (pos < end) {
            // findStartCode returns the index of a 3-byte 00 00 01, so the NAL begins at pos+3.
            val nalStart = pos + 3
            val next = NalUnitUtil.findStartCode(data, nalStart, end)
            // Trim the extra leading zero of a following 4-byte start code.
            val nalEnd = if (next < end && next > nalStart && data[next - 1].toInt() == 0) next - 1 else next
            if (nalStart < nalEnd) {
                val nalType = data[nalStart].toInt() and 0x1F
                when (nalType) {
                    7 -> addParameterSet(data, nalStart, nalEnd) { parseSps(it) }
                    8 -> addParameterSet(data, nalStart, nalEnd) { /* PPS: no fields we need */ }
                    9, 12 -> { /* drop AUD / filler */ }
                    else -> {
                        if (nalType == 5) isKeyframe = true
                        if (nalType in 1..5) hasVcl = true
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

    override fun finish() { /* nothing buffered across PES */ }

    private inline fun addParameterSet(data: ByteArray, start: Int, end: Int, parse: (ByteArray) -> Unit) {
        val nal = data.copyOfRange(start, end)
        // Deduplicate: parameter sets repeat on every keyframe.
        if (track.parameterSets.none { it.contentEquals(nal) }) {
            track.parameterSets.add(nal)
            try { parse(nal) } catch (_: Exception) { /* tolerate malformed SPS */ }
        }
        // Keep parameter sets in-band as well for player robustness.
        appendLengthPrefixed(data, start, end)
    }

    private fun appendLengthPrefixed(data: ByteArray, start: Int, end: Int) {
        val len = end - start
        out.writeInt(len)
        out.write(data, start, len)
    }

    private fun parseSps(nal: ByteArray) {
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
            bit.readUe()           // bit_depth_luma_minus8
            bit.readUe()           // bit_depth_chroma_minus8
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
    }

    companion object {
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
