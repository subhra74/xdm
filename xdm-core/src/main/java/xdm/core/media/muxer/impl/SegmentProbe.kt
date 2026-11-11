// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.impl

import xdm.core.media.muxer.transmux.packed.PackedAudioDemuxer
import java.io.RandomAccessFile

/**
 * Finds the container of a stream segment and where its media data starts.
 *
 * Most segments start with their container's first byte. Some sites serve segments with other bytes in
 * front: a small image (a PNG or GIF, so the response passes as a picture), or padding. The media after
 * those bytes is intact, so when byte 0 doesn't start a known container the first [SCAN_LIMIT] bytes
 * are searched for a start that a second, independent check confirms:
 *  - MPEG-TS: a 0x47 sync byte that recurs [TS_CONFIRMATIONS] times at a 188-, 192- (M2TS, the packet
 *    begins 4 bytes earlier) or 204-byte stride;
 *  - MP4 (incl. fragmented MP4 / CMAF): a top-level box whose size lands exactly on the next known box
 *    or on the end of the file;
 *  - Matroska / WebM: the 4-byte EBML magic or a bare Cluster ID.
 * Packed audio (ID3 tag or a raw audio frame) is only recognised at byte 0: its sync words are too short
 * to search for in arbitrary bytes.
 */
internal object SegmentProbe {
    enum class Container { TS, MP4, MKV, PACKED_AUDIO }

    /** [container] of a segment, whose media starts [offset] bytes into the file. */
    data class Located(val container: Container, val offset: Long)

    /** How far into a segment its media may start. */
    const val SCAN_LIMIT = 64 * 1024

    private const val M2TS_PACKET = 192
    private const val TS_CONFIRMATIONS = 3
    private val TS_PACKET_SIZES = intArrayOf(188, 192, 204)
    private val MP4_TOP_LEVEL_TYPES = setOf(
        "ftyp", "styp", "moov", "moof", "sidx", "ssix", "free", "skip", "mdat", "emsg", "prft", "uuid", "meta",
    )

    /**
     * Where [path]'s media starts. Null when no container is found in the first [SCAN_LIMIT] bytes; the
     * caller then reads the file from byte 0 as MP4 (an MP4 that starts with an unlisted box type).
     */
    fun locate(path: String): Located? = RandomAccessFile(path, "r").use { locate(it) }

    fun locate(raf: RandomAccessFile): Located? {
        val length = raf.length()
        // Enough for TS_CONFIRMATIONS strides of the largest packet past the furthest start.
        val head = ByteArray(minOf(length, (SCAN_LIMIT + 4 + 204 * (TS_CONFIRMATIONS + 1)).toLong()).toInt())
        raf.seek(0)
        raf.readFully(head)
        atStart(head)?.let { return Located(it, 0) }
        for (k in 1 until minOf(head.size, SCAN_LIMIT)) {
            find(head, k, length, raf)?.let { return it }
        }
        return null
    }

    /** The container that starts at byte 0, by the checks used before any search. */
    private fun atStart(head: ByteArray): Container? {
        if (isEbmlMagic(head, 0)) return Container.MKV
        if (head.size >= 8 && String(head, 4, 4, Charsets.US_ASCII) in MP4_TOP_LEVEL_TYPES) return Container.MP4
        if (isCluster(head, 0)) return Container.MKV
        if (head.isNotEmpty() && u8(head, 0) == 0x47) return Container.TS
        if (head.size > M2TS_PACKET + 4 && u8(head, 4) == 0x47 && u8(head, M2TS_PACKET + 4) == 0x47) return Container.TS
        if (PackedAudioDemuxer.isId3(head, 0) || isAudioSync(head)) return Container.PACKED_AUDIO
        return null
    }

    private fun find(head: ByteArray, k: Int, length: Long, raf: RandomAccessFile): Located? {
        if (isEbmlMagic(head, k) || isCluster(head, k)) return Located(Container.MKV, k.toLong())
        if (u8(head, k) == 0x47) {
            for (size in TS_PACKET_SIZES) {
                if (size == M2TS_PACKET && k < 4) continue
                if (tsStrideHolds(head, k, size, length)) {
                    return Located(Container.TS, if (size == M2TS_PACKET) k - 4L else k.toLong())
                }
            }
        }
        if (isMp4BoxChain(head, k, length, raf)) return Located(Container.MP4, k.toLong())
        return null
    }

    /** The sync byte recurs at [size] strides from [k], [TS_CONFIRMATIONS] times or up to the file's end. */
    private fun tsStrideHolds(head: ByteArray, k: Int, size: Int, length: Long): Boolean {
        var count = 0
        var p = k + size
        while (count < TS_CONFIRMATIONS) {
            if (p >= head.size) return count > 0 && p >= length
            if (u8(head, p) != 0x47) return false
            count++
            p += size
        }
        return true
    }

    /** A known box at [k] whose size ends at the file's end or at another known box. */
    private fun isMp4BoxChain(head: ByteArray, k: Int, length: Long, raf: RandomAccessFile): Boolean {
        if (k + 8 > head.size || !isBoxType(head, k + 4)) return false
        var size = u32(head, k)
        if (size == 1L) {
            if (k + 16 > head.size) return false
            size = (u32(head, k + 8) shl 32) or u32(head, k + 12)
            if (size < 16) return false
        } else if (size == 0L) {
            return true // to the end of the file
        } else if (size < 8) {
            return false
        }
        val next = k + size
        if (next == length) return true
        if (next + 8 > length) return false
        if (next + 8 <= head.size) return isBoxType(head, (next + 4).toInt())
        val box = ByteArray(8)
        raf.seek(next)
        raf.readFully(box)
        return isBoxType(box, 4)
    }

    private fun isBoxType(b: ByteArray, at: Int): Boolean =
        at + 4 <= b.size && String(b, at, 4, Charsets.US_ASCII) in MP4_TOP_LEVEL_TYPES

    private fun isEbmlMagic(b: ByteArray, at: Int) = at + 4 <= b.size &&
        u8(b, at) == 0x1A && u8(b, at + 1) == 0x45 && u8(b, at + 2) == 0xDF && u8(b, at + 3) == 0xA3

    /** A media segment that begins mid-Matroska-Segment starts with a bare Cluster (0x1F43B675). */
    private fun isCluster(b: ByteArray, at: Int) = at + 4 <= b.size &&
        u8(b, at) == 0x1F && u8(b, at + 1) == 0x43 && u8(b, at + 2) == 0xB6 && u8(b, at + 3) == 0x75

    /** ADTS / MPEG audio (0xFFE sync) or AC-3 / E-AC-3 (0x0B77) frame at the start of [head]. */
    private fun isAudioSync(head: ByteArray): Boolean {
        if (head.size < 2) return false
        val b0 = u8(head, 0)
        val b1 = u8(head, 1)
        return (b0 == 0xFF && (b1 and 0xE0) == 0xE0) || (b0 == 0x0B && b1 == 0x77)
    }

    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF

    private fun u32(b: ByteArray, at: Int): Long =
        ((u8(b, at).toLong() shl 24) or (u8(b, at + 1).toLong() shl 16) or (u8(b, at + 2).toLong() shl 8) or
            u8(b, at + 3).toLong())
}
