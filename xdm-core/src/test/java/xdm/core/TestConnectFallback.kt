// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import okhttp3.Dns
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.network.http.impl.HttpClientImpl
import java.net.InetAddress

/**
 * A host whose first resolved address never answers (one dead CDN node, as seen with
 * desktop.githubusercontent.com) is still reached through its next address, within the same call and
 * well before the 30 s connect timeout. With OkHttp 4 and retryOnConnectionFailure off, every attempt
 * went to the dead address again.
 */
class TestConnectFallback {
    private val server = MockHttpServer()

    /** TEST-NET-1 (RFC 5737): never routed, so a connect to it hangs or fails at once. */
    private val dead = InetAddress.getByName("192.0.2.1")
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val client = HttpClientImpl(2, dns = Dns { listOf(dead, loopback) })

    @AfterEach
    fun tearDown() {
        client.close()
        server.stop()
    }

    @Test
    fun deadFirstAddress_fallsBackToNextAddress() {
        val body = ByteArray(4096) { it.toByte() }
        val ep = server.register("/file.bin", Endpoint(body))
        val url = "http://fallback.test:${server.port}/file.bin"

        val start = System.nanoTime()
        val response = client.getResponse(url, null, null, null).getOrThrow()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val bytes = response.inputStream.use { it.readBytes() }
        response.close()

        assertEquals(200, response.statusCode, "status")
        assertArrayEquals(body, bytes, "body from the live address")
        assertEquals(1, ep.requests.size, "one request reached the server")
        assertTrue(elapsedMs < 5_000, "connected through the next address in ${elapsedMs} ms, not after the connect timeout")
    }
}
