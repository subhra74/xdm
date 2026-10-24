// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.network.http

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NTLMv2 messages for HTTP authentication (MS-NLMP): the Type1 (NEGOTIATE) a client opens with, the
 * server's Type2 (CHALLENGE), and the Type3 (AUTHENTICATE) answering it.
 *
 * Like curl, this does no key exchange, signing, sealing or MIC: HTTP never uses the session key. A
 * server that insists on a MIC or on channel binding (Extended Protection) refuses the Type3.
 */
object Ntlm {
    private const val NEGOTIATE_UNICODE = 0x00000001
    private const val NEGOTIATE_OEM = 0x00000002
    private const val REQUEST_TARGET = 0x00000004
    private const val NEGOTIATE_NTLM = 0x00000200
    private const val NEGOTIATE_ALWAYS_SIGN = 0x00008000
    private const val NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000
    private const val NEGOTIATE_TARGET_INFO = 0x00800000
    private const val NEGOTIATE_128 = 0x20000000
    private const val NEGOTIATE_56 = 0x80000000.toInt()

    private const val TYPE1_FLAGS = NEGOTIATE_UNICODE or NEGOTIATE_OEM or REQUEST_TARGET or NEGOTIATE_NTLM or
        NEGOTIATE_ALWAYS_SIGN or NEGOTIATE_EXTENDED_SESSIONSECURITY or NEGOTIATE_128 or NEGOTIATE_56

    private const val AV_EOL = 0
    private const val AV_TIMESTAMP = 7

    /** 100 ns intervals between 1601-01-01 (FILETIME epoch) and 1970-01-01. */
    private const val FILETIME_EPOCH_OFFSET = 116444736000000000L

    private val SIGNATURE = byteArrayOf(0x4E, 0x54, 0x4C, 0x4D, 0x53, 0x53, 0x50, 0) // "NTLMSSP\0"
    private val random by lazy { SecureRandom() }

    /** The parts of a server's Type2 message a Type3 needs. */
    class Challenge(val flags: Int, val serverChallenge: ByteArray, val targetInfo: ByteArray)

    /** The Type1 message: flags only, no domain or workstation. */
    fun type1(): ByteArray = ByteArray(32).also {
        SIGNATURE.copyInto(it)
        putInt(it, 8, 1)
        putInt(it, 12, TYPE1_FLAGS)
    }

    /** True when [message] starts with the NTLMSSP signature, whatever its type. */
    fun isNtlmMessage(message: ByteArray): Boolean =
        message.size >= SIGNATURE.size && SIGNATURE.indices.all { message[it] == SIGNATURE[it] }

    /** The server's challenge from a Type2 message, or null when [message] is not a well-formed Type2. */
    fun parseType2(message: ByteArray): Challenge? {
        if (message.size < 32 || !isNtlmMessage(message) || getInt(message, 8) != 2) return null
        val flags = getInt(message, 20)
        val serverChallenge = message.copyOfRange(24, 32)
        // Only a server that sets NEGOTIATE_TARGET_INFO has the field: older ones (and Squid's
        // ntlm_fake_auth) end the header at 40 and put the target name right there.
        var targetInfo = ByteArray(0)
        if (flags and NEGOTIATE_TARGET_INFO != 0) {
            if (message.size < 48) return null
            val length = getShort(message, 40)
            val offset = getInt(message, 44)
            if (length > 0) {
                if (offset < 0 || offset.toLong() + length > message.size) return null
                targetInfo = message.copyOfRange(offset, offset + length)
            }
        }
        return Challenge(flags, serverChallenge, targetInfo)
    }

    /**
     * The Type3 answering [challenge] for [user] in [domain] (empty: the server's own) with [password].
     */
    fun type3(challenge: Challenge, user: String, domain: String, password: String): ByteArray {
        val clientChallenge = ByteArray(8).also { random.nextBytes(it) }
        return type3(challenge, user, domain, password, clientChallenge, null)
    }

    /** [type3] with a fixed client challenge and, when [time] is given, a fixed FILETIME; for tests. */
    internal fun type3(
        challenge: Challenge, user: String, domain: String, password: String,
        clientChallenge: ByteArray, time: Long?,
    ): ByteArray {
        val serverTime = avTimestamp(challenge.targetInfo)
        val timestamp = time ?: serverTime ?: (System.currentTimeMillis() * 10000 + FILETIME_EPOCH_OFFSET)
        val responseKey = ntowfV2(password, user, domain)
        val nt = ntlmV2Response(responseKey, challenge.serverChallenge, clientChallenge, timestamp, challenge.targetInfo)
        // MS-NLMP 3.1.5.1.2: with a server timestamp the client sends Z(24) instead of the LMv2 response.
        val lm = if (serverTime != null) ByteArray(24) else lmV2Response(responseKey, challenge.serverChallenge, clientChallenge)

        val unicode = challenge.flags and NEGOTIATE_UNICODE != 0
        val charset = if (unicode) Charsets.UTF_16LE else Charsets.ISO_8859_1
        val domainBytes = domain.toByteArray(charset)
        val userBytes = user.toByteArray(charset)
        val flags = (challenge.flags and TYPE1_FLAGS) or NEGOTIATE_NTLM or
            (if (unicode) NEGOTIATE_UNICODE else NEGOTIATE_OEM)

        val headerSize = 64
        val out = ByteArray(headerSize + lm.size + nt.size + domainBytes.size + userBytes.size)
        SIGNATURE.copyInto(out)
        putInt(out, 8, 3)
        var offset = headerSize
        fun field(at: Int, bytes: ByteArray) {
            putShort(out, at, bytes.size)
            putShort(out, at + 2, bytes.size)
            putInt(out, at + 4, offset)
            bytes.copyInto(out, offset)
            offset += bytes.size
        }
        field(12, lm)
        field(20, nt)
        field(28, domainBytes)
        field(36, userBytes)
        field(44, ByteArray(0)) // workstation
        field(52, ByteArray(0)) // encrypted random session key: no key exchange
        putInt(out, 60, flags)
        return out
    }

    /**
     * Splits a typed user name into (domain, user): `DOMAIN\user` and `user@domain` name the domain,
     * a bare name leaves it empty.
     */
    fun splitUser(name: String): Pair<String, String> {
        val slash = name.indexOf('\\')
        if (slash >= 0) return name.substring(0, slash) to name.substring(slash + 1)
        val at = name.lastIndexOf('@')
        if (at >= 0) return name.substring(at + 1) to name.substring(0, at)
        return "" to name
    }

    /** NTOWFv2: HMAC-MD5 keyed with the MD4 password hash over Uppercase(user) + domain. */
    internal fun ntowfV2(password: String, user: String, domain: String): ByteArray =
        hmacMd5(Md4.digest(password.toByteArray(Charsets.UTF_16LE)), (user.uppercase() + domain).toByteArray(Charsets.UTF_16LE))

    /** NTProofStr followed by the blob it was computed over. */
    internal fun ntlmV2Response(
        responseKey: ByteArray, serverChallenge: ByteArray, clientChallenge: ByteArray, time: Long, targetInfo: ByteArray,
    ): ByteArray {
        val blob = ByteArray(28 + targetInfo.size + 4)
        blob[0] = 1
        blob[1] = 1
        putLong(blob, 8, time)
        clientChallenge.copyInto(blob, 16)
        targetInfo.copyInto(blob, 28)
        val proof = hmacMd5(responseKey, serverChallenge + blob)
        return proof + blob
    }

    internal fun lmV2Response(responseKey: ByteArray, serverChallenge: ByteArray, clientChallenge: ByteArray): ByteArray =
        hmacMd5(responseKey, serverChallenge + clientChallenge) + clientChallenge

    /** The MsvAvTimestamp value in a target info list, or null when there is none. */
    private fun avTimestamp(targetInfo: ByteArray): Long? {
        var i = 0
        while (i + 4 <= targetInfo.size) {
            val id = getShort(targetInfo, i)
            val length = getShort(targetInfo, i + 2)
            if (id == AV_EOL || i + 4 + length > targetInfo.size) return null
            if (id == AV_TIMESTAMP && length == 8) return getLong(targetInfo, i + 4)
            i += 4 + length
        }
        return null
    }

    private fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacMD5").run {
            init(SecretKeySpec(key, "HmacMD5"))
            doFinal(data)
        }

    private fun putShort(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte()
        b[at + 1] = (v ushr 8).toByte()
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun putLong(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun getShort(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun getInt(b: ByteArray, at: Int): Int = getShort(b, at) or (getShort(b, at + 2) shl 16)

    private fun getLong(b: ByteArray, at: Int): Long =
        (getInt(b, at).toLong() and 0xFFFFFFFFL) or (getInt(b, at + 4).toLong() shl 32)
}

/** MD4 (RFC 1320), which NTLM needs for the password hash and no standard JCA provider registers. */
internal object Md4 {
    fun digest(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val padded = ByteArray(((input.size + 8) / 64 + 1) * 64)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 8 + i] = (bitLength ushr (8 * i)).toByte()

        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476
        val x = IntArray(16)
        for (block in padded.indices step 64) {
            for (i in 0 until 16) {
                val p = block + i * 4
                x[i] = (padded[p].toInt() and 0xFF) or ((padded[p + 1].toInt() and 0xFF) shl 8) or
                    ((padded[p + 2].toInt() and 0xFF) shl 16) or ((padded[p + 3].toInt() and 0xFF) shl 24)
            }
            val aa = a; val bb = b; val cc = c; val dd = d

            fun f(x: Int, y: Int, z: Int) = (x and y) or (x.inv() and z)
            fun g(x: Int, y: Int, z: Int) = (x and y) or (x and z) or (y and z)
            fun h(x: Int, y: Int, z: Int) = x xor y xor z

            for (i in 0 until 4) {
                val k = i * 4
                a = Integer.rotateLeft(a + f(b, c, d) + x[k], 3)
                d = Integer.rotateLeft(d + f(a, b, c) + x[k + 1], 7)
                c = Integer.rotateLeft(c + f(d, a, b) + x[k + 2], 11)
                b = Integer.rotateLeft(b + f(c, d, a) + x[k + 3], 19)
            }
            for (i in 0 until 4) {
                a = Integer.rotateLeft(a + g(b, c, d) + x[i] + 0x5A827999, 3)
                d = Integer.rotateLeft(d + g(a, b, c) + x[i + 4] + 0x5A827999, 5)
                c = Integer.rotateLeft(c + g(d, a, b) + x[i + 8] + 0x5A827999, 9)
                b = Integer.rotateLeft(b + g(c, d, a) + x[i + 12] + 0x5A827999, 13)
            }
            for (i in intArrayOf(0, 2, 1, 3)) {
                a = Integer.rotateLeft(a + h(b, c, d) + x[i] + 0x6ED9EBA1, 3)
                d = Integer.rotateLeft(d + h(a, b, c) + x[i + 8] + 0x6ED9EBA1, 9)
                c = Integer.rotateLeft(c + h(d, a, b) + x[i + 4] + 0x6ED9EBA1, 11)
                b = Integer.rotateLeft(b + h(c, d, a) + x[i + 12] + 0x6ED9EBA1, 15)
            }
            a += aa; b += bb; c += cc; d += dd
        }
        val out = ByteArray(16)
        intArrayOf(a, b, c, d).forEachIndexed { n, v -> for (i in 0 until 4) out[n * 4 + i] = (v ushr (8 * i)).toByte() }
        return out
    }
}
