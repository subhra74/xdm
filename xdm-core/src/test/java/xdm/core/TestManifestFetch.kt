package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.ManifestUtils
import java.io.File
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

    /** Manifest temp files currently in the JVM temp folder (where [ManifestUtils] creates them). */
    private fun manifestTempFiles(): Set<String> =
        File(System.getProperty("java.io.tmpdir")).list { _, n -> n.endsWith(".m3u8") }?.toSet() ?: emptySet()

    @Test
    fun manifestFetch_stoppedMidBody_returnsNullAndLeavesNoFile() {
        val body = ("#EXTM3U\n" + "#EXTINF:1.0,\nseg.ts\n".repeat(2000)).toByteArray()
        server.register("/stall.m3u8", Endpoint(body, plan = { ConnPlan(stallAfterBytes = 1024, stallMs = 1500) }))
        val before = manifestTempFiles()
        val stop = AtomicBoolean(false)
        val stopper = Thread { Thread.sleep(300); stop.set(true) }.apply { start() }

        val path = ManifestUtils.downloadManifestAsFile(client, server.url("/stall.m3u8"), null, null, stop)
        stopper.join()

        assertNull(path, "a stopped fetch must not return a partial manifest")
        assertEquals(before, manifestTempFiles(), "partial manifest temp file left behind")
    }

    @Test
    fun manifestFetch_connectionCutMidBody_returnsNullAndLeavesNoFile() {
        val body = ("#EXTM3U\n" + "#EXTINF:1.0,\nseg.ts\n".repeat(2000)).toByteArray()
        server.register("/cut.m3u8", Endpoint(body, plan = { ConnPlan(truncateAfterBytes = 1024) }))
        val before = manifestTempFiles()

        val path = ManifestUtils.downloadManifestAsFile(client, server.url("/cut.m3u8"), null, null, AtomicBoolean(false))

        assertNull(path, "a cut-off fetch must not return a partial manifest")
        assertEquals(before, manifestTempFiles(), "partial manifest temp file left behind")
    }
}
