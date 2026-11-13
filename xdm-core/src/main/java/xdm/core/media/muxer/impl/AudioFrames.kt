// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.impl

/**
 * Frame headers of the codecs HLS packed audio allows (RFC 8216 3.4): ADTS AAC, MPEG audio, AC-3
 * and E-AC-3. Enough to walk a segment frame by frame and time each frame; nothing is decoded.
 */
internal object AudioFrames {
    enum class Kind(val streamType: Int, val streamId: Int, val registration: String?) {
        ADTS(0x0F, 0xC0, null), MPEG1_AUDIO(0x03, 0xC0, null), MPEG2_AUDIO(0x04, 0xC0, null),
        AC3(0x81, 0xBD, "AC-3"), EAC3(0x87, 0xBD, "EAC3"),
    }

    /** One frame at a position: its [length] in bytes, [samples] per channel and [sampleRate]. */
    class Frame(val kind: Kind, val length: Int, val samples: Int, val sampleRate: Int)

    private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    private val MPEG_RATES = intArrayOf(44100, 48000, 32000)
    private val MPEG1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val MPEG1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val MPEG1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val MPEG2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val AC3_KBPS = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640)

    /** The frame starting at [at], or null if no known frame header is there. */
    fun at(b: ByteArray, at: Int): Frame? {
        if (at + 7 > b.size) return null
        val b0 = b[at].toInt() and 0xFF
        val b1 = b[at + 1].toInt() and 0xFF
        return when {
            b0 == 0xFF && (b1 and 0xF6) == 0xF0 -> adts(b, at)
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 -> mpeg(b, at)
            b0 == 0x0B && b1 == 0x77 -> ac3(b, at)
            else -> null
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun adts(b: ByteArray, at: Int): Frame? {
        val freq = (u8(b, at + 2) shr 2) and 0x0F
        val len = ((u8(b, at + 3) and 0x03) shl 11) or (u8(b, at + 4) shl 3) or (u8(b, at + 5) shr 5)
        if (freq > 12 || len < 7) return null
        return Frame(Kind.ADTS, len, 1024 * ((u8(b, at + 6) and 0x03) + 1), AAC_RATES[freq])
    }

    private fun mpeg(b: ByteArray, at: Int): Frame? {
        val h = (u8(b, at + 1) shl 16) or (u8(b, at + 2) shl 8) or u8(b, at + 3)
        val version = (h shr 19) and 3   // 3: MPEG-1, 2: MPEG-2, 0: MPEG-2.5
        val layer = (h shr 17) and 3     // 3: I, 2: II, 1: III
        val brIndex = (h shr 12) and 0x0F
        val srIndex = (h shr 10) and 3
        val pad = (h shr 9) and 1
        if (version == 1 || layer == 0 || brIndex == 0 || brIndex == 15 || srIndex == 3) return null
        val mpeg1 = version == 3
        val rate = MPEG_RATES[srIndex] / (if (mpeg1) 1 else if (version == 2) 2 else 4)
        val kbps = when {
            mpeg1 && layer == 3 -> MPEG1_L1; mpeg1 && layer == 2 -> MPEG1_L2; mpeg1 -> MPEG1_L3
            layer == 3 -> MPEG2_L1; else -> MPEG2_L23
        }[brIndex]
        val len = when {
            layer == 3 -> (12 * kbps * 1000 / rate + pad) * 4
            layer == 2 || mpeg1 -> 144 * kbps * 1000 / rate + pad
            else -> 72 * kbps * 1000 / rate + pad
        }
        val samples = if (layer == 3) 384 else if (layer == 1 && !mpeg1) 576 else 1152
        return Frame(if (mpeg1) Kind.MPEG1_AUDIO else Kind.MPEG2_AUDIO, len, samples, rate)
    }

    private fun ac3(b: ByteArray, at: Int): Frame? {
        val bsid = u8(b, at + 5) shr 3
        if (bsid > 10) {
            // E-AC-3: frame size in words, then fscod / numblkscod.
            val len = ((((u8(b, at + 2) and 0x07) shl 8) or u8(b, at + 3)) + 1) * 2
            val fscod = u8(b, at + 4) shr 6
            val (rate, blocks) = if (fscod == 3) {
                intArrayOf(24000, 22050, 16000).getOrElse((u8(b, at + 4) shr 4) and 3) { return null } to 6
            } else intArrayOf(48000, 44100, 32000)[fscod] to intArrayOf(1, 2, 3, 6)[(u8(b, at + 4) shr 4) and 3]
            return Frame(Kind.EAC3, len, 256 * blocks, rate)
        }
        val fscod = u8(b, at + 4) shr 6
        val code = u8(b, at + 4) and 0x3F
        if (fscod == 3 || code >= 38) return null
        val kbps = AC3_KBPS[code / 2]
        val words = when (fscod) {
            0 -> kbps * 2                                  // 48 kHz
            1 -> kbps * 320 / 147 + (code and 1)           // 44.1 kHz
            else -> kbps * 3                               // 32 kHz
        }
        return Frame(Kind.AC3, words * 2, 1536, intArrayOf(48000, 44100, 32000)[fscod])
    }
}
