// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.ts

import xdm.core.media.muxer.transmux.es.ByteArrayBuilder
import xdm.core.media.muxer.transmux.es.ElementaryStreamReader

/**
 * Reassembles one elementary stream's PES packets from TS payload fragments. A PES begins at a
 * packet with payload_unit_start_indicator set and ends at the next one; on each boundary we parse
 * the PES header (PTS/DTS) and hand the payload to the [reader]. 33-bit timestamps go through the
 * demuxer's [clock], shared by all its streams, so the ~26.5h MPEG clock rollover doesn't corrupt durations.
 */
class PesAssembler(reader: ElementaryStreamReader, private val clock: TimestampUnwrapper) {
    /** Replaced once when a stream identified by its content (see [TsDemuxer]) gets its real reader. */
    var reader: ElementaryStreamReader = reader
    private val buffer = ByteArrayBuilder(64 * 1024)
    private var started = false

    /** stream_id of the PES last handed to the reader (0xE0-0xEF video, 0xC0-0xDF audio, 0xBD private). */
    var streamId = -1
        private set

    /** Bytes of PES data this stream carried: a stream with data but no frames out was not understood. */
    var bytesSeen = 0L
        private set

    fun consumePacket(data: ByteArray, start: Int, end: Int, pusi: Boolean) {
        if (pusi) {
            flush()
            buffer.reset()
            started = true
        }
        if (started) {
            buffer.write(data, start, end - start); bytesSeen += end - start
            // A bounded PES (PES_packet_length > 0) is handed on once complete, as ffmpeg's mpegts_push_data
            // emits it; the rest until the next start is not payload. Which PES is complete first decides the
            // wrap reference (TimestampUnwrapper), as ffmpeg's first packet out does.
            if (buffer.size >= 6) {
                val b = buffer.buffer
                val packetLength = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
                if (packetLength > 0 && buffer.size >= 6 + packetLength) endPes()
            }
        }
    }

    /**
     * Hands the PES being collected to the reader now (the data before a discontinuity is complete) and
     * waits for the next PES start: a continuation without one belongs to nothing that follows.
     */
    fun endPes() {
        flush()
        buffer.reset()
        started = false
    }

    fun flush() {
        if (!started || buffer.size < 9) { return }
        val b = buffer.buffer
        // packet_start_code_prefix 00 00 01
        if (b[0].toInt() != 0 || b[1].toInt() != 0 || (b[2].toInt() and 0xFF) != 1) { return }
        val streamId = b[3].toInt() and 0xFF
        this.streamId = streamId

        // Stream ids without a PES header extension (program_stream_map, padding, private_stream_2, ECM,
        // EMM, DSMCC, H.222.1 type E, directory): their payload starts at byte 6, and none is audio or video.
        if (streamId in NO_HEADER_EXTENSION) return

        val ptsDtsFlags = (b[7].toInt() and 0xC0) shr 6
        val headerDataLength = b[8].toInt() and 0xFF
        var pts = -1L
        var dts = -1L
        if (ptsDtsFlags == 2 || ptsDtsFlags == 3) {
            pts = readTimestamp(b, 9)
            if (ptsDtsFlags == 3) dts = readTimestamp(b, 14)
        }
        // DTS first: ffmpeg takes the wrap reference from a packet's DTS, else its PTS.
        dts = clock.unwrap(dts)
        pts = clock.unwrap(pts)

        // A bounded PES (PES_packet_length > 0) ends there, as in ffmpeg: anything after it is not payload.
        val packetLength = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        val end = if (packetLength > 0) minOf(buffer.size, 6 + packetLength) else buffer.size
        val payloadOffset = 9 + headerDataLength
        if (payloadOffset >= end) return
        // dts -1: the PES carried only a PTS (DTS = PTS by the spec; the video readers infer it when
        // frames are reordered, see DtsInference).
        reader.consume(b, payloadOffset, end - payloadOffset, pts, dts)
    }

    private fun readTimestamp(b: ByteArray, o: Int): Long {
        return (((b[o].toLong() and 0x0E) shl 29) or
            ((b[o + 1].toLong() and 0xFF) shl 22) or
            ((b[o + 2].toLong() and 0xFE) shl 14) or
            ((b[o + 3].toLong() and 0xFF) shl 7) or
            ((b[o + 4].toLong() and 0xFE) shr 1))
    }

    private companion object {
        val NO_HEADER_EXTENSION = setOf(0xBC, 0xBE, 0xBF, 0xF0, 0xF1, 0xF2, 0xF8, 0xFF)
    }
}
