// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.sample

/** Codec identity of an elementary stream, resolved from the PMT stream_type/descriptors. */
enum class Codec(val isVideo: Boolean) {
    H264(true),
    H265(true),
    AV1(true),
    H266(true),
    VP8(true),
    VP9(true),
    /** MPEG-1 or MPEG-2 video (Track.objectTypeIndication says which). */
    MPEG_VIDEO(true),
    /** MPEG-4 Part 2 visual (ASP/SP, e.g. DivX/Xvid-style streams). */
    MPEG4_VISUAL(true),
    AAC(false),
    AC3(false),
    EAC3(false),
    MP3(false),
    OPUS(false),
    VORBIS(false),
    /** DTS (core, with any DTS-HD extension substream kept in the same sample). */
    DTS(false),
    /** Any video/audio codec copied verbatim from an MP4 input (config + samples already in MP4 form). */
    OTHER_VIDEO(true),
    OTHER_AUDIO(false),
    UNKNOWN(false);
}
