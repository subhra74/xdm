package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.ManifestUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A manifest is fetched the way a browser fetches it: no `Range` header. Some CDNs reject a ranged
 * playlist request outright (400 over HTTP/2), so the replay must not add one.
 */
class TestManifestFetch {
    private val server = MockHttpServer()
    private val client = HttpClientImpl(2)

    @AfterEach
    fun tearDown() {
        client.close()
        server.stop()
    }

    @Test
    fun manifestFetch_sendsNoRangeHeader() {
        val body = "#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray()
        val ep = server.register("/master.m3u8", Endpoint(body))

        val bytes = ManifestUtils.downloadManifestBytes(
            client, server.url("/master.m3u8"), mapOf("Referer" to listOf("https://example.com/")), null,
            AtomicBoolean(false)
        )

        assertArrayEquals(body, bytes, "manifest body")
        assertEquals(1, ep.requests.size, "one request")
        assertFalse(ep.requests[0].hasRange, "manifest request must not carry a Range header")
        assertEquals(listOf("https://example.com/"), ep.requests[0].headers["referer"], "browser headers replayed")
    }
}
