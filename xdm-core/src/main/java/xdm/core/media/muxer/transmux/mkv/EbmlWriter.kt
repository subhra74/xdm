package xdm.core.media.muxer.transmux.mkv

/**
 * EBML element IDs (stored form, i.e. including their length-descriptor bits) for the Matroska/WebM
 * subset the writer emits. See the Matroska spec: https://www.matroska.org/technical/elements.html
 */
object Ebml {
    // EBML header
    const val EBML = 0x1A45DFA3L
    const val EBML_VERSION = 0x4286L
    const val EBML_READ_VERSION = 0x42F7L
    const val EBML_MAX_ID_LENGTH = 0x42F2L
    const val EBML_MAX_SIZE_LENGTH = 0x42F3L
    const val DOC_TYPE = 0x4282L
    const val DOC_TYPE_VERSION = 0x4287L
    const val DOC_TYPE_READ_VERSION = 0x4285L

    // Segment + top-level children
    const val SEGMENT = 0x18538067L
    const val SEEK_HEAD = 0x114D9B74L
    const val INFO = 0x1549A966L
    const val TIMECODE_SCALE = 0x2AD7B1L
    const val DURATION = 0x4489L
    const val MUXING_APP = 0x4D80L
    const val WRITING_APP = 0x5741L

    // Tracks
    const val TRACKS = 0x1654AE6BL
    const val TRACK_ENTRY = 0xAEL
    const val TRACK_NUMBER = 0xD7L
    const val TRACK_UID = 0x73C5L
    const val TRACK_TYPE = 0x83L
    const val FLAG_LACING = 0x9CL
    const val DEFAULT_DURATION = 0x23E383L
    const val LANGUAGE = 0x22B59CL
    const val CODEC_ID = 0x86L
    const val CODEC_PRIVATE = 0x63A2L
    const val VIDEO = 0xE0L
    const val PIXEL_WIDTH = 0xB0L
    const val PIXEL_HEIGHT = 0xBAL
    const val AUDIO = 0xE1L
    const val SAMPLING_FREQUENCY = 0xB5L
    const val CHANNELS = 0x9FL
    const val BIT_DEPTH = 0x6264L

    // Clusters
    const val CLUSTER = 0x1F43B675L
    const val TIMECODE = 0xE7L
    const val SIMPLE_BLOCK = 0xA3L
    const val BLOCK_GROUP = 0xA0L
    const val BLOCK = 0xA1L
    const val REFERENCE_BLOCK = 0xFBL

    // Other level-1 elements (skipped on read, used as unknown-size cluster terminators)
    const val CUES = 0x1C53BB6BL
    const val TAGS = 0x1254C367L

    const val TRACK_TYPE_VIDEO = 1L
    const val TRACK_TYPE_AUDIO = 2L

    /** The stored bytes of an element [id] (top marker bit already baked into the constant). */
    fun idBytes(id: Long): ByteArray {
        val n = when {
            id <= 0xFFL -> 1
            id <= 0xFFFFL -> 2
            id <= 0xFFFFFFL -> 3
            else -> 4
        }
        return ByteArray(n) { i -> (id ushr (8 * (n - 1 - i))).toByte() }
    }

    /** Minimal-length EBML variable-size integer encoding of a data size [value]. */
    fun sizeBytes(value: Long): ByteArray {
        var length = 1
        // The all-ones value of a given width is reserved (unknown size), hence the "- 1".
        while (length < 8 && value >= (1L shl (7 * length)) - 1) length++
        val out = ByteArray(length)
        for (i in 0 until length) out[length - 1 - i] = (value ushr (8 * i)).toByte()
        out[0] = (out[0].toInt() or (0x80 ushr (length - 1))).toByte()
        return out
    }

    /** Fixed 8-byte size vint, used as a placeholder that is back-patched once the length is known. */
    fun reservedSizeBytes(value: Long): ByteArray {
        val out = ByteArray(8)
        out[0] = 0x01
        for (i in 0 until 7) out[7 - i] = (value ushr (8 * i)).toByte()
        return out
    }

    /** Minimal big-endian bytes of an unsigned integer (at least one byte). */
    fun uintBytes(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        var n = 8
        while (n > 1 && (value ushr (8 * (n - 1))) == 0L) n--
        return ByteArray(n) { i -> (value ushr (8 * (n - 1 - i))).toByte() }
    }
}

/**
 * Tiny in-memory EBML builder for the small, fully-known header elements (EBML header, Info,
 * Tracks). Master elements are built into a child buffer so their size vint can be written exactly.
 * The large streaming elements (Segment, Cluster) are written directly to the output file by
 * [MkvWriter] instead, to keep memory bounded.
 */
class EbmlBuf {
    private var buf = ByteArray(4096)
    private var len = 0

    private fun ensure(extra: Int) {
        if (len + extra > buf.size) {
            var cap = buf.size * 2
            while (cap < len + extra) cap *= 2
            buf = buf.copyOf(cap)
        }
    }

    fun raw(b: ByteArray) { ensure(b.size); System.arraycopy(b, 0, buf, len, b.size); len += b.size }

    private fun header(id: Long, size: Long) { raw(Ebml.idBytes(id)); raw(Ebml.sizeBytes(size)) }

    fun uint(id: Long, value: Long) { val v = Ebml.uintBytes(value); header(id, v.size.toLong()); raw(v) }

    fun float(id: Long, value: Double) {
        header(id, 8)
        val bits = java.lang.Double.doubleToLongBits(value)
        raw(ByteArray(8) { i -> (bits ushr (8 * (7 - i))).toByte() })
    }

    fun str(id: Long, value: String) { val b = value.toByteArray(Charsets.UTF_8); header(id, b.size.toLong()); raw(b) }

    fun bin(id: Long, value: ByteArray) { header(id, value.size.toLong()); raw(value) }

    inline fun master(id: Long, body: EbmlBuf.() -> Unit) {
        val child = EbmlBuf()
        child.body()
        val payload = child.toByteArray()
        rawHeaderAndPayload(id, payload)
    }

    /** Used by [master]; kept public so the inline body can reach it. */
    fun rawHeaderAndPayload(id: Long, payload: ByteArray) {
        header(id, payload.size.toLong())
        raw(payload)
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)
}
