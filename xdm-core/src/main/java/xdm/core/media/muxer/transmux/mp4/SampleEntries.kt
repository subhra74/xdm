// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.mp4

import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track

/**
 * Builds the stsd sample entry (avc1/hev1/mp4a/ac-3/ec-3/Opus...) for a track demuxed from an
 * elementary stream, from its decoder config. Readers also use it to freeze the entry of a config
 * that is about to change mid-stream (Track.additionalSampleEntries).
 */
object SampleEntries {

    /** The complete sample entry box for [t]'s current config, or null if its codec has none. */
    fun build(t: Track): ByteArray? {
        val b = BoxBuf()
        write(b, t)
        return if (b.size() == 0) null else b.toByteArray()
    }

    fun write(buf: BoxBuf, t: Track) {
        when (t.codec) {
            Codec.H264 -> visualSampleEntry(buf, "avc1", t) { CodecBoxes.writeAvcC(this, t) }
            Codec.H265 -> visualSampleEntry(buf, "hev1", t) { CodecBoxes.writeHvcC(this, t) }
            Codec.AAC -> audioSampleEntry(buf, "mp4a", t) {
                CodecBoxes.writeEsds(this, CodecBoxes.OTI_AAC, t.audioSpecificConfig)
            }
            Codec.MP3 -> audioSampleEntry(buf, "mp4a", t) {
                val oti = if (t.sampleRate >= 32000) CodecBoxes.OTI_MPEG1_AUDIO else CodecBoxes.OTI_MPEG2_AUDIO
                CodecBoxes.writeEsds(this, oti, null)
            }
            Codec.AC3 -> audioSampleEntry(buf, "ac-3", t) {
                box("dac3") { bytes(t.decoderConfigRecord ?: ByteArray(3)) }
            }
            Codec.EAC3 -> audioSampleEntry(buf, "ec-3", t) {
                box("dec3") { bytes(t.decoderConfigRecord ?: ByteArray(5)) }
            }
            Codec.OPUS -> t.decoderConfigRecord?.let { head ->
                audioSampleEntry(buf, "Opus", t) { CodecBoxes.writeDOps(this, head) }
            }
            // MPEG-1/2 and MPEG-4 Part 2 video: mp4v, the sequence header / VOL as DecoderSpecificInfo.
            Codec.MPEG_VIDEO -> visualSampleEntry(buf, "mp4v", t) {
                CodecBoxes.writeEsds(this, t.objectTypeIndication, t.decoderConfigRecord, video = true)
            }
            Codec.MPEG4_VISUAL -> visualSampleEntry(buf, "mp4v", t) {
                CodecBoxes.writeEsds(this, CodecBoxes.OTI_MPEG4_VISUAL, t.decoderConfigRecord, video = true)
            }
            Codec.DTS -> audioSampleEntry(buf, "mp4a", t) { CodecBoxes.writeEsds(this, CodecBoxes.OTI_DTS, null) }
            else -> {}
        }
    }

    private inline fun visualSampleEntry(buf: BoxBuf, type: String, t: Track, config: BoxBuf.() -> Unit) {
        buf.box(type) {
            repeat(6) { u8(0) }             // reserved
            u16(1)                          // data_reference_index
            u16(0); u16(0)                  // pre_defined, reserved
            u32(0); u32(0); u32(0)          // pre_defined
            u16(t.width); u16(t.height)
            u32(0x00480000); u32(0x00480000) // resolution 72dpi
            u32(0)
            u16(1)                          // frame_count
            repeat(32) { u8(0) }            // compressorname
            u16(0x0018)                     // depth
            u16(0xFFFF)                     // pre_defined
            config()
            // As ffmpeg: colr when the bitstream describes its colours (HDR10 needs it to be shown as
            // HDR), pasp when it gives a sample aspect ratio (anamorphic video).
            if (t.colourPrimaries !in intArrayOf(-1, 2) && t.transferCharacteristics !in intArrayOf(-1, 2) &&
                t.matrixCoefficients !in intArrayOf(-1, 2)
            ) {
                box("colr") {
                    fourcc("nclx")
                    u16(t.colourPrimaries); u16(t.transferCharacteristics); u16(t.matrixCoefficients)
                    u8(if (t.fullRange) 0x80 else 0)
                }
            }
            if (t.sarNum > 0 && t.sarDen > 0) box("pasp") { u32(t.sarNum); u32(t.sarDen) }
        }
    }

    private inline fun audioSampleEntry(buf: BoxBuf, type: String, t: Track, config: BoxBuf.() -> Unit) {
        buf.box(type) {
            repeat(6) { u8(0) }             // reserved
            u16(1)                          // data_reference_index
            u32(0); u32(0)                  // version/revision/vendor
            u16(if (t.channelCount > 0) t.channelCount else 2)
            u16(16)                         // samplesize
            u16(0); u16(0)                  // pre_defined, reserved
            u32((t.sampleRate.toLong() and 0xFFFF) shl 16)
            config()
        }
    }
}
