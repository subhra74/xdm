package xdm.core.media.muxer.transmux.io

/**
 * A growable, big-endian byte buffer with a read cursor. Modelled on ExoPlayer/media3's
 * `ParsableByteArray`: it wraps a backing array plus a `limit`, and exposes typed reads that
 * advance an internal `position`. Used everywhere we peel structured headers off raw bytes
 * (PES headers, PSI sections, NAL/codec headers).
 */
class ParsableByteArray {
    var data: ByteArray
        private set
    var position: Int = 0
    var limit: Int = 0
        private set

    constructor(initialCapacity: Int = 64) {
        data = ByteArray(initialCapacity)
        limit = 0
    }

    constructor(bytes: ByteArray) {
        data = bytes
        limit = bytes.size
    }

    constructor(bytes: ByteArray, length: Int) {
        data = bytes
        limit = length
    }

    /** Resets the cursor and limit, reusing the backing array if large enough. */
    fun reset(length: Int) {
        ensureCapacity(length)
        limit = length
        position = 0
    }

    fun reset(bytes: ByteArray, length: Int) {
        data = bytes
        limit = length
        position = 0
    }

    fun bytesLeft(): Int = limit - position

    fun seek(pos: Int) {
        require(pos in 0..limit) { "position out of range: $pos (limit=$limit)" }
        position = pos
    }

    fun skipBytes(n: Int) {
        seek(position + n)
    }

    private fun ensureCapacity(length: Int) {
        if (length > data.size) {
            data = data.copyOf(maxOf(length, data.size * 2))
        }
    }

    fun readUnsignedByte(): Int = data[position++].toInt() and 0xFF

    fun readUnsignedShort(): Int {
        return ((data[position++].toInt() and 0xFF) shl 8) or
            (data[position++].toInt() and 0xFF)
    }

    fun readUnsignedInt24(): Int {
        return ((data[position++].toInt() and 0xFF) shl 16) or
            ((data[position++].toInt() and 0xFF) shl 8) or
            (data[position++].toInt() and 0xFF)
    }

    fun readUnsignedInt(): Long {
        return ((data[position++].toLong() and 0xFF) shl 24) or
            ((data[position++].toLong() and 0xFF) shl 16) or
            ((data[position++].toLong() and 0xFF) shl 8) or
            (data[position++].toLong() and 0xFF)
    }

    fun readInt(): Int = readUnsignedInt().toInt()

    /** Reads `length` bytes into a freshly allocated array. */
    fun readBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        System.arraycopy(data, position, out, 0, length)
        position += length
        return out
    }

    fun readBytes(dest: ByteArray, offset: Int, length: Int) {
        System.arraycopy(data, position, dest, offset, length)
        position += length
    }

    fun peekUnsignedByte(aheadBytes: Int): Int = data[position + aheadBytes].toInt() and 0xFF
}
