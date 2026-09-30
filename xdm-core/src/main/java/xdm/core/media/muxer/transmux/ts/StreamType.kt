package xdm.core.media.muxer.transmux.ts

import xdm.core.media.muxer.transmux.sample.Codec

/**
 * Maps MPEG-TS PMT `stream_type` values (ISO/IEC 13818-1 Table 2-34, plus common registrations)
 * to our [Codec] enum. Some streams (AV1, Opus) are carried as private data (0x06) and only
 * distinguishable via a registration/format descriptor inside the PMT ES_info loop.
 */
object StreamType {
    const val MPEG1_VIDEO = 0x01
    const val MPEG2_VIDEO = 0x02
    const val MPEG4_VIDEO = 0x10
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
    const val DTS = 0x82            // ATSC/Blu-ray
    const val DTS_HD = 0x85         // Blu-ray
    const val TRUEHD = 0x83         // Blu-ray
    const val DTS_HD_MA = 0x8A      // Blu-ray

    // Registration descriptor format identifiers (4cc) seen in the PMT.
    const val FOURCC_AC3 = 0x41432D33   // "AC-3"
    const val FOURCC_EAC3 = 0x45414333  // "EAC3"
    const val FOURCC_AV01 = 0x41563031  // "AV01"
    const val FOURCC_OPUS = 0x4F707573  // "Opus"

    /**
     * Resolves a PMT entry. [Codec.UNKNOWN] means "not an audio/video stream we recognise" (ID3
     * timed metadata 0x15, SCTE-35 cues 0x86, DSM-CC, teletext/subtitles, ...): callers skip it.
     * Recognised audio/video with no native reader maps to [Codec.OTHER_VIDEO]/[Codec.OTHER_AUDIO]
     * so the caller can fail loudly rather than silently drop a track. [dvbAudio] is the codec
     * signalled by a DVB AC-3 (0x6A) / E-AC-3 (0x7A) descriptor, for private-data streams.
     */
    fun fromStreamType(streamType: Int, registration: Int?, dvbAudio: Codec? = null): Codec {
        return when (streamType) {
            H264 -> Codec.H264
            H265 -> Codec.H265
            H266 -> Codec.H266
            ADTS_AAC -> Codec.AAC
            // No native reader for these.
            MPEG1_VIDEO, MPEG2_VIDEO, MPEG4_VIDEO -> Codec.OTHER_VIDEO
            LATM_AAC, DTS, DTS_HD, TRUEHD, DTS_HD_MA -> Codec.OTHER_AUDIO
            // MPEG-1/2 audio, any layer (MP1/MP2/MP3): Codec.MP3 covers the whole family.
            MPEG1_AUDIO, MPEG2_AUDIO -> Codec.MP3
            AC3 -> Codec.AC3
            EAC3 -> Codec.EAC3
            PRIVATE_DATA -> when (registration) {
                FOURCC_AC3 -> Codec.AC3
                FOURCC_EAC3 -> Codec.EAC3
                FOURCC_AV01 -> Codec.AV1
                FOURCC_OPUS -> Codec.OPUS
                else -> dvbAudio ?: Codec.UNKNOWN
            }
            else -> Codec.UNKNOWN
        }
    }
}
