// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.media

/**
 * Reads the head of a single-file fragmented MP4 (one track: `ftyp` + `moov` + `sidx`, then `moof`/`mdat`
 * pairs), the shape Meta's and Bilibili's CDNs serve each audio or video track in. Only box headers and
 * the few fields below are read; nothing is decoded.
 *
 * The `sidx` is what lets such a file be fetched in parallel, resumable pieces: [segmentRanges] cuts it
 * at subsegment boundaries, so every piece holds whole `moof`/`mdat` pairs, which is what the transmuxer
 * needs (it parses each piece on its own).
 */
object Mp4InitProbe {

    /** One `sidx` reference: [size] bytes of media covering [duration] ticks of the `sidx` timescale. */
    data class SidxRef(val size: Long, val duration: Long)

    data class Sidx(
        val timescale: Long,
        /** Absolute offset of the first referenced byte (the first `moof`). */
        val firstOffset: Long,
        val refs: List<SidxRef>,
    ) {
        val durationSeconds: Double get() = if (timescale > 0) refs.sumOf { it.duration } / timescale.toDouble() else 0.0
        val endOffset: Long get() = firstOffset + refs.sumOf { it.size }
    }

    data class Info(
        /** `vide` or `soun` (the `hdlr` handler type), or null if the `moov` had no track. */
        val handler: String?,
        /** Four-character code of the first sample entry, e.g. `avc1`, `vp09`, `av01`, `mp4a`. */
        val codec: String?,
        val width: Int,
        val height: Int,
        /** From the `sidx` when there is one; `mvhd`/`mdhd` are often 0 in these files. */
        val durationSeconds: Double,
        val sidx: Sidx?,
        /**
         * Bytes needed to read everything above when [bytes] stopped short of it (a `moov` or `sidx` cut
         * off), else 0. Probe again with at least this many bytes.
         */
        val needBytes: Long,
    ) {
        val isVideo: Boolean get() = handler == "vide"
        val isAudio: Boolean get() = handler == "soun"
    }

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

    /** Parses the start of a file. Returns null if [bytes] does not begin with an MP4 box structure. */
    fun parse(bytes: ByteArray): Info? {
        val st = State()
        var pos = 0L
        val len = bytes.size.toLong()
        var sawMoov = false
        var needBytes = 0L
        while (pos + 8 <= len) {
            val (size, type, header) = boxHeader(bytes, pos, len) ?: break
            if (pos == 0L && type != "ftyp" && type != "styp" && type != "moov") return null
            val end = pos + size
            when (type) {
                "moov" -> {
                    sawMoov = true
                    if (end > len) { needBytes = end; break }
                    walk(bytes, pos + header, end, st)
                }
                "sidx" -> {
                    if (end > len) { needBytes = end; break }
                    st.sidx = parseSidx(bytes, pos + header, end)
                }
                "moof", "mdat" -> break
            }
            pos = end
        }
        if (!sawMoov && needBytes == 0L) return null
        val duration = st.sidx?.durationSeconds?.takeIf { it > 0 }
            ?: st.mdhdSeconds.takeIf { it > 0 }
            ?: st.mvhdSeconds
        return Info(st.handler, st.codec, st.width, st.height, duration, st.sidx, needBytes)
    }

    /**
     * Byte ranges as (offset, length) that cover a file of [totalSize] bytes: the head (`ftyp`/`moov`/
     * `sidx`) first, then the media cut at `sidx` subsegment boundaries, adjacent subsegments merged until a
     * piece is at least [minPiece] bytes. Null when there is no usable `sidx`: fetch the file whole.
     */
    fun segmentRanges(info: Info, totalSize: Long, minPiece: Long = 4L * 1024 * 1024): List<Pair<Long, Long>>? {
        val sidx = info.sidx ?: return null
        if (sidx.refs.isEmpty() || sidx.firstOffset <= 0 || sidx.endOffset > totalSize) return null
        val ranges = ArrayList<Pair<Long, Long>>()
        ranges.add(0L to sidx.firstOffset)
        var start = sidx.firstOffset
        var pieceLen = 0L
        for (ref in sidx.refs) {
            pieceLen += ref.size
            if (pieceLen >= minPiece) {
                ranges.add(start to pieceLen)
                start += pieceLen
                pieceLen = 0
            }
        }
        // Whatever follows the last reference (trailing boxes such as `mfra`) goes with the last piece.
        pieceLen += totalSize - sidx.endOffset
        if (pieceLen > 0) ranges.add(start to pieceLen)
        return ranges
    }

    private class State {
        var handler: String? = null
        var codec: String? = null
        var width = 0
        var height = 0
        var mvhdSeconds = 0.0
        var mdhdSeconds = 0.0
        var sidx: Sidx? = null
    }

    private data class BoxHeader(val size: Long, val type: String, val headerLen: Int)

    private fun boxHeader(b: ByteArray, pos: Long, end: Long): BoxHeader? {
        if (pos + 8 > b.size) return null
        var size = u32(b, pos)
        val type = fourcc(b, pos + 4)
        var header = 8
        if (size == 1L) {
            if (pos + 16 > b.size) return null
            size = u64(b, pos + 8)
            header = 16
        } else if (size == 0L) {
            size = end - pos
        }
        if (size < header) return null
        return BoxHeader(size, type, header)
    }

    private fun walk(b: ByteArray, from: Long, end: Long, st: State) {
        var pos = from
        while (pos + 8 <= end) {
            val (size, type, header) = boxHeader(b, pos, end) ?: return
            val boxEnd = pos + size
            if (boxEnd > end) return
            val body = pos + header
            when (type) {
                in CONTAINERS -> walk(b, body, boxEnd, st)
                "mvhd" -> st.mvhdSeconds = fullBoxDuration(b, body)
                "mdhd" -> if (st.mdhdSeconds == 0.0) st.mdhdSeconds = fullBoxDuration(b, body)
                "hdlr" -> if (st.handler == null && body + 12 <= boxEnd) st.handler = fourcc(b, body + 8)
                "tkhd" -> if (st.width == 0 && boxEnd - 8 >= body) {
                    // Width and height are the last two 16.16 fixed-point fields, in either version.
                    st.width = (u32(b, boxEnd - 8) shr 16).toInt()
                    st.height = (u32(b, boxEnd - 4) shr 16).toInt()
                }
                "stsd" -> if (st.codec == null && body + 16 <= boxEnd && u32(b, body + 4) > 0) {
                    st.codec = fourcc(b, body + 12)
                }
            }
            pos = boxEnd
        }
    }

    /** Duration in seconds of an `mvhd`/`mdhd` whose body starts at [body]. */
    private fun fullBoxDuration(b: ByteArray, body: Long): Double {
        val version = b[body.toInt()].toInt()
        val (timescale, duration) = if (version == 1) {
            u32(b, body + 20) to u64(b, body + 24)
        } else {
            u32(b, body + 12) to u32(b, body + 16)
        }
        return if (timescale > 0) duration / timescale.toDouble() else 0.0
    }

    private fun parseSidx(b: ByteArray, body: Long, end: Long): Sidx? {
        if (body + 12 > end) return null
        val version = b[body.toInt()].toInt()
        val timescale = u32(b, body + 8)
        var p = body + 12
        val firstOffset: Long
        if (version == 0) {
            p += 4 // earliest_presentation_time
            firstOffset = u32(b, p); p += 4
        } else {
            p += 8
            firstOffset = u64(b, p); p += 8
        }
        // 16 reserved bits, then reference_count.
        val count = (u32(b, p) and 0xFFFF).toInt()
        p += 4
        if (p + count * 12L > end) return null
        val refs = ArrayList<SidxRef>(count)
        repeat(count) {
            val sizeWord = u32(b, p)
            if (sizeWord and 0x80000000L != 0L) return null // a reference to another sidx: not supported
            refs.add(SidxRef(sizeWord and 0x7FFFFFFFL, u32(b, p + 4)))
            p += 12
        }
        // first_offset counts from the first byte after the sidx box.
        return Sidx(timescale, end + firstOffset, refs)
    }

    private fun u32(b: ByteArray, at: Long): Long {
        val i = at.toInt()
        return ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
    }

    private fun u64(b: ByteArray, at: Long): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    private fun fourcc(b: ByteArray, at: Long): String {
        val i = at.toInt()
        return String(CharArray(4) { (b[i + it].toInt() and 0xFF).toChar() })
    }
}
