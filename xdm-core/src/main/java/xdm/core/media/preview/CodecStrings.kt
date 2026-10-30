// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.media.preview

/**
 * WebCodecs / RFC 6381 codec strings from an MP4 sample entry's config box body (the bytes after
 * the box header). Null when the body is too short to hold the fields read.
 */
internal object CodecStrings {

    /** `avc1.PPCCLL`: profile, constraint flags and level, straight from `avcC`. */
    fun avc(format: String, avcC: ByteArray): String? {
        if (avcC.size < 4) return null
        return "$format.${hex2(avcC[1])}${hex2(avcC[2])}${hex2(avcC[3])}"
    }

    /** `hvc1.<space><profile>.<compat, bit-reversed hex>.<tier><level>[.<constraint bytes>]` (ISO/IEC 14496-15 E.3). */
    fun hevc(format: String, hvcC: ByteArray): String? {
        if (hvcC.size < 13) return null
        val b1 = hvcC[1].toInt() and 0xFF
        val space = when (b1 shr 6) { 1 -> "A"; 2 -> "B"; 3 -> "C"; else -> "" }
        val tier = if (b1 and 0x20 != 0) "H" else "L"
        val profile = b1 and 0x1F
        val compat = Integer.reverse(Mp4KeyframeLocator.u32(hvcC, 2).toInt()).toLong() and 0xFFFFFFFFL
        val constraints = (6..11).map { hvcC[it].toInt() and 0xFF }.dropLastWhile { it == 0 }
        val level = hvcC[12].toInt() and 0xFF
        return buildString {
            append(format).append('.').append(space).append(profile)
            append('.').append(java.lang.Long.toHexString(compat).uppercase())
            append('.').append(tier).append(level)
            constraints.forEach { append('.').append(Integer.toHexString(it).uppercase()) }
        }
    }

    /** `av01.P.LLT.DD`: profile, level, tier and bit depth from `av1C`. */
    fun av1(av1C: ByteArray): String? {
        if (av1C.size < 3) return null
        val b1 = av1C[1].toInt() and 0xFF
        val b2 = av1C[2].toInt() and 0xFF
        val profile = b1 shr 5
        val level = b1 and 0x1F
        val tier = if (b2 and 0x80 != 0) "H" else "M"
        val highBitDepth = b2 and 0x40 != 0
        val twelveBit = b2 and 0x20 != 0
        val depth = if (!highBitDepth) 8 else if (profile == 2 && twelveBit) 12 else 10
        return "av01.$profile.${pad2(level)}$tier.${pad2(depth)}"
    }

    /** `vp09.PP.LL.DD`: profile, level and bit depth from `vpcC` (a full box: version/flags first). */
    fun vp9(vpcC: ByteArray): String? {
        if (vpcC.size < 7) return null
        val profile = vpcC[4].toInt() and 0xFF
        val level = vpcC[5].toInt() and 0xFF
        val depth = (vpcC[6].toInt() and 0xFF) shr 4
        return "vp09.${pad2(profile)}.${pad2(level)}.${pad2(depth)}"
    }

    private fun hex2(b: Byte): String = String.format("%02x", b.toInt() and 0xFF)

    private fun pad2(v: Int): String = if (v < 10) "0$v" else "$v"
}
