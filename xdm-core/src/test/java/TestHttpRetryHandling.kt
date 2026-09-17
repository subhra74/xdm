import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.Range
import java.util.concurrent.TimeUnit

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
            "download hung after HTTP 429 (connections=${ep.connCount.get()}, " +
                "live chunk threads=${liveChunkThreads().size})",
            settled
        )
        assertNull("unexpected failure: ${host.failure}", host.failure)
        assertDownloaded(host, bytes)
        assertTrue("expected a retry after the 429", ep.connCount.get() >= 2)
    }

    @Test
    fun unexpectedException_failsDownloadInsteadOfHanging() {
        val throwing = object : PoolingHttpClient {
            override fun close() {}
            override fun getResponse(url: String, headers: HeaderMap?, cookie: String?, range: Range): Result<HttpResponse> =
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
                "chunk thread died on an unexpected exception without reporting a failure",
                host.latch.await(5, TimeUnit.SECONDS)
            )
            assertEquals(DownloadError.InternalError, host.failure)
        } finally {
            task.stop()
        }
    }

    @Test
    fun pauseDuringRetryWait_chunkThreadExitsPromptly() {
        val bytes = randomData(64 * 1024, seed = 42)
        // Every attempt is a retryable 503, so the chunk thread sits in its 5s retry wait.
        val ep = server.register("/retrywait", Endpoint(bytes, plan = { ConnPlan(failStatus = 503) }))
        val host = host()
        val task = newTask(id = 42, path = "/retrywait", host = host, maxSegments = 1)
        task.start()

        assertTrue("no connection attempt", waitFor(5000) { ep.connCount.get() >= 1 })
        Thread.sleep(300) // let the thread enter the retry wait
        task.stop()
        assertTrue("pause not acknowledged", host.pauseLatch.await(5, TimeUnit.SECONDS))

        assertTrue(
            "chunk thread kept sleeping through the retry delay after Pause",
            waitFor(1500) { liveChunkThreads().isEmpty() }
        )
        assertEquals("no request expected after Pause", 1, ep.connCount.get())
    }
}
