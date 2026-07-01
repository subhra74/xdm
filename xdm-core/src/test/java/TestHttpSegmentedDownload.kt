import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.DownloadError

/**
 * End-to-end tests for the segmented HTTP downloader ([xdm.core.downloaders.web.http.HttpDownloaderTask])
 * driven against an in-process [MockHttpServer]: single/multi-segment downloads, odd sizes,
 * no-resume streaming, and failure/retry handling. The race/stall behaviour lives in
 * [TestHttpSegmentedDownloadRace] (separate class -> separate JVM fork).
 */
class TestHttpSegmentedDownload : HttpDownloadTestBase() {

    // ---------------------------------------------------------------------------------------
    // Segmented download correctness
    // ---------------------------------------------------------------------------------------

    @Test
    fun singleSegment_resumable_downloadsExactBytes() {
        val bytes = randomData(200 * 1024, seed = 1)
        val ep = server.register("/single", Endpoint(bytes))
        val host = download(id = 1, path = "/single", maxSegments = 1)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
        assertEquals(bytes.size.toLong(), host.success!!.fileSize)
        assertTrue("expected at least one request", ep.connCount.get() >= 1)
    }

    @Test
    fun multiSegment_parallel_downloadsExactBytesUsingMultipleConnections() {
        val bytes = randomData(4 * 1024 * 1024, seed = 2)
        // Mild throttle so the split cascade actually opens several parallel connections.
        val ep = server.register("/multi", Endpoint(bytes, plan = {
            ConnPlan(throttleChunk = 64 * 1024, throttleSleepMs = 1)
        }))
        val host = download(id = 2, path = "/multi", maxSegments = 6)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
        assertTrue(
            "expected multiple segment connections, saw ${ep.connCount.get()}",
            ep.connCount.get() >= 2
        )
        assertTrue("expected >1 segment in progress", host.maxSegmentsSeen.get() >= 2)
    }

    @Test
    fun multiSegment_oddSize_boundariesAreExact() {
        // A prime-ish size that will not divide evenly across segments, stressing split math.
        val bytes = randomData(3_000_001, seed = 3)
        server.register("/odd", Endpoint(bytes))
        val host = download(id = 3, path = "/odd", maxSegments = 5)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
    }

    @Test
    fun smallFile_belowSplitThreshold_staysSingleConnection() {
        val bytes = randomData(100 * 1024, seed = 4) // < 256KB split threshold
        val ep = server.register("/small", Endpoint(bytes))
        val host = download(id = 4, path = "/small", maxSegments = 8)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
        assertEquals("small file should not be split", 1, ep.connCount.get())
    }

    @Test
    fun initCallback_reportsFileSizeAndType() {
        val bytes = randomData(512 * 1024, seed = 5)
        server.register("/init", Endpoint(bytes))
        val host = download(id = 5, path = "/init", maxSegments = 4)

        awaitSuccess(host)
        assertNotNull("init callback should fire", host.initInfo)
        assertEquals(bytes.size.toLong(), host.initInfo!!.fileSize)
    }

    @Test
    fun noResume_chunkedStream_downloadsWholeFile() {
        val bytes = randomData(700 * 1024, seed = 6)
        // Server ignores Range and streams with unknown length -> single-stream path.
        server.register("/noresume", Endpoint(bytes, resumeSupported = false, plan = {
            ConnPlan(ignoreRange = true, omitContentLength = true)
        }))
        val host = download(id = 6, path = "/noresume", maxSegments = 8)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
    }

    // ---------------------------------------------------------------------------------------
    // Failures & retries
    // ---------------------------------------------------------------------------------------

    @Test
    fun fatalServerError_failsDownload() {
        val bytes = randomData(64 * 1024, seed = 7)
        server.register("/err", Endpoint(bytes, plan = { ConnPlan(failStatus = 500) }))
        val host = download(id = 7, path = "/err", maxSegments = 1)

        awaitDone(host)
        assertNull(host.success)
        assertEquals(DownloadError.InvalidResponse, host.failure)
    }

    @Test
    fun transientServerError_retriesAndCompletes() {
        val bytes = randomData(512 * 1024, seed = 8)
        // First connection returns a retryable 503; the retry succeeds.
        server.register("/transient", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(failStatus = 503) else ConnPlan()
        }))
        val host = download(id = 8, path = "/transient", maxSegments = 1)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
    }

    // ---------------------------------------------------------------------------------------
    // Pause & resume
    // ---------------------------------------------------------------------------------------

    @Test
    fun pauseThenResume_completesWithExactBytes() {
        val bytes = randomData(4 * 1024 * 1024, seed = 9)
        // Throttle hard so the download is still running when we pause (~1.9s/segment).
        server.register("/pause", Endpoint(bytes, plan = {
            ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 30)
        }))

        val host1 = host()
        val task1 = newTask(id = 9, path = "/pause", host = host1, maxSegments = 4)
        task1.start()
        // Let some data flow, then pause while still in flight.
        Thread.sleep(500)
        task1.stop()
        assertTrue("pause callback should fire", host1.pauseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(host1.paused)
        assertNull("download should not have completed before pause", host1.success)

        // Resume in a fresh task instance (as the app does), loading the saved .state.
        val host2 = host()
        val task2 = newTask(id = 9, path = "/pause", host = host2, maxSegments = 4)
        task2.resume()
        awaitSuccess(host2)
        assertDownloaded(host2, bytes)
    }

    /**
     * A server that advertises a Content-Length but ignores Range requests (returns 200 with the
     * full body) still completes correctly. The engine optimistically splits into segments, but
     * each split chunk fails at *connect* with a content-length mismatch (NoResume) without ever
     * downloading a byte - so `downloaded == 0` - and the first chunk (which holds the full 200
     * body stream) reclaims the adjacent failed split via `takeOverChunk` and downloads the whole
     * file itself. Effectively a single-stream fallback. Verifies exact bytes.
     */
    @Test
    fun noResume_withContentLength_completesViaChunkTakeover() {
        val bytes = randomData(2 * 1024 * 1024, seed = 15)
        server.register("/noresumelen", Endpoint(bytes, resumeSupported = false, plan = {
            ConnPlan(ignoreRange = true)
        }))
        val host = download(id = 15, path = "/noresumelen", maxSegments = 4)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
    }
}
