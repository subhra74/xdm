package xdm.core.media.muxer.transmux.ts

import xdm.core.media.muxer.transmux.es.ByteArrayBuilder
import xdm.core.media.muxer.transmux.es.ElementaryStreamReader

/**
 * Reassembles one elementary stream's PES packets from TS payload fragments. A PES begins at a
 * packet with payload_unit_start_indicator set and ends at the next one; on each boundary we parse
 * the PES header (PTS/DTS) and hand the payload to the [reader]. 33-bit timestamps are unwrapped to
 * a monotonic 64-bit timeline so the ~26.5h MPEG clock rollover doesn't corrupt durations.
 */
class PesAssembler(val reader: ElementaryStreamReader) {
    private val buffer = ByteArrayBuilder(64 * 1024)
    private var started = false

    private var wrapOffset = 0L
    private var lastRef = -1L

    fun consumePacket(data: ByteArray, start: Int, end: Int, pusi: Boolean) {
        if (pusi) {
            flush()
            buffer.reset()
            started = true
        }
        if (started) buffer.write(data, start, end - start)
    }

    fun flush() {
        if (!started || buffer.size < 9) { return }
        val b = buffer.buffer
        // packet_start_code_prefix 00 00 01
        if (b[0].toInt() != 0 || b[1].toInt() != 0 || (b[2].toInt() and 0xFF) != 1) { return }
        val streamId = b[3].toInt() and 0xFF

        // Streams without a PES header extension.
        if (streamId == 0xBE || streamId == 0xBF) return

        val ptsDtsFlags = (b[7].toInt() and 0xC0) shr 6
        val headerDataLength = b[8].toInt() and 0xFF
        var pts = -1L
        var dts = -1L
        if (ptsDtsFlags == 2 || ptsDtsFlags == 3) {
            pts = readTimestamp(b, 9)
            if (ptsDtsFlags == 3) dts = readTimestamp(b, 14)
        }
        val ref = if (dts >= 0) dts else pts
        if (ref >= 0) {
            if (lastRef >= 0 && lastRef - ref > (1L shl 32)) wrapOffset += (1L shl 33)
            lastRef = ref
            if (pts >= 0) pts += wrapOffset
            if (dts >= 0) dts += wrapOffset
        }

        val payloadOffset = 9 + headerDataLength
        if (payloadOffset >= buffer.size) return
        reader.consume(b, payloadOffset, buffer.size - payloadOffset, pts, if (dts >= 0) dts else pts)
    }

    private fun readTimestamp(b: ByteArray, o: Int): Long {
        return (((b[o].toLong() and 0x0E) shl 29) or
            ((b[o + 1].toLong() and 0xFF) shl 22) or
            ((b[o + 2].toLong() and 0xFE) shl 14) or
            ((b[o + 3].toLong() and 0xFF) shl 7) or
            ((b[o + 4].toLong() and 0xFE) shr 1))
    }
}
