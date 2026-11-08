// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.sample

/**
 * Accumulated per-track state produced by demuxing: the codec, its decoder configuration
 * (the bytes that go into avcC/hvcC/esds/...), display dimensions / audio params, the chosen
 * MP4 timescale, and the list of staged [Sample]s. The MP4 writer turns this into a `trak`.
 */
class Track(val codec: Codec) {
    val samples = SampleTable()

    /** MP4 media timescale. Video: 90000 (TS clock). Audio: sampling rate. */
    var timescale: Int = 90000

    // --- Video ---
    var width: Int = 0
    var height: Int = 0

    /** Sample (pixel) aspect ratio from the bitstream; 0 when unknown. Written as pasp, and scales tkhd. */
    var sarNum: Int = 0
    var sarDen: Int = 0

    /** Colour description (ISO/IEC 23091-2 code points) from the bitstream; -1 when not signalled. */
    var colourPrimaries: Int = -1
    var transferCharacteristics: Int = -1
    var matrixCoefficients: Int = -1
    var fullRange: Boolean = false

    /** H.264/H.265 sequence properties for avcC/hvcC. */
    var chromaFormat: Int = 1
    var bitDepthLuma: Int = 8
    var bitDepthChroma: Int = 8
    var temporalLayers: Int = 1
    var temporalIdNested: Boolean = false

    // --- Audio ---
    var sampleRate: Int = 0
    var channelCount: Int = 0
    /** MPEG audio layer (1, 2 or 3) for [Codec.MP3] tracks, which carry all MPEG-1/2 audio layers. */
    var mpegAudioLayer: Int = 3

    /**
     * Codec-specific decoder config:
     *  - H264: ordered SPS/PPS NAL units (without start codes), used to build avcC.
     *  - H265: VPS/SPS/PPS NAL units, used to build hvcC.
     *  - AAC : the AudioSpecificConfig bytes for esds.
     *  - AC3/EAC3: the raw config used to synthesize dac3/dec3.
     */
    val parameterSets = ArrayList<ByteArray>()
    var audioSpecificConfig: ByteArray? = null

    /**
     * Ready-to-embed codec box payload (everything after the box size+type):
     *  - H264 -> avcC payload, H265 -> hvcC payload
     *  - AC3 -> dac3 payload, EAC3 -> dec3 payload
     *  - OPUS -> an `OpusHead` (little-endian, as in Ogg/Matroska); the MP4 writer converts it to dOps
     * AAC instead carries its config in [audioSpecificConfig] (wrapped in esds by the writer).
     */
    var decoderConfigRecord: ByteArray? = null

    /** nalLengthSize for avcC/hvcC (we always emit 4). */
    val nalLengthSize = 4

    /**
     * A complete MP4 sample-entry box (e.g. `avc1`/`mp4a`...) copied verbatim from an MP4/fMP4
     * input's stsd. When set, the writer embeds it directly instead of rebuilding a sample entry
     * from [parameterSets]/[audioSpecificConfig] (used by the MP4 demux path).
     */
    var sampleEntryBox: ByteArray? = null

    /**
     * Further sample entries (stsd index 2, 3, ...) when the codec config changes mid-stream: a later
     * init segment (an HLS `EXT-X-MAP` after a discontinuity), or an elementary stream whose audio
     * config changes (e.g. 48 to 44.1 kHz at an ad break; the reader then also freezes the first
     * config into [sampleEntryBox]). Samples point at theirs via [Sample.sampleDescriptionIndex].
     */
    val additionalSampleEntries = ArrayList<ByteArray>()

    /**
     * Matroska/WebM codec identity carried verbatim from a Matroska input (e.g. `V_VP9`, `A_OPUS`).
     * When set, the MKV writer emits it directly and uses [decoderConfigRecord] as the
     * `CodecPrivate` (used by the Matroska demux path, and the natural output form for MKV).
     */
    var matroskaCodecId: String? = null

    /** MPEG-4 Systems objectTypeIndication for esds-described codecs (MPEG-1/2 video). */
    var objectTypeIndication: Int = 0

    /** Audio bit depth (Matroska `BitDepth`), when known — used for raw/PCM tracks. */
    var audioBitDepth: Int = 0

    fun isReady(): Boolean {
        if (samples.isEmpty()) return false
        if (matroskaCodecId != null) return true
        if (sampleEntryBox != null) return true
        return when (codec) {
            Codec.H264, Codec.H265 -> width > 0 && height > 0 && parameterSets.isNotEmpty()
            Codec.AAC -> audioSpecificConfig != null && sampleRate > 0
            Codec.AC3, Codec.EAC3, Codec.MP3 -> sampleRate > 0
            Codec.OPUS -> decoderConfigRecord != null
            Codec.MPEG_VIDEO, Codec.MPEG4_VISUAL -> width > 0 && height > 0 && decoderConfigRecord != null
            Codec.DTS -> sampleRate > 0
            else -> true
        }
    }

    fun durationTicks(): Long { var sum = 0L; for (i in 0 until samples.size) sum += samples.duration(i); return sum }

    /** 1-based stsd index of [entry], registering it as a further sample entry if unseen. */
    fun descriptionIndexFor(entry: ByteArray): Int {
        if (sampleEntryBox?.contentEquals(entry) == true) return 1
        val i = additionalSampleEntries.indexOfFirst { it.contentEquals(entry) }
        if (i >= 0) return i + 2
        additionalSampleEntries.add(entry)
        return additionalSampleEntries.size + 1
    }
}
