package xdm.core.media.muxer.transmux.mp4

/**
 * Tiny big-endian writer for building ISO-BMFF boxes in memory. `box("moov") { ... }` writes a
 * 4-byte size placeholder + type, runs the body, then back-patches the size in place. Backed by a
 * directly-indexable growable array so size patching is O(1), keeping large `moov` builds linear.
 */
class BoxBuf {
    private var buf = ByteArray(64 * 1024)
    private var len = 0

    private fun ensure(extra: Int) {
        if (len + extra > buf.size) {
            var cap = buf.size * 2
            while (cap < len + extra) cap *= 2
            buf = buf.copyOf(cap)
        }
    }

    fun u8(v: Int) { ensure(1); buf[len++] = v.toByte() }
    fun u16(v: Int) { ensure(2); buf[len++] = (v ushr 8).toByte(); buf[len++] = v.toByte() }
    fun u24(v: Int) { ensure(3); buf[len++] = (v ushr 16).toByte(); buf[len++] = (v ushr 8).toByte(); buf[len++] = v.toByte() }
    fun u32(v: Long) {
        ensure(4)
        buf[len++] = (v ushr 24).toByte(); buf[len++] = (v ushr 16).toByte()
        buf[len++] = (v ushr 8).toByte(); buf[len++] = v.toByte()
    }
    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long) { ensure(8); for (s in 56 downTo 0 step 8) buf[len++] = (v ushr s).toByte() }
    fun bytes(b: ByteArray) { ensure(b.size); System.arraycopy(b, 0, buf, len, b.size); len += b.size }
    fun fourcc(s: String) { ensure(s.length); for (c in s) buf[len++] = (c.code and 0xFF).toByte() }

    /** Writes a full-box version+flags word. */
    fun fullBoxHeader(version: Int, flags: Int) { u8(version); u24(flags) }

    inline fun box(type: String, body: BoxBuf.() -> Unit) {
        val start = mark()
        u32(0)          // size placeholder
        fourcc(type)
        body()
        patchSize(start)
    }

    fun mark(): Int = len

    fun patchSize(start: Int) {
        val size = len - start
        buf[start] = (size ushr 24).toByte()
        buf[start + 1] = (size ushr 16).toByte()
        buf[start + 2] = (size ushr 8).toByte()
        buf[start + 3] = size.toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)
    fun size(): Int = len
}
