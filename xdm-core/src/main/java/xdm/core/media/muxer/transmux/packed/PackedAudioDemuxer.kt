package xdm.core.media.muxer.transmux.packed

import xdm.core.media.muxer.transmux.es.Ac3Reader
import xdm.core.media.muxer.transmux.es.AdtsReader
import xdm.core.media.muxer.transmux.es.ElementaryStreamReader
import xdm.core.media.muxer.transmux.es.MpegAudioReader
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.media.muxer.transmux.ts.UnsupportedCodecException
import java.io.File

/**
 * Demuxer for HLS "packed audio" segments (RFC 8216 §3.4): raw ADTS AAC, MPEG audio (MP3/MP2),
 * AC-3 or E-AC-3 frames with no container, each segment starting with an ID3v2 tag whose PRIV
 * frame `com.apple.streaming.transportStreamTimestamp` carries the 33-bit MPEG-TS PTS of the first
 * frame. Frames go to the same readers the TS path uses; the ID3 timestamp stands in for the PES
 * PTS, so segments line up on the same 90 kHz timeline as TS video. Segments without the tag
 * simply continue the previous timeline.
 */
class PackedAudioDemuxer(private val sink: SampleSink) {
    private var reader: ElementaryStreamReader? = null
    private var wrapOffset = 0L
    private var lastPts = -1L

    val tracks: List<Track> get() = listOfNotNull(reader?.track)

    /** Parses one packed-audio segment. Segments are small (seconds of audio), so they're read whole. */
    fun parseSegment(path: String) {
        val data = File(path).readBytes()
        var pos = 0
        var pts = -1L
        while (isId3(data, pos)) {
            val tagEnd = minOf(pos + id3TagSize(data, pos), data.size)
            findTimestamp(data, pos + ID3_HEADER_SIZE, tagEnd)?.let { pts = unwrap(it) }
            pos = tagEnd
        }
        if (pos >= data.size) return
        val r = reader ?: createReader(data, pos)?.also { reader = it }
            ?: throw UnsupportedCodecException("Unrecognized packed audio segment: $path")
        r.consume(data, pos, data.size - pos, pts, pts)
    }

    fun finish() {
        reader?.finish()
    }

    /** Picks the reader from the first frame's sync word. */
    private fun createReader(data: ByteArray, pos: Int): ElementaryStreamReader? {
        if (pos + 6 > data.size) return null
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        return when {
            // ADTS: 0xFFF sync + layer bits 00.
            b0 == 0xFF && (b1 and 0xF6) == 0xF0 -> AdtsReader(sink)
            // MPEG audio: 0xFFE sync + non-zero layer bits.
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x06) != 0 -> MpegAudioReader(sink)
            // AC-3 / E-AC-3 share the 0x0B77 syncword; bsid (byte 5, top 5 bits) > 10 means E-AC-3.
            b0 == 0x0B && b1 == 0x77 -> Ac3Reader(sink, eac3 = ((data[pos + 5].toInt() and 0xFF) shr 3) > 10)
            else -> null
        }
    }

    /** Unwraps the 33-bit PTS into a monotonic timeline (same rule as the TS PES assembler). */
    private fun unwrap(pts: Long): Long {
        if (lastPts >= 0 && lastPts - pts > (1L shl 32)) wrapOffset += (1L shl 33)
        lastPts = pts
        return pts + wrapOffset
    }

    companion object {
        private const val ID3_HEADER_SIZE = 10
        private val TIMESTAMP_OWNER = "com.apple.streaming.transportStreamTimestamp\u0000".toByteArray(Charsets.US_ASCII)

        fun isId3(data: ByteArray, pos: Int): Boolean =
            pos + ID3_HEADER_SIZE <= data.size &&
                data[pos].toInt() == 'I'.code && data[pos + 1].toInt() == 'D'.code && data[pos + 2].toInt() == '3'.code

        /** Total ID3v2 tag length: header + syncsafe body size + optional footer. */
        private fun id3TagSize(data: ByteArray, pos: Int): Int {
            var body = 0
            for (k in 6..9) body = (body shl 7) or (data[pos + k].toInt() and 0x7F)
            val footer = if ((data[pos + 5].toInt() and 0x10) != 0) 10 else 0
            return ID3_HEADER_SIZE + body + footer
        }

        /**
         * Finds the transportStreamTimestamp PRIV payload in an ID3 tag body: the owner string is
         * followed by an 8-byte big-endian value whose low 33 bits are the PTS.
         */
        private fun findTimestamp(data: ByteArray, start: Int, end: Int): Long? {
            val owner = TIMESTAMP_OWNER
            var i = start
            while (i + owner.size + 8 <= end) {
                var match = true
                for (k in owner.indices) if (data[i + k] != owner[k]) { match = false; break }
                if (match) {
                    var v = 0L
                    for (k in 0 until 8) v = (v shl 8) or (data[i + owner.size + k].toLong() and 0xFF)
                    return v and 0x1FFFFFFFFL
                }
                i++
            }
            return null
        }
    }
}
