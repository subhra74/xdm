// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.sample

import xdm.core.util.Logger
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * The sample records of one track, in decode order.
 *
 * A two-hour video has over half a million samples, too many to keep as objects on a phone that gives
 * an app a 48 MB heap. Records are stored in column blocks of [BLOCK] rows (offset, size, pts, dts,
 * flags: 30 bytes a row). Inside a [SampleSpill] scope every full block is written to a scratch file
 * in the scope's folder and only the block being filled plus two cached blocks stay in memory, so the
 * heap used is the same for any length. Outside a scope (tests, probes) all blocks stay in memory.
 *
 * Reads and writes are by row index; the writers scan front to back, which reads each block once.
 * Rows can be changed after they were added (timestamps spread over guessed samples, discontinuity
 * sections shifted): a changed cached block is written back when it leaves the cache.
 *
 * Not thread-safe: a track is filled and written on one thread.
 */
class SampleTable : AbstractList<Sample>(), Closeable {
    private var count = 0

    /** The block being filled; never in the spill file. */
    private var tail = Block(0)

    /** Full blocks when not spilling (no scope, or the scratch file couldn't be created). */
    private val memoryBlocks = ArrayList<Block>()

    private var spillDir: File? = null
    private var spillFile: File? = null
    private var raf: RandomAccessFile? = null
    private var scratch: ByteArray? = null

    /** Cached spilled blocks, most recently used first. */
    private var cache0: Block? = null
    private var cache1: Block? = null

    private var durationsDerived = false
    private var repairTypical = -1L
    private var repairLimit = Long.MAX_VALUE

    init {
        SampleSpill.current()?.let { scope ->
            spillDir = scope.dir
            scope.register(this)
        }
    }

    override val size: Int get() = count

    override fun get(index: Int): Sample {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("index $index, size $count")
        return Sample(this, index)
    }

    /** Appends a sample record. [descriptionIndex] is the 1-based stsd entry (see [Track.additionalSampleEntries]). */
    fun add(fileOffset: Long, size: Int, pts: Long, dts: Long, keyframe: Boolean, descriptionIndex: Int = 1) {
        require(descriptionIndex in 1..MAX_DESCRIPTION_INDEX) { "sample description index $descriptionIndex" }
        if (tail.used == BLOCK) pushTail()
        val k = tail.used++
        tail.offset[k] = fileOffset
        tail.size[k] = size
        tail.pts[k] = pts
        tail.dts[k] = dts
        tail.flags[k] = ((if (keyframe) KEY_FLAG else 0) or descriptionIndex).toShort()
        count++
    }

    fun fileOffset(i: Int): Long = block(i).offset[i and MASK]
    fun sampleSize(i: Int): Int = block(i).size[i and MASK]
    fun pts(i: Int): Long = block(i).pts[i and MASK]
    fun dts(i: Int): Long = block(i).dts[i and MASK]
    fun isKeyframe(i: Int): Boolean = (block(i).flags[i and MASK].toInt() and KEY_FLAG) != 0
    fun descriptionIndex(i: Int): Int = block(i).flags[i and MASK].toInt() and MAX_DESCRIPTION_INDEX

    fun setPts(i: Int, v: Long) { val b = block(i); b.pts[i and MASK] = v; b.dirty = true }
    fun setDts(i: Int, v: Long) { val b = block(i); b.dts[i and MASK] = v; b.dirty = true }

    /** Moves rows [from, to) by [ticks] on the timeline (pts and dts). */
    fun shift(from: Int, to: Int, ticks: Long) {
        if (ticks == 0L) return
        for (j in from until to) {
            val b = block(j)
            b.pts[j and MASK] += ticks
            b.dts[j and MASK] += ticks
            b.dirty = true
        }
    }

    /** Decode-time step to the next sample; the last sample repeats the step before it, a lone sample gets 1. */
    fun rawDuration(i: Int): Long = when {
        count == 1 -> 1
        i < count - 1 -> dts(i + 1) - dts(i)
        else -> dts(i) - dts(i - 1)
    }

    /**
     * From now on [duration] is the [rawDuration], replaced by [repairTypical] when that is positive and
     * the step is negative or above [repairLimit], and never below 0.
     */
    fun deriveDurations(repairTypical: Long = -1, repairLimit: Long = Long.MAX_VALUE) {
        durationsDerived = true
        this.repairTypical = repairTypical
        this.repairLimit = repairLimit
    }

    /** The sample's duration in track ticks; 0 until [deriveDurations] is called. */
    fun duration(i: Int): Long {
        if (!durationsDerived) return 0
        var d = rawDuration(i)
        if (repairTypical > 0 && (d < 0 || d > repairLimit)) d = repairTypical
        return if (d < 0) 0 else d
    }

    /** Drops the scratch file; the table must not be used afterwards. Called by the [SampleSpill] scope. */
    override fun close() {
        try { raf?.close() } catch (_: IOException) {}
        raf = null
        spillFile?.delete()
        spillFile = null
        memoryBlocks.clear()
        cache0 = null; cache1 = null; scratch = null
    }

    // ---- blocks ----

    private class Block(var index: Int) {
        val offset = LongArray(BLOCK)
        val size = IntArray(BLOCK)
        val pts = LongArray(BLOCK)
        val dts = LongArray(BLOCK)
        val flags = ShortArray(BLOCK)
        var used = 0
        var dirty = false
    }

    private fun block(row: Int): Block {
        val bi = row ushr SHIFT
        if (bi == tail.index) return tail
        val file = raf ?: return memoryBlocks[bi]
        cache0?.let { if (it.index == bi) return it }
        cache1?.let { if (it.index == bi) { cache1 = cache0; cache0 = it; return it } }
        // Load into the least recently used slot, writing it back first if it was changed.
        val b = cache1 ?: Block(bi)
        if (b.dirty) writeBlock(file, b)
        b.index = bi
        readBlock(file, b)
        cache1 = cache0
        cache0 = b
        return b
    }

    /** The tail is full: store it and start the next block. */
    private fun pushTail() {
        val full = tail
        val file = raf ?: openSpill()
        if (file == null) {
            memoryBlocks.add(full)
            tail = Block(full.index + 1)
            return
        }
        writeBlock(file, full)
        // The written block's arrays become the new tail.
        full.index++
        full.used = 0
        full.dirty = false
    }

    private fun openSpill(): RandomAccessFile? {
        val dir = spillDir ?: return null
        if (memoryBlocks.isNotEmpty()) return null // spilling failed before: stay in memory
        return try {
            dir.mkdirs()
            val f = File.createTempFile("samples", ".tbl", dir)
            spillFile = f
            RandomAccessFile(f, "rw").also { raf = it; scratch = ByteArray(BLOCK_BYTES) }
        } catch (e: IOException) {
            Logger.error("XDM", "Sample table kept in memory: no scratch file in $dir", e)
            spillDir = null
            null
        }
    }

    private fun writeBlock(file: RandomAccessFile, b: Block) {
        val bytes = scratch!!
        ByteBuffer.wrap(bytes, 0, BLOCK * 8).asLongBuffer().put(b.offset)
        ByteBuffer.wrap(bytes, BLOCK * 8, BLOCK * 4).asIntBuffer().put(b.size)
        ByteBuffer.wrap(bytes, BLOCK * 12, BLOCK * 8).asLongBuffer().put(b.pts)
        ByteBuffer.wrap(bytes, BLOCK * 20, BLOCK * 8).asLongBuffer().put(b.dts)
        ByteBuffer.wrap(bytes, BLOCK * 28, BLOCK * 2).asShortBuffer().put(b.flags)
        file.seek(b.index.toLong() * BLOCK_BYTES)
        file.write(bytes, 0, BLOCK_BYTES)
        b.dirty = false
    }

    private fun readBlock(file: RandomAccessFile, b: Block) {
        val bytes = scratch!!
        file.seek(b.index.toLong() * BLOCK_BYTES)
        file.readFully(bytes, 0, BLOCK_BYTES)
        ByteBuffer.wrap(bytes, 0, BLOCK * 8).asLongBuffer().get(b.offset)
        ByteBuffer.wrap(bytes, BLOCK * 8, BLOCK * 4).asIntBuffer().get(b.size)
        ByteBuffer.wrap(bytes, BLOCK * 12, BLOCK * 8).asLongBuffer().get(b.pts)
        ByteBuffer.wrap(bytes, BLOCK * 20, BLOCK * 8).asLongBuffer().get(b.dts)
        ByteBuffer.wrap(bytes, BLOCK * 28, BLOCK * 2).asShortBuffer().get(b.flags)
        b.used = BLOCK
        b.dirty = false
    }

    private companion object {
        const val SHIFT = 11
        const val BLOCK = 1 shl SHIFT
        const val MASK = BLOCK - 1
        const val BLOCK_BYTES = BLOCK * 30
        const val KEY_FLAG = 0x8000
        const val MAX_DESCRIPTION_INDEX = 0x7FFF
    }
}

/**
 * Scope in which new [SampleTable]s on this thread spill their full blocks to scratch files in [dir].
 * Closing the scope closes those tables and deletes their files, also when muxing failed.
 */
class SampleSpill private constructor(val dir: File, private val outer: SampleSpill?) : Closeable {
    private val tables = ArrayList<SampleTable>()

    internal fun register(table: SampleTable) { tables.add(table) }

    override fun close() {
        for (t in tables) t.close()
        tables.clear()
        if (current.get() === this) {
            if (outer != null) current.set(outer) else current.remove()
        }
    }

    companion object {
        private val current = ThreadLocal<SampleSpill>()

        internal fun current(): SampleSpill? = current.get()

        /** Opens a scope on this thread; close it on the same thread. */
        fun open(dir: File): SampleSpill = SampleSpill(dir, current.get()).also { current.set(it) }
    }
}

/** Receives values from a [LongSource] without boxing. */
fun interface LongSink { fun accept(v: Long) }

/** Feeds a sequence of values to a sink; may be called several times and must feed the same values each time. */
fun interface LongSource { fun forEach(sink: LongSink) }

/**
 * The value at rank `n / 2` of the sorted non-negative values of [source] (the upper median), or -1
 * when it is empty. Exact without holding the values: a 16-bit radix select over four more passes.
 */
fun upperMedian(source: LongSource): Long {
    var n = 0L
    source.forEach { n++ }
    if (n == 0L) return -1
    var rank = n / 2
    var prefix = 0L
    var prefixMask = 0L
    val hist = LongArray(1 shl 16)
    for (shift in intArrayOf(48, 32, 16, 0)) {
        hist.fill(0)
        val p = prefix
        val m = prefixMask
        source.forEach { v -> if ((v and m) == p) hist[((v ushr shift) and 0xFFFF).toInt()]++ }
        var b = 0
        while (rank >= hist[b]) { rank -= hist[b]; b++ }
        prefix = prefix or (b.toLong() shl shift)
        prefixMask = prefixMask or (0xFFFFL shl shift)
    }
    return prefix
}
