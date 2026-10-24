// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.core.checksum.ChecksumAlgorithm
import xdm.core.checksum.ChecksumParser
import xdm.core.checksum.ChecksumResult
import xdm.core.checksum.ChecksumSource
import xdm.core.checksum.ChecksumVerifier
import xdm.core.checksum.ExpectedChecksum
import xdm.core.checksum.NotFoundReason
import xdm.core.checksum.SidecarUrls
import xdm.core.network.http.impl.HttpClientImpl
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Sidecar URL conventions, the checksum file formats in the wild, and verification end to end. */
class TestChecksum {
    private val content = "hello checksum\n".toByteArray()
    private val sha256 = digest("SHA-256")
    private val md5 = digest("MD5")

    private lateinit var dir: File
    private lateinit var file: File
    private val server = MockHttpServer()
    private val client = HttpClientImpl(2)

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-checksum").toFile()
        file = File(dir, "app.bin").apply { writeBytes(content) }
    }

    @AfterEach
    fun tearDown() {
        client.close()
        server.stop()
        dir.deleteRecursively()
    }

    private fun digest(name: String) =
        MessageDigest.getInstance(name).digest(content).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun verify(input: String, algorithm: ChecksumAlgorithm?, source: ChecksumSource?, probe: Boolean) =
        ChecksumVerifier(client).verify(
            file, input, algorithm, source, listOf("app.bin"), probe, object : ChecksumVerifier.Progress {}
        )

    @Test
    fun sidecarUrlDropsQueryAndFragment() {
        assertEquals(
            "https://h.example/dl/app.bin.sha256",
            SidecarUrls.sidecar("https://h.example/dl/app.bin?token=abc#x", ChecksumAlgorithm.SHA256),
            "query and fragment are not part of the sidecar URL"
        )
        assertNull(SidecarUrls.sidecar("https://h.example/", ChecksumAlgorithm.MD5), "no file path, no sidecar")
        assertEquals(
            "https://h.example/dl/SHA256SUMS",
            SidecarUrls.autoCandidates("https://h.example/dl/app.bin").last(),
            "auto tries the folder manifest last"
        )
        assertEquals("my file.iso", SidecarUrls.remoteFileName("https://h/a/my%20file.iso?x=1"), "name is decoded")
    }

    @Test
    fun parsesCommonFormats() {
        val names = listOf("app.bin")
        val expected = ExpectedChecksum(ChecksumAlgorithm.SHA256, sha256)
        assertEquals(expected, ChecksumParser.parse(sha256.uppercase() + "\n", names), "bare hash, any case")
        assertEquals(expected, ChecksumParser.parse("$sha256  app.bin\n", names), "GNU text mode")
        assertEquals(expected, ChecksumParser.parse("$sha256 *app.bin\n", names), "GNU binary mode")
        assertEquals(expected, ChecksumParser.parse("SHA256 (app.bin) = $sha256\n", names), "BSD tag")
        assertEquals(expected, ChecksumParser.parse("﻿$sha256  ./dist/app.bin\r\n", names), "BOM, CRLF, path")
        val gpg = "app.bin: " + sha256.uppercase().chunked(8).joinToString(" ").let {
            it.substring(0, 40) + "\n        " + it.substring(40)
        }
        assertEquals(expected, ChecksumParser.parse(gpg, names), "gpg --print-md, wrapped")
    }

    @Test
    fun manifestNeedsNameMatchWhenSeveralEntries() {
        val other = "0".repeat(64)
        val sums = "$other  other.bin\n$sha256  app.bin\n"
        assertEquals(sha256, ChecksumParser.parse(sums, listOf("app.bin"))?.hex, "picks the matching line")
        assertNull(ChecksumParser.parse(sums, listOf("missing.bin")), "no line for this file")
        assertEquals(
            sha256, ChecksumParser.parse("$sha256  original-name.bin", listOf("app.bin"))?.hex,
            "a single-entry sidecar is taken whatever name it gives"
        )
        assertNull(ChecksumParser.parse("$md5  app.bin", listOf("app.bin"), ChecksumAlgorithm.SHA256), "hint filters")
    }

    @Test
    fun pastedHashSkipsTheNetwork() {
        assertInstanceOf(ChecksumResult.Match::class.java, verify(md5, null, null, probe = false), "MD5 detected by length")
        val bad = verify("f".repeat(64), null, null, probe = false)
        assertInstanceOf(ChecksumResult.Mismatch::class.java, bad, "wrong hash is a mismatch")
        assertEquals(sha256, (bad as ChecksumResult.Mismatch).actualHex, "actual hash reported")
        val notHash = verify("not a hash", null, null, probe = false) as ChecksumResult.NotFound
        assertEquals(NotFoundReason.BAD_INPUT, notHash.reason, "neither URL nor hash")
    }

    @Test
    fun autoProbesSidecarsAndFolderManifest() {
        // Only the folder manifest exists: the per-file sidecars all 404.
        server.register("/rel/SHA256SUMS", Endpoint("${"1".repeat(64)}  x.bin\n$sha256  app.bin\n".toByteArray()))
        val source = ChecksumSource(server.url("/rel/app.bin?sig=1"), null, null)
        val auto = SidecarUrls.sidecar(source.url, ChecksumAlgorithm.SHA256)!!
        val result = verify(auto, null, source, probe = true)
        assertInstanceOf(ChecksumResult.Match::class.java, result, "found in SHA256SUMS")
    }

    @Test
    fun explicitUrlReportsHttpError() {
        val source = ChecksumSource(server.url("/none/app.bin"), null, null)
        val result = verify(server.url("/none/app.bin.sha512"), ChecksumAlgorithm.SHA512, source, probe = false)
        assertEquals(
            ChecksumResult.NotFound(NotFoundReason.HTTP_ERROR, "404", server.url("/none/app.bin.sha512")), result,
            "a single URL's failure is reported as is"
        )
        val probed = verify(server.url("/none/app.bin.sha256"), null, source, probe = true) as ChecksumResult.NotFound
        assertEquals(NotFoundReason.NO_SIDECAR, probed.reason, "all candidates missing")
    }

    @Test
    fun credentialsOnlyGoToTheSameHost() {
        val sidecar = server.register("/c/app.bin.md5", Endpoint("$md5\n".toByteArray()))
        val headers = mapOf("Referer" to listOf("https://page"), "Range" to listOf("bytes=0-"))
        val same = ChecksumSource(server.url("/c/app.bin"), headers, "sid=1")
        assertInstanceOf(ChecksumResult.Match::class.java, verify(server.url("/c/app.bin.md5"), null, same, false), "match")
        val req = sidecar.requests.last().headers
        assertEquals(listOf("sid=1"), req["cookie"], "cookie sent to the download's host")
        assertNull(req["range"], "file request headers are dropped")

        // Same server reached under another host name: nothing from the download goes along.
        val other = ChecksumSource("http://localhost:${server.port}/c/app.bin", headers, "sid=1")
        verify(server.url("/c/app.bin.md5"), null, other, false)
        val req2 = sidecar.requests.last().headers
        assertTrue(req2["cookie"] == null && req2["referer"] == null, "no cookie or headers to another host")
    }

    @Test
    fun networkErrorIsRetriedOnce() {
        // The first connection drops before the body: one transient failure must not fail the check.
        val sidecar = server.register("/r/app.bin.sha256", Endpoint("$sha256\n".toByteArray()) {
            if (it.connIndex == 1) ConnPlan(truncateAfterBytes = 0) else ConnPlan()
        })
        val source = ChecksumSource(server.url("/r/app.bin"), null, null)
        val result = verify(SidecarUrls.sidecar(source.url, ChecksumAlgorithm.SHA256)!!, null, source, probe = true)
        assertEquals(
            ChecksumResult.Match(ExpectedChecksum(ChecksumAlgorithm.SHA256, sha256), server.url("/r/app.bin.sha256")),
            result, "matched on the retry"
        )
        assertEquals(2, sidecar.connCount.get(), "one retry")
    }

    @Test
    fun probingDoesNotHideTheRealFailure() {
        // .sha256 keeps failing; the remaining candidates 404. The failure, not "no sidecar", is shown.
        server.register("/f/app.bin.sha256", Endpoint("$sha256\n".toByteArray()) { ConnPlan(truncateAfterBytes = 0) })
        val source = ChecksumSource(server.url("/f/app.bin"), null, null)
        val result = verify(SidecarUrls.sidecar(source.url, ChecksumAlgorithm.SHA256)!!, null, source, probe = true)
        result as ChecksumResult.NotFound
        assertEquals(NotFoundReason.NETWORK_ERROR, result.reason, "network error wins over later 404s")
        assertEquals(server.url("/f/app.bin.sha256"), result.from, "names the URL that failed")

        // A served file without this file's hash also beats the 404s.
        server.register("/g/app.bin.sha256", Endpoint("${"2".repeat(64)}  other.bin\n${"3".repeat(64)}  x.bin\n".toByteArray()))
        val g = ChecksumSource(server.url("/g/app.bin"), null, null)
        val noHash = verify(SidecarUrls.sidecar(g.url, ChecksumAlgorithm.SHA256)!!, null, g, probe = true)
        assertEquals(NotFoundReason.NO_HASH_FOR_FILE, (noHash as ChecksumResult.NotFound).reason, "file found, no line")
    }
}
