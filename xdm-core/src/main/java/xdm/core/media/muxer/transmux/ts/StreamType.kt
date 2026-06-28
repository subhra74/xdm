package xdm.core.media.muxer.transmux.ts

import xdm.core.media.muxer.transmux.sample.Codec

/**
 * Maps MPEG-TS PMT `stream_type` values (ISO/IEC 13818-1 Table 2-34, plus common registrations)
 * to our [Codec] enum. Some streams (AV1, Opus) are carried as private data (0x06) and only
 * distinguishable via a registration/format descriptor inside the PMT ES_info loop.
 */
object StreamType {
    const val MPEG1_AUDIO = 0x03
    const val MPEG2_AUDIO = 0x04
    const val ADTS_AAC = 0x0F
    const val LATM_AAC = 0x11
    const val H264 = 0x1B
    const val H265 = 0x24
    const val H266 = 0x33
    const val PRIVATE_DATA = 0x06   // AC-3/E-AC-3 (DVB), AV1, Opus via descriptor
    const val AC3 = 0x81            // ATSC
    const val EAC3 = 0x87           // ATSC
    const val AC3_DVB = 0x06        // distinguished by AC-3 descriptor (tag 0x6A)

    // Registration descriptor format identifiers (4cc) seen in the PMT.
    const val FOURCC_AC3 = 0x41432D33   // "AC-3"
    const val FOURCC_EAC3 = 0x45414333  // "EAC3"
    const val FOURCC_AV01 = 0x41563031  // "AV01"
    const val FOURCC_OPUS = 0x4F707573  // "Opus"

    fun fromStreamType(streamType: Int, registration: Int?): Codec {
        return when (streamType) {
            H264 -> Codec.H264
            H265 -> Codec.H265
            H266 -> Codec.H266
            ADTS_AAC -> Codec.AAC
            // LATM-framed AAC isn't handled natively; map to UNKNOWN so the caller falls back to ffmpeg.
            LATM_AAC -> Codec.UNKNOWN
            MPEG1_AUDIO, MPEG2_AUDIO -> Codec.MP3
            AC3 -> Codec.AC3
            EAC3 -> Codec.EAC3
            PRIVATE_DATA -> when (registration) {
                FOURCC_AC3 -> Codec.AC3
                FOURCC_EAC3 -> Codec.EAC3
                FOURCC_AV01 -> Codec.AV1
                FOURCC_OPUS -> Codec.OPUS
                else -> Codec.UNKNOWN
            }
            else -> Codec.UNKNOWN
        }
    }
}
