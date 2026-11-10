// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders.web.http

import xdm.core.storage.PositionalOutput
import java.io.IOException
import java.nio.channels.ClosedChannelException

/**
 * Fills the part file up front, front to back in [STEP] steps, on a disk that cannot have holes (FAT32,
 * exFAT: [xdm.core.storage.StorageFolder.fillsGaps]), before any data is written.
 *
 * There a write far into a file, or growing it, makes the file system write zeros over the whole gap
 * first, inside that one call. Sizing the part file in one go would sit at 0% for minutes on a slow card
 * or USB stick, with Pause doing nothing, and a chunk starting far into the file would stall the same way.
 * Instead the file grows by one byte at the end of each step (the file system zero-fills the step), so the
 * download shows how far it got and can stop between steps. The zeros get written either way; this only
 * makes the wait visible and stoppable.
 *
 * The download prepares first, then downloads: while the file is being filled nothing splits, no chunk starts
 * on resume, and every data write waits ([Guarded]); the first connection of a new download has read the
 * size and holds its first write until the fill is done. A chunk therefore never grows the file itself and a
 * zero byte never lands on downloaded data.
 *
 * Not saved: on these disks the part file's length is how far it got, and a resume goes on from there.
 */
class PartAllocation {
    private val lock = Object()

    /** True from [begin] until the file is filled, or the fill failed. Writes, splits and chunk starts wait for it. */
    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    private var failure: IOException? = null

    /** Starts holding every write back until [finish]. */
    fun begin() {
        synchronized(lock) {
            failure = null
            active = true
        }
    }

    /** The file is filled: every write goes straight through from now on. */
    fun finish() {
        synchronized(lock) {
            active = false
            lock.notifyAll()
        }
    }

    /** Filling stopped with [error]: waiting writes, and any later ones, fail with it. */
    fun fail(error: IOException) {
        synchronized(lock) {
            failure = error
            active = false
            lock.notifyAll()
        }
    }

    /** Waits until writes may go ahead; throws if filling failed or [closed] says to stop. */
    private fun await(closed: () -> Boolean) {
        if (!active && failure == null) return
        synchronized(lock) {
            while (true) {
                if (closed()) throw ClosedChannelException()
                failure?.let { throw IOException("Could not allocate the part file", it) }
                if (!active) return
                lock.wait(WAIT_MS)
            }
        }
    }

    private fun wake() {
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * The part file's output as chunks see it: a write waits until the file is filled. Closing it (pause,
     * failure, completion) fails a waiting write with [ClosedChannelException], which the stopping download
     * reads as a cancel.
     */
    inner class Guarded(private val out: PositionalOutput) : PositionalOutput {
        @Volatile
        private var closed = false

        /** The unguarded output, for the filling itself. */
        val inner: PositionalOutput get() = out

        override fun write(position: Long, b: ByteArray, off: Int, len: Int) {
            await { closed }
            out.write(position, b, off, len)
        }

        override fun length(): Long = out.length()
        override fun force() = out.force()

        override fun close() {
            closed = true
            wake()
            out.close()
        }
    }

    companion object {
        /** How far each step grows the file: short enough to stop within a fraction of a second on a slow stick. */
        const val STEP = 512L * 1024

        /**
         * The fill flushes to the disk after this much, and so does the download on these disks afterwards. On a
         * slow card (5 MB/s measured on a nearly full FAT32 card) that is under 2 s, which is also about how long
         * a Pause may wait.
         */
        const val FLUSH_EVERY = 8L * 1024 * 1024

        /** Files smaller than this are sized in one go as before: the gap fill is short. */
        const val MIN_BYTES = 8L * 1024 * 1024

        private const val WAIT_MS = 250L
    }
}
