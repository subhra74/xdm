// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.ts

/**
 * Identifies the codec of a TS stream from its first PES payloads, for streams whose PMT entry
 * doesn't say (private data, unknown stream types, no PMT at all), as ffmpeg probes them. Only
 * strong evidence counts, since the start codes of the video formats overlap:
 *  - MPEG-4 Part 2: a visual object sequence (`B0`) or a VOP (`B6`): both are invalid NAL headers;
 *  - MPEG-1/2 video: a sequence header (`00 00 01 B3`) or GOP header (`B8`), without the above (`B3`
 *    is also MPEG-4's group_of_vop code);
 *  - HEVC: two of VPS/SPS/PPS, or an access unit delimiter as the first unit, with a valid two-byte
 *    NAL header (layer 0, temporal id 1-7);
 *  - H.264: an SPS with a known profile followed by a PPS, or an access unit delimiter as the first
 *    unit. MPEG-2 slice start codes take every value 0x01-0xAF, so a lone H.264-looking byte isn't
 *    enough; an AUD has to open the PES, and NAL units don't count at all in a PES that opens with
 *    `00` (an MPEG picture or video object).
 * Start codes never occur inside H.264/HEVC NAL data (emulation prevention), so an MPEG start code
 * in a payload is never a NAL stream's.
 */
object EsProbe {

    /** Codec from one PES payload: video first when [streamId] says video (0xE0-0xEF), else audio first. */
    fun identify(data: ByteArray, offset: Int, length: Int, streamId: Int): StreamReader? =
        if (streamId in 0xE0..0xEF) video(data, offset, length) ?: audio(data, offset, length)
        else audio(data, offset, length) ?: video(data, offset, length)

    /** Audio codec from the first bytes of a PES payload, or null if they don't say. */
    fun audio(data: ByteArray, offset: Int, length: Int): StreamReader? {
        if (length < 6) return null
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        return when {
            b0 == 0xFF && (b1 and 0xF6) == 0xF0 -> StreamReader.ADTS                       // ADTS: layer 00
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x06) != 0 -> StreamReader.MPEG_AUDIO
            b0 == 0x56 && (b1 and 0xE0) == 0xE0 -> StreamReader.LATM                       // LOAS sync 0x2B7
            b0 == 0x0B && b1 == 0x77 ->                                                    // AC-3 family: bsid
                if (((data[offset + 5].toInt() and 0xFF) shr 3) > 10) StreamReader.EAC3 else StreamReader.AC3
            b0 == 0x7F && b1 == 0xFE && (data[offset + 2].toInt() and 0xFF) == 0x80 &&
                (data[offset + 3].toInt() and 0xFF) == 0x01 -> StreamReader.DTS
            else -> null
        }
    }

    /** Video codec from the start codes in a PES payload, or null without strong evidence. */
    fun video(data: ByteArray, offset: Int, length: Int): StreamReader? {
        val end = offset + length
        var firstCode = -1
        var mpeg4 = false; var mpeg2 = false
        var h264Sps = false; var h264Pps = false; var h264Aud = false
        var hevcParams = 0; var hevcAud = false
        var i = offset
        while (i + 3 < end) {
            if (data[i].toInt() != 0 || data[i + 1].toInt() != 0 || data[i + 2].toInt() != 1) { i++; continue }
            val code = data[i + 3].toInt() and 0xFF
            val next = if (i + 4 < end) data[i + 4].toInt() and 0xFF else -1
            val next2 = if (i + 5 < end) data[i + 5].toInt() and 0xFF else -1
            val first = firstCode < 0
            if (first) firstCode = code
            when {
                // MPEG-4 Part 2: VOS or VOP (B3 is its group_of_vop code, so B3 alone can't decide).
                code == 0xB0 || code == 0xB6 -> mpeg4 = true
                code == 0xB3 || code == 0xB8 -> mpeg2 = true
                // NAL units: never when the PES opens with 00 (an MPEG-1/2 picture or MPEG-4 video
                // object), whose slice codes 01-AF can look like any NAL header.
                firstCode != 0x00 && code and 0x80 == 0 && next >= 0 -> {
                    // HEVC: forbidden 0, nuh_layer_id 0, temporal id 1-7.
                    val hevcType = (code shr 1) and 0x3F
                    val hevcHeader = code and 0x01 == 0 && next and 0xF8 == 0 && next and 0x07 != 0
                    if (hevcHeader && hevcType in 32..34) hevcParams = hevcParams or (1 shl (hevcType - 32))
                    if (first && hevcHeader && hevcType == 35 && next2 >= 0 && next2 and 0x1F == 0x10) hevcAud = true
                    // H.264: AUD (nal_ref_idc 0, primary_pic_type + stop bit), SPS with a known profile, PPS.
                    when (code and 0x1F) {
                        9 -> if (first && code == 0x09 && next and 0x1F == 0x10) h264Aud = true
                        7 -> if (code and 0x60 != 0 && next in H264_PROFILES) h264Sps = true
                        8 -> if (h264Sps && code and 0x60 != 0) h264Pps = true
                    }
                }
            }
            i += 3
        }
        return when {
            mpeg4 -> StreamReader.MPEG4_VISUAL
            mpeg2 -> StreamReader.MPEG_VIDEO
            Integer.bitCount(hevcParams) >= 2 || hevcAud -> StreamReader.H265
            (h264Sps && h264Pps) || h264Aud -> StreamReader.H264
            else -> null
        }
    }

    private val H264_PROFILES = setOf(66, 77, 88, 100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
}
