package xdm.core.media.muxer.transmux.sample

/**
 * Accumulated per-track state produced by demuxing: the codec, its decoder configuration
 * (the bytes that go into avcC/hvcC/esds/...), display dimensions / audio params, the chosen
 * MP4 timescale, and the list of staged [Sample]s. The MP4 writer turns this into a `trak`.
 */
class Track(val codec: Codec) {
    val samples = ArrayList<Sample>()

    /** MP4 media timescale. Video: 90000 (TS clock). Audio: sampling rate. */
    var timescale: Int = 90000

    // --- Video ---
    var width: Int = 0
    var height: Int = 0

    // --- Audio ---
    var sampleRate: Int = 0
    var channelCount: Int = 0

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
     * Matroska/WebM codec identity carried verbatim from a Matroska input (e.g. `V_VP9`, `A_OPUS`).
     * When set, the MKV writer emits it directly and uses [decoderConfigRecord] as the
     * `CodecPrivate` (used by the Matroska demux path, and the natural output form for MKV).
     */
    var matroskaCodecId: String? = null

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
            else -> true
        }
    }

    fun durationTicks(): Long = samples.sumOf { it.durationTicks }
}
