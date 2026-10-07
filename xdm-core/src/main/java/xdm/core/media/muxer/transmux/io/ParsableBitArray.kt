package xdm.core.media.muxer.transmux.io

/**
 * Big-endian bit reader over a byte array. Needed for codec headers that are not byte aligned:
 * H.264/HEVC SPS (Exp-Golomb coded), ADTS/AC-3 frame headers, etc. Mirrors media3's
 * `ParsableBitArray` plus unsigned/signed Exp-Golomb helpers.
 */
class ParsableBitArray(
    private var data: ByteArray = ByteArray(0),
    private var byteLimit: Int = data.size
) {
    private var byteOffset: Int = 0
    private var bitOffset: Int = 0

    fun reset(bytes: ByteArray, limit: Int = bytes.size) {
        data = bytes
        byteOffset = 0
        bitOffset = 0
        byteLimit = limit
    }

    fun bitsLeft(): Int = (byteLimit - byteOffset) * 8 - bitOffset

    fun skipBits(numBits: Int) {
        val total = bitOffset + numBits
        byteOffset += total ushr 3
        bitOffset = total and 0x7
    }

    fun readBit(): Boolean = readBits(1) == 1

    /** Reads up to 32 bits, MSB first, returning them right-aligned. */
    fun readBits(numBits: Int): Int {
        if (numBits == 0) return 0
        var result = 0
        var remaining = numBits
        // First, the (possibly partial) current byte.
        var bitsAvailable = 8 - bitOffset
        var b = data[byteOffset].toInt() and 0xFF
        while (remaining > 0) {
            if (bitsAvailable == 0) {
                byteOffset++
                b = data[byteOffset].toInt() and 0xFF
                bitsAvailable = 8
            }
            val take = minOf(remaining, bitsAvailable)
            val shift = bitsAvailable - take
            val mask = (1 shl take) - 1
            result = (result shl take) or ((b ushr shift) and mask)
            bitsAvailable -= take
            remaining -= take
        }
        bitOffset = 8 - bitsAvailable
        if (bitOffset == 8) {
            bitOffset = 0
            byteOffset++
        }
        return result
    }

    /** Reads a 33-bit value as Long (used for nothing here but handy for timestamps). */
    fun readBitsLong(numBits: Int): Long {
        var result = 0L
        var remaining = numBits
        while (remaining > 24) {
            result = (result shl 24) or (readBits(24).toLong() and 0xFFFFFF)
            remaining -= 24
        }
        result = (result shl remaining) or (readBits(remaining).toLong() and ((1L shl remaining) - 1))
        return result
    }

    /** Unsigned Exp-Golomb (ue(v)). */
    fun readUe(): Int {
        var leadingZeros = 0
        while (!readBit()) leadingZeros++
        if (leadingZeros == 0) return 0
        return (1 shl leadingZeros) - 1 + readBits(leadingZeros)
    }

    /** Signed Exp-Golomb (se(v)). */
    fun readSe(): Int {
        val codeNum = readUe()
        val sign = if (codeNum and 0x1 == 1) 1 else -1
        return sign * ((codeNum + 1) / 2)
    }

    fun canReadBits(numBits: Int): Boolean = bitsLeft() >= numBits
}
