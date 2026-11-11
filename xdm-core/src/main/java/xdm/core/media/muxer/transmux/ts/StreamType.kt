// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.ts

/** The elementary stream readers the TS demuxer can attach to a PID. */
enum class StreamReader { H264, H265, ADTS, LATM, MPEG_AUDIO, AC3, EAC3, OPUS, MPEG_VIDEO, MPEG4_VISUAL, DTS }

/** What to do with one PMT entry. */
sealed class StreamMapping {
    class Read(val reader: StreamReader) : StreamMapping()

    /** Identify the codec from the first PES payloads; [fallback] if they don't tell (null: skip). */
    class Probe(val fallback: StreamReader?) : StreamMapping()

    /** Not read: not audio/video (metadata, subtitles, SCTE-35...) or, with [unsupported], a codec we can't write. */
    class Skip(val unsupported: String? = null) : StreamMapping()
}

/**
 * Maps PMT `stream_type` values (ISO/IEC 13818-1 table 2-34) plus registration and DVB descriptors
 * to readers, following the stream type tables of ffmpeg's MPEG-TS demuxer (ISO, HDMV/Blu-ray when
 * the program carries an `HDMV`/`HDPR` registration, ATSC, registration and descriptor based).
 * Like ffmpeg, the AAC types are confirmed from content: some encoders put LATM under 0x0F or
 * ADTS under 0x11, and private data (0x06) without a descriptor is identified by its bytes.
 */
object StreamType {
    const val PRIVATE_DATA = 0x06

    private fun fourcc(s: String) =
        (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code

    private val HDMV = fourcc("HDMV")
    private val HDPR = fourcc("HDPR")

    fun map(
        streamType: Int,
        programRegistration: Int?,
        registration: Int?,
        dvbAudio: StreamReader?,
        /** A descriptor of a stream that isn't audio or video (teletext, subtitles, VBI, data). */
        nonAvDescriptor: Boolean,
        /** The DVB AC-4 descriptor (extension 0x15). */
        ac4Descriptor: Boolean = false,
    ): StreamMapping {
        val bluRay = programRegistration == HDMV || programRegistration == HDPR
        if (bluRay) {
            when (streamType) {
                0x80 -> return StreamMapping.Skip("Blu-ray LPCM")
                0x81 -> return StreamMapping.Read(StreamReader.AC3)
                0x82, 0x85, 0x86 -> return StreamMapping.Read(StreamReader.DTS)
                0x83 -> return StreamMapping.Skip("TrueHD")
                0x84, 0xA1 -> return StreamMapping.Read(StreamReader.EAC3)
                0xA2 -> return StreamMapping.Skip("DTS Express")
                0x90, 0x91, 0x92 -> return StreamMapping.Skip() // PGS / IGS / text subtitles
            }
        }
        return when (streamType) {
            0x01, 0x02 -> StreamMapping.Read(StreamReader.MPEG_VIDEO)
            0x03 -> StreamMapping.Read(StreamReader.MPEG_AUDIO)
            0x04 -> StreamMapping.Probe(StreamReader.MPEG_AUDIO)
            0x0F -> StreamMapping.Probe(StreamReader.ADTS)
            0x10 -> StreamMapping.Read(StreamReader.MPEG4_VISUAL)
            0x11 -> StreamMapping.Probe(StreamReader.LATM)
            0x1B -> StreamMapping.Read(StreamReader.H264)
            0x24 -> StreamMapping.Read(StreamReader.H265)
            0x1C -> StreamMapping.Skip("MPEG-4 audio without LATM/ADTS")
            0x20 -> StreamMapping.Skip("H.264 MVC dependent view")
            // LCEVC: an enhancement layer over the base video, which is kept; not a lost stream.
            0x36 -> StreamMapping.Skip()
            0x21 -> StreamMapping.Skip("JPEG 2000")
            0x32 -> StreamMapping.Skip("JPEG XS")
            0x33 -> StreamMapping.Skip("H.266/VVC")
            0x42 -> StreamMapping.Skip("AVS")
            0xD1 -> StreamMapping.Skip("Dirac")
            0xD2, 0xD4 -> StreamMapping.Skip("AVS2/AVS3")
            0xEA -> StreamMapping.Skip("VC-1")
            0x81 -> StreamMapping.Read(StreamReader.AC3)   // ATSC
            0x87 -> StreamMapping.Read(StreamReader.EAC3)  // ATSC
            0x8A -> StreamMapping.Read(StreamReader.DTS)
            PRIVATE_DATA -> privateData(registration, dvbAudio, nonAvDescriptor, ac4Descriptor)
            // DigiCipher II video (MPEG-2) outside Blu-ray, as Media3; the content decides first.
            0x80 -> StreamMapping.Probe(StreamReader.MPEG_VIDEO)
            // Known non-A/V: private sections, DSM-CC, ID3 timed metadata, SCTE-35.
            0x05, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x13, 0x14, 0x15, 0x16, 0x86 -> StreamMapping.Skip()
            else -> when (registration) {
                fourcc("HEVC") -> StreamMapping.Read(StreamReader.H265)
                fourcc("drac") -> StreamMapping.Skip("Dirac")
                fourcc("VVC ") -> StreamMapping.Skip("H.266/VVC")
                fourcc("AC-3") -> StreamMapping.Read(StreamReader.AC3)
                fourcc("EAC3") -> StreamMapping.Read(StreamReader.EAC3)
                fourcc("DTS1"), fourcc("DTS2"), fourcc("DTS3") -> StreamMapping.Read(StreamReader.DTS)
                // Any other type: identified from content, as ffmpeg probes streams it has no
                // table entry for (e.g. its own DTS on 0x82 without an HDMV registration).
                else -> StreamMapping.Probe(null)
            }
        }
    }

    private fun privateData(registration: Int?, dvbAudio: StreamReader?, nonAv: Boolean, ac4: Boolean): StreamMapping {
        when (registration) {
            fourcc("AC-3") -> return StreamMapping.Read(StreamReader.AC3)
            fourcc("EAC3") -> return StreamMapping.Read(StreamReader.EAC3)
            fourcc("Opus") -> return StreamMapping.Read(StreamReader.OPUS)
            fourcc("DTS1"), fourcc("DTS2"), fourcc("DTS3") -> return StreamMapping.Read(StreamReader.DTS)
            fourcc("HEVC") -> return StreamMapping.Read(StreamReader.H265)
            fourcc("AV01") -> return StreamMapping.Skip("AV1 in MPEG-TS")
            fourcc("VC-1") -> return StreamMapping.Skip("VC-1")
            fourcc("VVC ") -> return StreamMapping.Skip("H.266/VVC")
            fourcc("drac") -> return StreamMapping.Skip("Dirac")
            fourcc("BSSD") -> return StreamMapping.Skip("SMPTE 302M")
            fourcc("AC-4") -> return StreamMapping.Skip("AC-4")
            fourcc("ID3 "), fourcc("KLVA"), fourcc("VANC") -> return StreamMapping.Skip()
        }
        if (dvbAudio != null) return StreamMapping.Read(dvbAudio)
        if (ac4) return StreamMapping.Skip("AC-4")
        // Teletext, DVB subtitles, VBI and data streams say so in a descriptor; anything else
        // (including streams with descriptors we don't know) is identified from its content.
        return if (nonAv) StreamMapping.Skip() else StreamMapping.Probe(null)
    }
}
