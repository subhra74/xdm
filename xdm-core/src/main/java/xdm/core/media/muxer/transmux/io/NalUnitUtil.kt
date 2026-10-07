package xdm.core.media.muxer.transmux.io

/**
 * Helpers for Annex-B byte streams (H.264/H.265 carried in MPEG-TS use start codes
 * 0x000001 / 0x00000001). MP4 instead stores length-prefixed NAL units, so transmuxing video
 * means: find start codes, then re-emit each NAL with a 4-byte length prefix.
 */
object NalUnitUtil {

    /**
     * Finds the next Annex-B start code (0x000001) in [data] at or after [start], up to [end].
     * Returns the index of the 0x01 byte's position such that the start code occupies
     * [index-3 or index-2 .. index]; specifically returns the offset of the first 0x00 of the
     * start code, or [end] if none found.
     */
    fun findStartCode(data: ByteArray, start: Int, end: Int): Int {
        var i = start
        while (i + 2 < end) {
            if (data[i + 2].toInt() and 0xFF > 1) {
                // The third byte can't be part of 00 00 01, jump ahead.
                i += 3
            } else if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && (data[i + 2].toInt() and 0xFF) == 1) {
                return i
            } else {
                i++
            }
        }
        return end
    }

    /**
     * Removes emulation-prevention bytes (0x03 after 0x0000) from a NAL unit, producing the
     * Raw Byte Sequence Payload (RBSP) needed to parse SPS/PPS with the bit reader.
     */
    fun unescapeRbsp(data: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        var outLen = 0
        var i = offset
        val end = offset + length
        var zeros = 0
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 0x03 && i + 1 < end && (data[i + 1].toInt() and 0xFF) <= 0x03) {
                // Skip the emulation-prevention byte.
                zeros = 0
                i++
                continue
            }
            out[outLen++] = data[i]
            zeros = if (b == 0) zeros + 1 else 0
            i++
        }
        return out.copyOf(outLen)
    }
}
