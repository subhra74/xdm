package xdm.core.media.muxer.transmux.sample

/** Codec identity of an elementary stream, resolved from the PMT stream_type/descriptors. */
enum class Codec(val isVideo: Boolean) {
    H264(true),
    H265(true),
    AV1(true),
    H266(true),
    VP8(true),
    VP9(true),
    AAC(false),
    AC3(false),
    EAC3(false),
    MP3(false),
    OPUS(false),
    VORBIS(false),
    /** Any video/audio codec copied verbatim from an MP4 input (config + samples already in MP4 form). */
    OTHER_VIDEO(true),
    OTHER_AUDIO(false),
    UNKNOWN(false);
}
