// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.ManifestUtils
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manifests are fetched in the background for any page that plays media, so their size is not left
 * to the server: a body past the limit is abandoned, reported through onError, and leaves no temp file.
 */
class TestManifestSizeLimit {
    private val server = MockHttpServer()
    private val client = HttpClientImpl(2)

    @AfterEach
    fun tearDown() {
        client.close()
        server.stop()
    }

    private fun manifestTempFiles(): Set<String> =
        File(System.getProperty("java.io.tmpdir")).list { _, n -> n.endsWith(".m3u8") }?.toSet() ?: emptySet()

    private fun fetch(path: String, maxBytes: Long): Pair<ByteArray?, Throwable?> {
        var error: Throwable? = null
        val bytes = ManifestUtils.downloadManifestBytes(
            client, server.url(path), null, null, AtomicBoolean(false), onError = { error = it }, maxBytes = maxBytes,
        )
        return bytes to error
    }

    @Test
    fun bodyOverTheLimit_isAbandoned() {
        server.register("/big.m3u8", Endpoint(ByteArray(64 * 1024) { 0x41 }))
        val before = manifestTempFiles()
        val (bytes, error) = fetch("/big.m3u8", 1024)
        assertNull(bytes, "an oversized manifest must not be returned")
        assertTrue(error is ManifestUtils.ManifestTooLargeException, "expected a size error, got $error")
        assertEquals(before, manifestTempFiles(), "partial manifest temp file left behind")
    }

    /** No Content-Length (chunked, or a decoded gzip body): the limit is counted while reading. */
    @Test
    fun chunkedBodyOverTheLimit_isAbandonedWhileReading() {
        server.register("/chunked.m3u8", Endpoint(ByteArray(64 * 1024) { 0x41 }, plan = { ConnPlan(omitContentLength = true) }))
        val before = manifestTempFiles()
        val (bytes, error) = fetch("/chunked.m3u8", 1024)
        assertNull(bytes, "an oversized manifest must not be returned")
        assertTrue(error is ManifestUtils.ManifestTooLargeException, "expected a size error, got $error")
        assertEquals(before, manifestTempFiles(), "partial manifest temp file left behind")
    }

    @Test
    fun bodyAtTheLimit_isRead() {
        val body = ByteArray(1024) { 0x41 }
        server.register("/exact.m3u8", Endpoint(body))
        val (bytes, error) = fetch("/exact.m3u8", 1024)
        assertArrayEquals(body, bytes, "a body of exactly the limit is accepted")
        assertNull(error)
    }

    @Test
    fun ordinaryManifest_isReadWithTheDefaultLimit() {
        val body = ("#EXTM3U\n" + "#EXTINF:2.0,\nseg.ts\n".repeat(50_000) + "#EXT-X-ENDLIST\n").toByteArray()
        server.register("/ok.m3u8", Endpoint(body))
        val (bytes, error) = fetch("/ok.m3u8", ManifestUtils.MAX_MANIFEST_BYTES)
        assertArrayEquals(body, bytes, "a ~1 MB playlist is well within the limit")
        assertNull(error)
    }
}
