// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.core.network.http.Md4
import xdm.core.network.http.Ntlm

/** NTLMv2 messages against RFC 1320 (MD4) and the MS-NLMP §4.2.4 NTLMv2 test vectors. */
class TestNtlm {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // MS-NLMP 4.2.4: user "User", domain "Domain", password "Password", time 0, client challenge aa * 8.
    private val serverChallenge = hex("0123456789abcdef")
    private val clientChallenge = ByteArray(8) { 0xaa.toByte() }
    private val targetInfo = hex(
        "02000c0044006f006d00610069006e00" + // MsvAvNbDomainName "Domain"
            "01000c00530065007200760065007200" + // MsvAvNbComputerName "Server"
            "00000000", // MsvAvEOL
    )

    @Test
    fun md4_rfc1320Vectors() {
        assertArrayEquals(hex("31d6cfe0d16ae931b73c59d7e0c089c0"), Md4.digest(ByteArray(0)), "MD4(\"\")")
        assertArrayEquals(hex("a448017aaf21d8525fc10ae87aa6729d"), Md4.digest("abc".toByteArray()), "MD4(abc)")
        assertArrayEquals(hex("d9130a8164549fe818874806e1c7014b"), Md4.digest("message digest".toByteArray()), "MD4(message digest)")
        val long = "1234567890".repeat(8).toByteArray()
        assertArrayEquals(hex("e33b4ddc9c38f2199c3e7b164fcc0536"), Md4.digest(long), "MD4 over two blocks")
    }

    @Test
    fun ntowfV1_passwordHash() {
        assertArrayEquals(hex("a4f49c406510bdcab6824ee7c30fd852"), Md4.digest("Password".toByteArray(Charsets.UTF_16LE)), "NTOWFv1")
    }

    @Test
    fun ntlmV2_specVectors() {
        val key = Ntlm.ntowfV2("Password", "User", "Domain")
        assertArrayEquals(hex("0c868a403bfd7a93a3001ef22ef02e3f"), key, "NTOWFv2")

        val lm = Ntlm.lmV2Response(key, serverChallenge, clientChallenge)
        assertArrayEquals(hex("86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa"), lm, "LMv2 response")

        val nt = Ntlm.ntlmV2Response(key, serverChallenge, clientChallenge, 0L, targetInfo)
        assertArrayEquals(hex("68cd0ab851e51c96aabc927bebef6a1c"), nt.copyOf(16), "NTProofStr")
    }

    @Test
    fun type3_carriesSpecResponsesAndNames() {
        val challenge = Ntlm.Challenge(0x00000001 or 0x00000200, serverChallenge, targetInfo)
        val msg = Ntlm.type3(challenge, "User", "Domain", "Password", clientChallenge, 0L)

        fun field(at: Int): ByteArray {
            val len = (msg[at].toInt() and 0xFF) or ((msg[at + 1].toInt() and 0xFF) shl 8)
            val off = (msg[at + 4].toInt() and 0xFF) or ((msg[at + 5].toInt() and 0xFF) shl 8)
            return msg.copyOfRange(off, off + len)
        }
        assertEquals(3, msg[8].toInt(), "message type")
        assertArrayEquals(hex("86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa"), field(12), "LM field")
        assertArrayEquals(hex("68cd0ab851e51c96aabc927bebef6a1c"), field(20).copyOf(16), "NT field proof")
        assertEquals("Domain", String(field(28), Charsets.UTF_16LE), "domain field")
        assertEquals("User", String(field(36), Charsets.UTF_16LE), "user field")
    }

    @Test
    fun type3_serverTimestamp_zeroesLmResponse() {
        val withTime = hex("07000800") + ByteArray(8) { 1 } + hex("00000000")
        val msg = Ntlm.type3(Ntlm.Challenge(1, serverChallenge, withTime), "u", "", "p", clientChallenge, null)
        val off = (msg[16].toInt() and 0xFF) or ((msg[17].toInt() and 0xFF) shl 8)
        assertArrayEquals(ByteArray(24), msg.copyOfRange(off, off + 24), "LM response should be Z(24)")
    }

    @Test
    fun parseType2_rejectsMalformed() {
        assertNull(Ntlm.parseType2(Ntlm.type1()), "a Type1 is not a Type2")
        assertNull(Ntlm.parseType2(ByteArray(40)), "no signature")
        val type2 = ByteArray(48).also {
            Ntlm.type1().copyOf(8).copyInto(it)
            it[8] = 2
            it[22] = 0x80.toByte() // NEGOTIATE_TARGET_INFO
            it[40] = 100 // target info longer than the message
            it[44] = 48
        }
        assertNull(Ntlm.parseType2(type2), "target info out of bounds")
        type2[40] = 0
        assertNotNull(Ntlm.parseType2(type2), "empty target info is fine")
    }

    @Test
    fun parseType2_withoutTargetInfo_squidFakeAuth() {
        // Squid's ntlm_fake_auth: a 40-byte header, no NEGOTIATE_TARGET_INFO, the target name at 40.
        val type2 = java.util.Base64.getDecoder().decode("TlRMTVNTUAACAAAACQAJAAMAAAAGgggAYDpq9vYxfPwAAAAAAAA6AFdPUktHUk9VUA==")
        val challenge = Ntlm.parseType2(type2)
        assertNotNull(challenge, "a Type2 without target info must parse")
        assertArrayEquals(hex("603a6af6f6317cfc"), challenge!!.serverChallenge, "server challenge")
        assertEquals(0, challenge.targetInfo.size, "no target info")
    }

    @Test
    fun splitUser_domainForms() {
        assertEquals("CORP" to "alice", Ntlm.splitUser("CORP\\alice"), "DOMAIN\\user")
        assertEquals("corp.example" to "alice", Ntlm.splitUser("alice@corp.example"), "user@domain")
        assertEquals("" to "alice", Ntlm.splitUser("alice"), "bare user")
    }
}
