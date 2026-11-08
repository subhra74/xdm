// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.mp4

import java.io.OutputStream

/**
 * Tiny big-endian writer for building ISO-BMFF boxes in memory. `box("moov") { ... }` writes a
 * 4-byte size placeholder + type, runs the body, then back-patches the size in place. Backed by a
 * directly-indexable growable array so size patching is O(1).
 *
 * Large tables need not be held in memory: [deferred] reserves a region of known length that a
 * writer fills only when the boxes are streamed out by [writeTo]; enclosing box sizes include it.
 */
class BoxBuf {
    private var buf = ByteArray(4 * 1024)
    private var len = 0

    private class Deferred(val at: Int, val length: Long, val write: (TableOut) -> Unit)
    private val deferred = ArrayList<Deferred>()

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

    /**
     * Reserves [length] bytes here, written by [write] during [writeTo]. The writer must write exactly
     * [length] bytes; [writeTo] fails otherwise.
     */
    fun deferred(length: Long, write: (TableOut) -> Unit) {
        deferred.add(Deferred(len, length, write))
    }

    inline fun box(type: String, body: BoxBuf.() -> Unit) {
        val start = mark()
        u32(0)          // size placeholder
        fourcc(type)
        body()
        patchSize(start)
    }

    fun mark(): Int = len

    fun patchSize(start: Int) {
        var size = (len - start).toLong()
        // Regions reserved inside the box (after its header, so strictly after start).
        for (d in deferred) if (d.at > start) size += d.length
        buf[start] = (size ushr 24).toByte()
        buf[start + 1] = (size ushr 16).toByte()
        buf[start + 2] = (size ushr 8).toByte()
        buf[start + 3] = size.toByte()
    }

    /** The bytes built; only for buffers without [deferred] regions. */
    fun toByteArray(): ByteArray {
        check(deferred.isEmpty()) { "box has deferred tables: use writeTo" }
        return buf.copyOf(len)
    }

    /** Bytes held in memory (without [deferred] regions). */
    fun size(): Int = len

    /** Total length once written, [deferred] regions included. */
    fun totalSize(): Long = len + deferred.sumOf { it.length }

    /** Streams the boxes to [out], running each [deferred] writer at its place. */
    fun writeTo(out: OutputStream) {
        var from = 0
        val table = TableOut(out)
        for (d in deferred) {
            out.write(buf, from, d.at - from)
            from = d.at
            val before = table.count
            d.write(table)
            table.flush()
            check(table.count - before == d.length) { "table wrote ${table.count - before} bytes, reserved ${d.length}" }
        }
        out.write(buf, from, len - from)
    }
}

/** Big-endian writer for the sample tables streamed by [BoxBuf.writeTo]; [count] is the bytes written. */
class TableOut(private val out: OutputStream) {
    private val b = ByteArray(16 * 1024)
    private var n = 0
    var count = 0L
        private set

    fun u32(v: Long) {
        if (n + 4 > b.size) flush()
        b[n] = (v ushr 24).toByte(); b[n + 1] = (v ushr 16).toByte(); b[n + 2] = (v ushr 8).toByte(); b[n + 3] = v.toByte()
        n += 4; count += 4
    }
    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }

    fun flush() {
        if (n > 0) out.write(b, 0, n)
        n = 0
    }
}
