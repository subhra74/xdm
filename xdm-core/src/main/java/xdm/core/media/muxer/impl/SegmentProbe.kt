// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.impl

import xdm.core.media.muxer.transmux.es.Ac3Reader
import java.io.RandomAccessFile

/**
 * Finds the container of a stream segment and where its media data starts.
 *
 * Most segments start with their container's first byte. Some sites serve segments with other bytes
 * in front: a picture (PNG, GIF, JPEG, BMP, WebP, so the response passes as an image), an ID3 tag, or
 * padding. The media after those bytes is intact. A segment is read in three steps:
 *  1. At the current position, a container is accepted only when a second, independent check
 *     confirms it (a lone sync byte or magic number also occurs inside other data):
 *     - MPEG-TS: the 0x47 sync byte recurs [TS_PACKETS] times at a 188-, 192- (M2TS, the packet
 *       begins 4 bytes earlier) or 204-byte stride, or up to the end of the file;
 *     - MP4 (incl. fragmented MP4 / CMAF): a known top-level box type;
 *     - Matroska / WebM: the EBML magic followed by a well-formed EBML header, or a bare Cluster;
 *     - packed audio: an ID3 tag, or [AUDIO_FRAMES] ADTS / MPEG audio / AC-3 frames in a row whose
 *       lengths land on the next frame's sync word.
 *     An ID3 tag followed by another container is skipped like a picture; followed by audio, it
 *     belongs to the packed audio (it carries the segment's timestamp).
 *  2. Otherwise, when the bytes are a picture, the picture is walked to its exact end (PNG chunks,
 *     GIF blocks, JPEG markers, BMP / RIFF sizes) and step 1 runs again there, so a picture of any
 *     size is skipped, and pictures in a row too.
 *  3. Otherwise the first [SCAN_LIMIT] bytes are searched, with the stricter checks of step 1.
 */
internal object SegmentProbe {
    enum class Container { TS, MP4, MKV, PACKED_AUDIO }

    /** [container] of a segment, whose media starts [offset] bytes into the file. */
    data class Located(val container: Container, val offset: Long)

    /** How far into a segment the search of step 3 looks for the media. */
    const val SCAN_LIMIT = 1024 * 1024

    /** Pictures (or ID3 tags) in a row skipped before giving up on step 2. */
    private const val MAX_WRAPPERS = 8

    private const val M2TS_PACKET = 192
    /** TS packets (the first one included) whose sync bytes must line up. */
    private const val TS_PACKETS = 5
    private val TS_PACKET_SIZES = intArrayOf(188, M2TS_PACKET, 204)
    /** Audio frames in a row that confirm packed audio. */
    private const val AUDIO_FRAMES = 4
    /** ADTS frames confirming packed audio must add up to more than a TS packet, so TS payload isn't taken for it. */
    private const val MIN_ADTS_RUN = 188
    private val MP4_TOP_LEVEL_TYPES = setOf(
        "ftyp", "styp", "moov", "moof", "sidx", "ssix", "free", "skip", "mdat", "emsg", "prft", "uuid", "meta",
    )

    /**
     * Where [path]'s media starts. Null when no container is found; the caller then goes by the file
     * name ([fromName]) or reads the file from byte 0 as MP4 (an MP4 that starts with an unlisted box).
     */
    fun locate(path: String): Located? = RandomAccessFile(path, "r").use { locate(it) }

    fun locate(raf: RandomAccessFile): Located? {
        val p = Peek(raf)
        // Steps 1 and 2: the media at the start, or after pictures / ID3 tags in front of it.
        var pos = 0L
        for (i in 0..MAX_WRAPPERS) {
            if (pos >= p.length) return null
            at(p, pos, strict = pos > 0)?.let { return it }
            pos = wrapperEnd(p, pos) ?: break
        }
        // Step 3: search.
        val end = minOf(p.length, pos + SCAN_LIMIT)
        var k = pos + 1
        while (k < end) {
            found(p, k)?.let { return it }
            k++
        }
        return null
    }

    /**
     * The container a segment's file name suggests (the downloader names a segment after its URL's
     * extension), for when its bytes don't tell. Null when the name doesn't say either.
     */
    fun fromName(path: String): Container? {
        var name = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (name.endsWith(".enc")) name = name.removeSuffix(".enc")
        return when (name.substringAfterLast('.', "")) {
            "ts", "m2ts", "mts", "tsv", "tsa" -> Container.TS
            "mp4", "m4s", "m4v", "m4a", "fmp4", "cmfv", "cmfa", "mov", "m4f", "mp4a", "mp4v" -> Container.MP4
            "webm", "mkv", "mka", "weba" -> Container.MKV
            "aac", "adts", "ac3", "ec3", "eac3", "mp3", "mp2", "mpga" -> Container.PACKED_AUDIO
            else -> null
        }
    }

    // ---- step 1: a container at a given position ----

    /**
     * The container that starts at [pos]. At the start of the file ([strict] false) the MP4 and
     * Matroska checks stay as lenient as they always were; TS and audio always need their second check.
     */
    private fun at(p: Peek, pos: Long, strict: Boolean): Located? {
        if (isEbmlMagic(p, pos) && (isEbmlHeader(p, pos) || !strict)) return Located(Container.MKV, pos)
        if (isBoxType(p, pos + 4)) return Located(Container.MP4, pos)
        if (isCluster(p, pos)) return Located(Container.MKV, pos)
        tsAt(p, pos)?.let { return it }
        if (isId3(p, pos)) {
            // An ID3 tag in front of another container is skipped; in front of audio it is part of it.
            val after = id3End(p, pos)
            if (after < p.length) {
                at(p, after, strict = true)?.let { inner ->
                    return if (inner.container == Container.PACKED_AUDIO) Located(Container.PACKED_AUDIO, pos) else inner
                }
            }
            return Located(Container.PACKED_AUDIO, pos)
        }
        if (audioRun(p, pos)) return Located(Container.PACKED_AUDIO, pos)
        return null
    }

    // ---- step 3: the search ----

    private fun found(p: Peek, k: Long): Located? {
        val b = p.u8(k)
        if (b == 0x1A && isEbmlMagic(p, k) && isEbmlHeader(p, k)) return Located(Container.MKV, k)
        if (b == 0x1F && isCluster(p, k) && clusterHasChild(p, k)) return Located(Container.MKV, k)
        if (b == 0x47) tsAt(p, k)?.let { return it }
        if (isMp4BoxChain(p, k)) return Located(Container.MP4, k)
        if ((b == 0xFF || b == 0x0B) && audioRun(p, k)) return Located(Container.PACKED_AUDIO, k)
        if (b == 'I'.code && isId3(p, k) && audioRun(p, id3End(p, k))) return Located(Container.PACKED_AUDIO, k)
        return null
    }

    // ---- TS ----

    /** TS at [pos] (or M2TS whose sync byte is at [pos] + 4), confirmed by the following packets. */
    private fun tsAt(p: Peek, pos: Long): Located? {
        if (p.u8(pos) == 0x47) {
            for (size in TS_PACKET_SIZES) {
                if (size == M2TS_PACKET) continue
                if (tsStrideHolds(p, pos, size)) return Located(Container.TS, pos)
            }
            // A sync byte 4 bytes into the data: an M2TS packet starting 4 bytes earlier.
            if (pos >= 4 && tsStrideHolds(p, pos, M2TS_PACKET)) return Located(Container.TS, pos - 4)
        }
        if (p.u8(pos + 4) == 0x47 && tsStrideHolds(p, pos + 4, M2TS_PACKET)) return Located(Container.TS, pos)
        return null
    }

    /** The sync byte at [sync] recurs at [size] strides for [TS_PACKETS] packets, or until the file ends. */
    private fun tsStrideHolds(p: Peek, sync: Long, size: Int): Boolean {
        var confirmed = 0
        var at = sync + size
        while (confirmed < TS_PACKETS - 1) {
            if (at >= p.length) return confirmed > 0
            if (p.u8(at) != 0x47) return false
            confirmed++
            at += size
        }
        return true
    }

    // ---- MP4 ----

    /** Every known box type starts with a lowercase letter: checked first, so the search stays cheap. */
    private fun isBoxType(p: Peek, at: Long): Boolean =
        p.u8(at) in 'a'.code..'z'.code && p.ascii4(at)?.let { it in MP4_TOP_LEVEL_TYPES } ?: false

    /** A known box at [k] whose size ends at the file's end or at another known box. */
    private fun isMp4BoxChain(p: Peek, k: Long): Boolean {
        if (!isBoxType(p, k + 4)) return false
        var size = p.u32(k)
        if (size == 1L) {
            size = (p.u32(k + 8) shl 32) or p.u32(k + 12)
            if (size < 16) return false
        } else if (size == 0L) {
            return true // to the end of the file
        } else if (size < 8) {
            // A malformed `free` box with a size smaller than its header, as some writers make.
            if (p.ascii4(k + 4) != "free") return false
            size = 8
        }
        val next = k + size
        if (next == p.length) return true
        if (next + 8 > p.length) return false
        return isBoxType(p, next + 4)
    }

    // ---- Matroska ----

    private fun isEbmlMagic(p: Peek, at: Long) =
        p.u8(at) == 0x1A && p.u8(at + 1) == 0x45 && p.u8(at + 2) == 0xDF && p.u8(at + 3) == 0xA3

    /** The EBML header at [at]: its children (ID + size + data) fill it exactly, and it fits the file. */
    private fun isEbmlHeader(p: Peek, at: Long): Boolean {
        val size = readVint(p, at + 4) ?: return false
        val start = at + 4 + size.second
        val end = start + size.first
        if (size.first <= 0 || size.first > 4096 || end >= p.length) return false
        var q = start
        while (q < end) {
            val idLen = idLength(p.u8(q))
            if (idLen == 0) return false
            val child = readVint(p, q + idLen) ?: return false
            q += idLen + child.second + child.first
        }
        return q == end
    }

    /** A media segment that begins mid-Matroska-Segment starts with a bare Cluster (0x1F43B675). */
    private fun isCluster(p: Peek, at: Long) =
        p.u8(at) == 0x1F && p.u8(at + 1) == 0x43 && p.u8(at + 2) == 0xB6 && p.u8(at + 3) == 0x75

    /** The Cluster at [at] opens with a Timestamp, Position or PrevSize, as muxers write it. */
    private fun clusterHasChild(p: Peek, at: Long): Boolean {
        val size = readVint(p, at + 4) ?: return false
        val child = p.u8(at + 4 + size.second)
        return child == 0xE7 || child == 0xA7 || child == 0xAB
    }

    /** Length of an EBML element ID from its first byte (1–4), or 0 when it isn't one. */
    private fun idLength(first: Int): Int = when {
        first < 0 -> 0
        first and 0x80 != 0 -> 1
        first and 0x40 != 0 -> 2
        first and 0x20 != 0 -> 3
        first and 0x10 != 0 -> 4
        else -> 0
    }

    /** An EBML variable-size integer at [at]: (value, length in bytes); null when malformed. */
    private fun readVint(p: Peek, at: Long): Pair<Long, Int>? {
        val first = p.u8(at)
        if (first <= 0) return null
        var len = 1
        var mask = 0x80
        while (len <= 8 && first and mask == 0) { mask = mask ushr 1; len++ }
        if (len > 8) return null
        var value = (first and (mask - 1)).toLong()
        for (i in 1 until len) {
            val b = p.u8(at + i)
            if (b < 0) return null
            value = (value shl 8) or b.toLong()
        }
        return value to len
    }

    // ---- packed audio ----

    private fun isId3(p: Peek, at: Long): Boolean =
        p.u8(at) == 'I'.code && p.u8(at + 1) == 'D'.code && p.u8(at + 2) == '3'.code &&
            p.u8(at + 3) in 2..4 && (6..9).all { p.u8(at + it) in 0..0x7F }

    /** Just past the ID3v2 tag at [at] (header, syncsafe size, optional footer). */
    private fun id3End(p: Peek, at: Long): Long {
        var body = 0L
        for (k in 6..9) body = (body shl 7) or (p.u8(at + k) and 0x7F).toLong()
        val footer = if (p.u8(at + 5) and 0x10 != 0) 10 else 0
        return at + 10 + body + footer
    }

    /**
     * [AUDIO_FRAMES] frames of one kind in a row from [at], each starting where the previous one's
     * length says, or frames up to the exact end of the file. ADTS frames must also add up to more
     * than [MIN_ADTS_RUN] bytes and keep their sampling frequency; MPEG audio frames keep their
     * version, layer and sampling rate.
     */
    private fun audioRun(p: Peek, at: Long): Boolean {
        val kind = frameKind(p, at)
        if (kind < 0) return false
        val key = frameKey(p, at, kind)
        var pos = at
        var frames = 0
        var bytes = 0L
        while (frames < AUDIO_FRAMES || (kind == ADTS && bytes <= MIN_ADTS_RUN)) {
            if (pos == p.length) return frames > 0
            if (frameKind(p, pos) != kind || frameKey(p, pos, kind) != key) return false
            val len = frameLength(p, pos, kind)
            if (len <= 0) return false
            frames++
            bytes += len
            pos += len
        }
        return true
    }

    private const val ADTS = 0
    private const val MPEG_AUDIO = 1
    private const val AC3 = 2

    private fun frameKind(p: Peek, at: Long): Int {
        val b0 = p.u8(at)
        val b1 = p.u8(at + 1)
        return when {
            b0 == 0xFF && b1 >= 0 && (b1 and 0xF6) == 0xF0 -> ADTS
            b0 == 0xFF && b1 >= 0 && (b1 and 0xE0) == 0xE0 && (b1 and 0x06) != 0 -> MPEG_AUDIO
            b0 == 0x0B && b1 == 0x77 -> AC3
            else -> -1
        }
    }

    /** What must stay the same from frame to frame. */
    private fun frameKey(p: Peek, at: Long, kind: Int): Long = when (kind) {
        ADTS -> ((p.u8(at + 2) shr 2) and 0x0F).toLong()            // sampling frequency index
        MPEG_AUDIO -> p.u32(at) and 0xFFFE0C00L                      // sync, version, layer, rate
        else -> 0L
    }

    /** The frame's length in bytes, or -1 when its header is not a valid one. */
    private fun frameLength(p: Peek, at: Long, kind: Int): Int = when (kind) {
        ADTS -> {
            val freq = (p.u8(at + 2) shr 2) and 0x0F
            val len = ((p.u8(at + 3) and 0x03) shl 11) or (p.u8(at + 4) shl 3) or (p.u8(at + 5) shr 5)
            if (freq > 12 || len < 7) -1 else len
        }
        MPEG_AUDIO -> mpegAudioFrameLength(p.u32(at).toInt())
        else -> ac3FrameLength(p, at)
    }

    private fun mpegAudioFrameLength(h: Int): Int {
        val version = (h ushr 19) and 3       // 0 = MPEG-2.5, 1 = reserved, 2 = MPEG-2, 3 = MPEG-1
        val layerBits = (h ushr 17) and 3     // 1 = III, 2 = II, 3 = I
        val bitrateIndex = (h ushr 12) and 0xF
        val rateIndex = (h ushr 10) and 3
        if (version == 1 || layerBits == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return -1
        val mpeg1 = version == 3
        val layer = 4 - layerBits
        val sampleRate = intArrayOf(44100, 48000, 32000)[rateIndex] / (if (mpeg1) 1 else if (version == 2) 2 else 4)
        val kbps = when {
            mpeg1 && layer == 1 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
            mpeg1 && layer == 2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
            mpeg1 -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
            layer == 1 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
            else -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        }[bitrateIndex] * 1000
        val padding = (h ushr 9) and 1
        return when {
            layer == 1 -> (12 * kbps / sampleRate + padding) * 4
            layer == 2 || mpeg1 -> 144 * kbps / sampleRate + padding
            else -> 72 * kbps / sampleRate + padding
        }
    }

    /** AC-3 (frmsizecod table) or E-AC-3 (frmsiz) frame length, decided by bsid as the reader does. */
    private fun ac3FrameLength(p: Peek, at: Long): Int {
        val bsid = (p.u8(at + 5) shr 3) and 0x1F
        if (bsid > 16) return -1
        if (bsid > 10) return (((p.u8(at + 2) and 0x07) shl 8) or p.u8(at + 3)).let { (it + 1) * 2 }
        val fscod = (p.u8(at + 4) shr 6) and 3
        val frmsizecod = p.u8(at + 4) and 0x3F
        if (fscod >= 3 || frmsizecod >= Ac3Reader.FRAME_SIZE_TAB.size) return -1
        return Ac3Reader.FRAME_SIZE_TAB[frmsizecod][fscod] * 2
    }

    // ---- step 2: pictures in front of the media ----

    /** Just past the picture (or ID3 tag) starting at [at], or null when there is none. */
    private fun wrapperEnd(p: Peek, at: Long): Long? {
        val end = when {
            isId3(p, at) -> id3End(p, at)
            p.u8(at) == 0x89 && p.ascii4(at + 1)?.startsWith("PNG") == true -> pngEnd(p, at)
            p.ascii4(at) == "GIF8" -> gifEnd(p, at)
            p.u8(at) == 0xFF && p.u8(at + 1) == 0xD8 -> jpegEnd(p, at)
            p.u8(at) == 'B'.code && p.u8(at + 1) == 'M'.code -> bmpEnd(p, at)
            p.ascii4(at) == "RIFF" && p.ascii4(at + 8) == "WEBP" -> (p.u32le(at + 4) + 8).let { at + it + (it and 1) }
            else -> null
        } ?: return null
        return end.takeIf { it > at && it <= p.length }
    }

    /** PNG: signature, then chunks (length, type, data, CRC) up to and including IEND. */
    private fun pngEnd(p: Peek, at: Long): Long? {
        if (p.u8(at + 4) != 0x0D || p.u8(at + 5) != 0x0A || p.u8(at + 6) != 0x1A || p.u8(at + 7) != 0x0A) return null
        var pos = at + 8
        while (pos + 12 <= p.length) {
            val len = p.u32(pos)
            if (len > Int.MAX_VALUE) return null
            val type = p.ascii4(pos + 4) ?: return null
            pos += 12 + len
            if (type == "IEND") return pos
        }
        return null
    }

    /** GIF: header, logical screen (+ global colour table), then blocks up to the 0x3B trailer. */
    private fun gifEnd(p: Peek, at: Long): Long? {
        val flags = p.u8(at + 10)
        if (flags < 0) return null
        var pos = at + 13
        if (flags and 0x80 != 0) pos += 3L shl ((flags and 7) + 1)
        while (pos < p.length) {
            when (p.u8(pos)) {
                0x3B -> return pos + 1
                0x21 -> pos = skipSubBlocks(p, pos + 2) ?: return null            // extension
                0x2C -> {                                                         // image
                    val local = p.u8(pos + 9)
                    if (local < 0) return null
                    pos += 10
                    if (local and 0x80 != 0) pos += 3L shl ((local and 7) + 1)
                    pos = skipSubBlocks(p, pos + 1) ?: return null                // after LZW code size
                }
                else -> return null
            }
        }
        return null
    }

    private fun skipSubBlocks(p: Peek, from: Long): Long? {
        var pos = from
        while (true) {
            val n = p.u8(pos)
            if (n < 0) return null
            pos += 1 + n
            if (n == 0) return pos
        }
    }

    /** JPEG: markers and their segments up to EOI; entropy-coded data after each SOS is scanned. */
    private fun jpegEnd(p: Peek, at: Long): Long? {
        var pos = at + 2
        while (pos < p.length) {
            if (p.u8(pos) != 0xFF) return null
            var marker = p.u8(pos + 1)
            while (marker == 0xFF) { pos++; marker = p.u8(pos + 1) }               // fill bytes
            pos += 2
            when {
                marker < 0 -> return null
                marker == 0xD9 -> return pos                                      // EOI
                marker in 0xD0..0xD7 || marker == 0x01 -> {}                      // no segment
                else -> {
                    val len = (p.u8(pos) shl 8) or p.u8(pos + 1)
                    if (len < 2) return null
                    pos += len
                    if (marker == 0xDA) {                                         // SOS: skip the scan data
                        while (pos < p.length) {
                            if (p.u8(pos) == 0xFF) {
                                val next = p.u8(pos + 1)
                                if (next != 0x00 && next !in 0xD0..0xD7) break    // a real marker
                                pos += 2
                            } else {
                                pos++
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    /** BMP: the file size in its header, checked against a known DIB header size. */
    private fun bmpEnd(p: Peek, at: Long): Long? {
        val size = p.u32le(at + 2)
        val dib = p.u32le(at + 14)
        if (size < 26 || dib !in setOf(12L, 40L, 52L, 56L, 64L, 108L, 124L)) return null
        return at + size
    }

    /**
     * Random access to a file through a small block cache: the checks above read scattered bytes,
     * and the search reads up to [SCAN_LIMIT] bytes one at a time.
     */
    private class Peek(private val raf: RandomAccessFile) {
        val length: Long = raf.length()
        private val block = ByteArray(BLOCK)
        private var blockStart = -1L
        private var blockLen = 0

        /** The byte at [pos] (0–255), or -1 past the end of the file. */
        fun u8(pos: Long): Int {
            if (pos < 0 || pos >= length) return -1
            if (pos < blockStart || pos >= blockStart + blockLen) {
                blockStart = pos
                raf.seek(pos)
                blockLen = 0
                while (blockLen < BLOCK) {
                    val n = raf.read(block, blockLen, BLOCK - blockLen)
                    if (n <= 0) break
                    blockLen += n
                }
                if (blockLen == 0) return -1
            }
            return block[(pos - blockStart).toInt()].toInt() and 0xFF
        }

        /** Big-endian 32 bits at [pos] (bytes past the end read as 0). */
        fun u32(pos: Long): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or u8(pos + i).coerceAtLeast(0).toLong()
            return v
        }

        fun u32le(pos: Long): Long {
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or u8(pos + i).coerceAtLeast(0).toLong()
            return v
        }

        /** Four bytes at [pos] as ASCII, or null when they run past the end. */
        fun ascii4(pos: Long): String? {
            if (pos < 0 || pos + 4 > length) return null
            val c = CharArray(4) { u8(pos + it).toChar() }
            return String(c)
        }

        private companion object {
            const val BLOCK = 64 * 1024
        }
    }
}
