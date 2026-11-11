// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Bytes that sites put in front of stream segments (pictures, ID3 tags, junk), and minimal media
 * to put after them, for the start-detection tests.
 */
internal object PrefixBytes {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** A valid 1x1 GIF89a (43 bytes). It starts with 0x47, the TS sync byte. */
    val gif: ByteArray = hex(
        "474946383961010001008000000000" + "00ffffff" + "21f904010000000000" +
            "2c00000000010001000002024401003b"
    )

    /** A PNG with an IDAT of [dataSize] bytes holding TS-like sync bytes at a 188 stride and a fake box. */
    fun png(dataSize: Int): ByteArray {
        val idat = ByteArray(dataSize) { 0x11 }
        var i = 0
        while (i < dataSize) { idat[i] = 0x47; i += 188 }
        "\u0000\u0000\u0000\u0010moof".toByteArray(Charsets.ISO_8859_1).copyInto(idat, minOf(1000, dataSize - 8).coerceAtLeast(0))
        fun chunk(type: String, data: ByteArray) = u32(data.size) + type.toByteArray(Charsets.US_ASCII) + data + ByteArray(4)
        return hex("89504e470d0a1a0a") + chunk("IHDR", ByteArray(13)) + chunk("IDAT", idat) + chunk("IEND", ByteArray(0))
    }

    /** A baseline JPEG skeleton: APP0, DQT, SOS, entropy data with stuffed 0xFF00 and a restart marker, EOI. */
    fun jpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(hex("ffd8"))
        out.write(hex("ffe00010") + ByteArray(14))
        out.write(hex("ffdb0043") + ByteArray(65))
        out.write(hex("ffda000c") + ByteArray(10))
        val rnd = Random(7)
        repeat(600) { out.write(rnd.nextInt(0, 0xFF)) }  // never 0xFF itself
        out.write(hex("ff00"))
        repeat(300) { out.write(rnd.nextInt(0, 0xFF)) }
        out.write(hex("ffd0"))
        repeat(300) { out.write(rnd.nextInt(0, 0xFF)) }
        out.write(hex("ffd9"))
        return out.toByteArray()
    }

    /** A 24-bit 2x2 BMP. */
    fun bmp(): ByteArray {
        val size = 54 + 16
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
        return "BM".toByteArray(Charsets.US_ASCII) + le32(size) + ByteArray(4) + le32(54) + le32(40) + ByteArray(36) + ByteArray(16)
    }

    /** An ID3v2.4 tag with [bodySize] bytes of padding. */
    fun id3(bodySize: Int): ByteArray {
        val s = byteArrayOf((bodySize shr 21 and 0x7F).toByte(), (bodySize shr 14 and 0x7F).toByte(),
            (bodySize shr 7 and 0x7F).toByte(), (bodySize and 0x7F).toByte())
        return "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0) + s + ByteArray(bodySize)
    }

    /** [n] seeded random bytes. */
    fun junk(n: Int, seed: Int = 1): ByteArray = Random(seed).nextBytes(n)

    /** [n] TS null packets (PID 0x1FFF), [packetSize] 188 or 204; 192 adds a 4-byte timestamp in front. */
    fun tsPackets(n: Int, packetSize: Int = 188): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(n) {
            if (packetSize == 192) out.write(ByteArray(4) { 0x3A })
            out.write(hex("471fff10"))
            out.write(ByteArray(184) { 0xFF.toByte() })
            if (packetSize == 204) out.write(ByteArray(16))
        }
        return out.toByteArray()
    }

    /** [n] ADTS frames (AAC LC, 48 kHz, stereo) of [frameSize] bytes. */
    fun adts(n: Int, frameSize: Int = 100): ByteArray {
        val h = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (3 shl 2)).toByte(),
            ((2 shl 6) or (frameSize shr 11)).toByte(), (frameSize shr 3).toByte(), (((frameSize and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())
        val out = ByteArrayOutputStream()
        repeat(n) { out.write(h); out.write(ByteArray(frameSize - 7)) }
        return out.toByteArray()
    }

    /** [n] AC-3 frames (48 kHz, frmsizecod 8: 256 bytes). */
    fun ac3(n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(n) { out.write(hex("0b770000") + byteArrayOf(8, (8 shl 3).toByte()) + ByteArray(250)) }
        return out.toByteArray()
    }

    /** An EBML header (DocType webm) followed by an unknown-size Segment. */
    fun ebml(): ByteArray {
        val docType = hex("4282") + byteArrayOf(0x84.toByte()) + "webm".toByteArray(Charsets.US_ASCII)
        val body = hex("42868101") + docType
        return hex("1a45dfa3") + byteArrayOf((0x80 or body.size).toByte()) + body + hex("1853806701ffffffffffffff")
    }
}
