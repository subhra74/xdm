// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.ts

/** The CRC32 of MPEG-2 PSI sections (polynomial 0x04C11DB7, no reflection, initial 0xFFFFFFFF). */
object MpegCrc32 {
    private val TABLE = IntArray(256) { n ->
        var c = n shl 24
        repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        c
    }

    fun of(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var crc = -1
        for (i in from until to) crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (b[i].toInt() and 0xFF)) and 0xFF]
        return crc
    }
}
