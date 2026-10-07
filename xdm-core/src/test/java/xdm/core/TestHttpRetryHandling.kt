package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.readContext
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.Range
import xdm.core.util.AtomicIO
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression for CODE_REVIEW B2: retry handling in `HttpChunkRetriever`.
 *
 * - A 429 used to throw out of `connect()`, killing the chunk thread and leaving the download
 *   hung with no error; it must honour Retry-After and retry.
 * - Any unexpected exception in the chunk thread must fail the chunk, not orphan it.
 * - The retry wait must notice Pause instead of sleeping the whole delay.
 */
class TestHttpRetryHandling : HttpDownloadTestBase() {

    /** Stack frames of live chunk threads, i.e. threads still inside `retrieveChunk`. */
    private fun liveChunkThreads(): List<Thread> =
        Thread.getAllStackTraces().entries
            .filter { (_, st) -> st.any { it.methodName == "retrieveChunk" } }
            .map { it.key }

    @Test
    fun rateLimited429_honoursRetryAfterAndCompletes() {
        val bytes = randomData(256 * 1024, seed = 40)
        val ep = server.register("/limited429", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(failStatus = 429, retryAfterSecs = 1) else ConnPlan()
        }))
        val host = host()
        newTask(id = 40, path = "/limited429", host = host, maxSegments = 1).start()

        val settled = host.latch.await(15, TimeUnit.SECONDS)
        assertTrue(
            settled
        , "download hung after HTTP 429 (connections=${ep.connCount.get()}, " +
                "live chunk threads=${liveChunkThreads().size})")
        assertNull(host.failure, "unexpected failure: ${host.failure}")
        assertDownloaded(host, bytes)
        assertTrue(ep.connCount.get() >= 2, "expected a retry after the 429")
    }

    @Test
    fun unexpectedException_failsDownloadInsteadOfHanging() {
        val throwing = object : PoolingHttpClient {
            override fun close() {}
            override fun getResponse(url: String, headers: HeaderMap?, cookie: String?, range: Range?): Result<HttpResponse> =
                throw IllegalStateException("boom")
        }
        val host = host()
        val info = HttpDownloadTaskInfo(
            id = 41, url = "http://127.0.0.1:1/unused", fileName = "file-41.bin", respectFileName = false,
            cookie = null, headers = null, origin = null, autoCategorize = false,
            defaultDownloadFolder = tmpDir.absolutePath, userSelectedDownloadFolder = null,
            maxPiece = 0, authInfo = null, knownFileSize = null,
        )
        val task = HttpDownloaderTask(info, host, throwing, work.absolutePath, TestConfig(maxSegments = 1))
        try {
            task.start()
            assertTrue(
                host.latch.await(5, TimeUnit.SECONDS)
            , "chunk thread died on an unexpected exception without reporting a failure")
            assertEquals(DownloadError.InternalError, host.failure)
        } finally {
            task.stop()
        }
    }

    private class SimulatedError : Error("simulated")

    /** Like an unexpected exception, an Error (out of memory, stack overflow) must fail the download, not hang it. */
    @Test
    fun unexpectedError_failsDownloadInsteadOfHanging() {
        val throwing = object : PoolingHttpClient {
            override fun close() {}
            override fun getResponse(url: String, headers: HeaderMap?, cookie: String?, range: Range?): Result<HttpResponse> =
                throw SimulatedError()
        }
        val host = host()
        val info = HttpDownloadTaskInfo(
            id = 43, url = "http://127.0.0.1:1/unused", fileName = "file-43.bin", respectFileName = false,
            cookie = null, headers = null, origin = null, autoCategorize = false,
            defaultDownloadFolder = tmpDir.absolutePath, userSelectedDownloadFolder = null,
            maxPiece = 0, authInfo = null, knownFileSize = null,
        )
        val task = HttpDownloaderTask(info, host, throwing, work.absolutePath, TestConfig(maxSegments = 1))
        try {
            task.start()
            assertTrue(
                host.latch.await(5, TimeUnit.SECONDS),
                "chunk thread died on an Error without reporting a failure"
            )
            assertEquals(DownloadError.InternalError, host.failure)
        } finally {
            task.stop()
        }
    }

    /**
     * The link redirects to a signed URL that works only once, as a short-lived signed URL stops working
     * once it expires. After the connection drops, the retry must go back through the original link for
     * a fresh one, and the saved state must keep the original link rather than the expired target.
     */
    @Test
    fun expiredRedirectTarget_isResolvedAgain() {
        val bytes = randomData(1024 * 1024, seed = 44)
        val signature = AtomicInteger(0)
        val used = ConcurrentHashMap.newKeySet<String>()
        server.register("/signed", Endpoint(bytes, plan = { ctx ->
            val sig = ctx.uri.substringAfter("sig=")
            when {
                !used.add(sig) -> ConnPlan(failStatus = 403)
                sig == "1" -> ConnPlan(truncateAfterBytes = 256 * 1024)
                else -> ConnPlan()
            }
        }))
        server.redirect("/link") { "/signed?sig=${signature.incrementAndGet()}" }
        val host = host()
        newTask(id = 44, path = "/link", host = host, maxSegments = 1).start()

        awaitSuccess(host, timeoutSec = 30)
        assertDownloaded(host, bytes)
        val saved = AtomicIO.readTransacted("44.state", work.absolutePath) { readContext(it, host) }.getOrThrow()
        assertEquals(server.url("/link"), saved.url, "the saved state keeps the expired redirect target")
    }

    @Test
    fun pauseDuringRetryWait_chunkThreadExitsPromptly() {
        val bytes = randomData(64 * 1024, seed = 42)
        // Every attempt is a retryable 503, so the chunk thread sits in its 5s retry wait.
        val ep = server.register("/retrywait", Endpoint(bytes, plan = { ConnPlan(failStatus = 503) }))
        val host = host()
        val task = newTask(id = 42, path = "/retrywait", host = host, maxSegments = 1)
        task.start()

        assertTrue(waitFor(5000) { ep.connCount.get() >= 1 }, "no connection attempt")
        Thread.sleep(300) // let the thread enter the retry wait
        task.stop()
        assertTrue(host.pauseLatch.await(5, TimeUnit.SECONDS), "pause not acknowledged")

        assertTrue(
            waitFor(1500) { liveChunkThreads().isEmpty() }
        , "chunk thread kept sleeping through the retry delay after Pause")
        assertEquals(1, ep.connCount.get(), "no request expected after Pause")
    }
}
