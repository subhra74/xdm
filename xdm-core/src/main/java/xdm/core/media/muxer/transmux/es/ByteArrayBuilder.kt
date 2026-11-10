// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.es

/** Minimal growable byte buffer used to assemble length-prefixed NAL access units. */
class ByteArrayBuilder(initial: Int = 64 * 1024) {
    var buffer = ByteArray(initial)
        private set
    var size = 0
        private set

    fun reset() { size = 0 }

    /** Drops the first [from] bytes, moving the rest to the start of the buffer. */
    fun compact(from: Int) {
        if (from <= 0) return
        val keep = size - from
        if (keep > 0) System.arraycopy(buffer, from, buffer, 0, keep)
        size = maxOf(keep, 0)
    }

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need > buffer.size) {
            var cap = buffer.size * 2
            while (cap < need) cap *= 2
            buffer = buffer.copyOf(cap)
        }
    }

    fun writeInt(v: Int) {
        ensure(4)
        buffer[size++] = (v ushr 24).toByte()
        buffer[size++] = (v ushr 16).toByte()
        buffer[size++] = (v ushr 8).toByte()
        buffer[size++] = v.toByte()
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        ensure(length)
        System.arraycopy(data, offset, buffer, size, length)
        size += length
    }
}
