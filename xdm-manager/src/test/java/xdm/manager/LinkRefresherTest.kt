// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.network.http.impl.HttpClientImpl
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LinkRefresher]: when it may refresh a download again (only after progress), and what it accepts from the
 * source link (a redirect to the same file: same size and Last-Modified), against a local server whose link
 * hands out a new signed file URL each time.
 */
class LinkRefresherTest {
    private lateinit var server: HttpServer
    private val size = 1000L
    private val modified = "Tue, 10 Nov 2026 10:00:00 GMT"
    private val signature = AtomicInteger(0)
    private val linkCookies = CopyOnWriteArrayList<String?>()

    @Volatile private var fileSize = size
    @Volatile private var fileModified = modified
    @Volatile private var linkStatus = 302

    @BeforeEach
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/link") { ex ->
            linkCookies.add(ex.requestHeaders.getFirst("Cookie"))
            if (linkStatus == 302) ex.responseHeaders.add("Location", "/file?sig=${signature.incrementAndGet()}")
            ex.sendResponseHeaders(linkStatus, -1)
            ex.close()
        }
        server.createContext("/file") { ex ->
            ex.responseHeaders.add("Content-Range", "bytes 0-0/$fileSize")
            ex.responseHeaders.add("Last-Modified", fileModified)
            ex.sendResponseHeaders(206, 1)
            ex.responseBody.use { it.write(0) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun task() = HttpDownloadTaskInfo(
        id = 1, url = url("/file?sig=0"), fileName = "f.bin", respectFileName = false,
        cookie = "cdn=1", headers = mapOf("Referer" to listOf("https://site/page"), "Cookie" to listOf("cdn=2")),
        origin = null, categoryId = null, targetFolder = "/tmp", maxPiece = 8, authInfo = null, knownFileSize = size,
        sourceUrl = url("/link"), sourceCookie = "session=abc",
    )

    private fun refresher(clock: () -> Long = System::currentTimeMillis) = LinkRefresher({ HttpClientImpl(1) }, clock)

    @Test
    fun resolve_followsTheSourceLinkToAFreshUrl() {
        val fresh = refresher().resolve(task(), size, modified)
        assertEquals(url("/file?sig=1"), fresh, "the link's redirect target is the fresh URL")
        assertEquals(listOf<String?>("session=abc"), linkCookies, "only the source host's cookies go to the link")
    }

    @Test
    fun resolve_refusesAnotherFile() {
        fileSize = size + 1
        assertNull(refresher().resolve(task(), size, modified), "a different size is another file")
        fileSize = size
        fileModified = "Wed, 11 Nov 2026 10:00:00 GMT"
        assertNull(refresher().resolve(task(), size, modified), "a different Last-Modified is another file")
        assertEquals(url("/file?sig=3"), refresher().resolve(task(), size, null), "no validator saved: size decides")
    }

    @Test
    fun resolve_givesNothingWhenTheLinkIsRefused() {
        linkStatus = 403
        assertNull(refresher().resolve(task(), size, modified), "a refused source link")
    }

    @Test
    fun claim_needsProgressAndTimeSinceTheLastRefresh() {
        var now = 1_000_000L
        val r = refresher { now }
        assertTrue(r.claim(1, 100), "the first refresh")
        now += LinkRefresher.MIN_INTERVAL_MS * 10
        assertFalse(r.claim(1, 100), "no progress since the last refresh")
        assertTrue(r.claim(1, 200), "progress since the last refresh")
        now += LinkRefresher.MIN_INTERVAL_MS - 1
        assertFalse(r.claim(1, 300), "progress, but too soon after the last refresh")
        now += 1
        assertTrue(r.claim(1, 300), "progress, and long enough after it")
        assertTrue(r.claim(2, 0), "each download on its own")
        r.forget(1)
        assertTrue(r.claim(1, 300), "forgotten, e.g. after a refresh by hand")
    }
}
