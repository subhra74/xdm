package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError

/**
 * End-to-end tests for the segmented HTTP downloader ([xdm.core.downloaders.web.http.HttpDownloaderTask])
 * driven against an in-process [MockHttpServer]: single/multi-segment downloads, odd sizes,
 * no-resume streaming, and failure/retry handling.
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
        assertTrue(ep.connCount.get() >= 1, "expected at least one request")
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
            ep.connCount.get() >= 2
        , "expected multiple segment connections, saw ${ep.connCount.get()}")
        assertTrue(host.maxSegmentsSeen.get() >= 2, "expected >1 segment in progress")
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
        assertEquals(1, ep.connCount.get(), "small file should not be split")
    }

    @Test
    fun initCallback_reportsFileSizeAndType() {
        val bytes = randomData(512 * 1024, seed = 5)
        server.register("/init", Endpoint(bytes))
        val host = download(id = 5, path = "/init", maxSegments = 4)

        awaitSuccess(host)
        assertNotNull(host.initInfo, "init callback should fire")
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

    /**
     * Range support but no Content-Length: the size is unknown, so the download must stay one stream
     * even though more segments are allowed.
     */
    @Test
    fun unknownSize_neverSplits() {
        val bytes = randomData(2 * 1024 * 1024, seed = 16)
        val ep = server.register("/nolen", Endpoint(bytes, plan = {
            ConnPlan(omitContentLength = true, throttleChunk = 64 * 1024, throttleSleepMs = 5)
        }))
        val host = download(id = 16, path = "/nolen", maxSegments = 8)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
        assertEquals(1, ep.connCount.get(), "a download of unknown size was split")
    }

    /**
     * The first response is a sized 206, so the download splits, but every later request gets a 200
     * with the whole file and no Content-Length. A 200 is the file from byte 0, not the requested
     * range: those segments must be refused (the first connection then absorbs them), never written
     * at their offsets.
     */
    @Test
    fun boundedRangeAnswered200WithoutLength_isRefused() {
        val bytes = randomData(2 * 1024 * 1024, seed = 17)
        val ep = server.register("/range200", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(throttleChunk = 64 * 1024, throttleSleepMs = 10)
            else ConnPlan(ignoreRange = true, omitContentLength = true)
        }))
        val host = download(id = 17, path = "/range200", maxSegments = 4)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
        assertTrue(ep.connCount.get() > 1, "the download never split, so no range request was made")
    }

    // ---------------------------------------------------------------------------------------
    // Failures & retries
    // ---------------------------------------------------------------------------------------

    @Test
    fun fatalStatus_failsDownload() {
        val bytes = randomData(64 * 1024, seed = 7)
        server.register("/err", Endpoint(bytes, plan = { ConnPlan(failStatus = 404) }))
        val host = download(id = 7, path = "/err", maxSegments = 1)

        awaitDone(host)
        assertNull(host.success)
        assertEquals(DownloadError.InvalidResponse, host.failure)
    }

    /** A 500 is often passing (an overloaded or restarting server): it is retried like a 503. */
    @Test
    fun serverError500_isRetried() {
        val bytes = randomData(512 * 1024, seed = 11)
        server.register("/500", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(failStatus = 500) else ConnPlan()
        }))
        val host = download(id = 11, path = "/500", maxSegments = 1)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
    }

    /** A 416 means the range is not there at all; retrying cannot help, so it fails at once. */
    @Test
    fun rangeNotSatisfiable_failsAtOnce() {
        val bytes = randomData(64 * 1024, seed = 12)
        server.register("/416", Endpoint(bytes, plan = { ConnPlan(failStatus = 416) }))
        val host = download(id = 12, path = "/416", maxSegments = 1)

        awaitDone(host, 4)
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

    @Test
    fun truncatedConnection_failsFastAndResumes() {
        val bytes = randomData(512 * 1024, seed = 10)
        // The first connection is cut after 100 KB; the client must see the dropped socket right away
        // (not wait out its read timeout) and resume from where it stopped.
        val ep = server.register("/truncate", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(truncateAfterBytes = 100 * 1024) else ConnPlan()
        }))
        val started = System.currentTimeMillis()
        val host = download(id = 10, path = "/truncate", maxSegments = 1)

        awaitSuccess(host, timeoutSec = 20)
        val elapsed = System.currentTimeMillis() - started
        assertDownloaded(host, bytes)
        assertEquals(2, ep.connCount.get(), "expected exactly one reconnect after the cut")
        assertTrue(ep.requests[1].rangeStart > 0, "retry must resume, not restart")
        // One 5s retry wait; a socket left open would block the read for far longer.
        assertTrue(elapsed < 10_000, "took ${elapsed}ms; the truncated socket was not closed")
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
        assertTrue(host1.pauseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS), "pause callback should fire")
        assertTrue(host1.paused)
        assertNull(host1.success, "download should not have completed before pause")

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
     * each split chunk is refused at *connect* (a 200 to a range request: NoResume) without ever
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

    /** Waits until the temp file holds at least [min] bytes (a file of unknown size is not preallocated). */
    private fun awaitBytesOnDisk(min: Long) = assertTrue(
        waitFor(5000) { tmpDir.listFiles()?.any { it.name.endsWith(".tmp") && it.length() >= min } == true },
        "no data reached the temp file"
    )

    /**
     * A download without a Content-Length can only request `bytes=0-`, so a resume cannot continue
     * it: it must start over instead of writing the whole file again after the bytes already on disk.
     */
    @Test
    fun unknownSize_pauseThenResume_startsOver() {
        val bytes = randomData(1024 * 1024, seed = 18)
        server.register("/nolen-pause", Endpoint(bytes, plan = {
            ConnPlan(omitContentLength = true, throttleChunk = 16 * 1024, throttleSleepMs = 20)
        }))
        val host1 = host()
        val task1 = newTask(id = 18, path = "/nolen-pause", host = host1, maxSegments = 4)
        task1.start()
        awaitBytesOnDisk(64 * 1024)
        task1.stop()
        assertTrue(host1.pauseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS), "pause callback should fire")
        assertNull(host1.success, "download should not have completed before pause")

        val host2 = host()
        newTask(id = 18, path = "/nolen-pause", host = host2, maxSegments = 4).resume()
        awaitSuccess(host2)
        assertDownloaded(host2, bytes)
        assertTrue(
            tmpDir.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty(),
            "the discarded temp file was left behind"
        )
    }

    /**
     * No Content-Length before the pause, but one on resume. The restart must pick the size up and
     * finish as a normal sized download (which may split), not treat the size-0 segment as complete.
     */
    @Test
    fun unknownSize_resumeGetsContentLength_completesAsSizedDownload() {
        val bytes = randomData(1024 * 1024, seed = 19)
        val ep = server.register("/nolen-then-len", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) {
                ConnPlan(omitContentLength = true, throttleChunk = 16 * 1024, throttleSleepMs = 20)
            } else {
                ConnPlan()
            }
        }))
        val host1 = host()
        val task1 = newTask(id = 19, path = "/nolen-then-len", host = host1, maxSegments = 4)
        task1.start()
        awaitBytesOnDisk(64 * 1024)
        task1.stop()
        assertTrue(host1.pauseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS), "pause callback should fire")

        val host2 = host()
        newTask(id = 19, path = "/nolen-then-len", host = host2, maxSegments = 4).resume()
        awaitSuccess(host2)
        assertDownloaded(host2, bytes)
        assertEquals(bytes.size.toLong(), host2.initInfo?.fileSize, "the restart did not pick up the size")
        assertTrue(ep.connCount.get() > 2, "the restarted download never split")
    }

    /**
     * The file changes on the server while the download is paused (same size, new Last-Modified).
     * Resume must not continue the old version with the new one's bytes: its range requests carry
     * If-Range, the server answers with the whole new file (200), and that is refused, so the download
     * fails instead of publishing a mix of both versions.
     */
    @Test
    fun fileChangedWhilePaused_resumeDoesNotMixVersions() {
        val v1 = randomData(2 * 1024 * 1024, seed = 20)
        val v2 = Version(randomData(v1.size, seed = 21), etag = "\"v2\"", lastModified = "Tue, 02 Jan 2024 00:00:00 GMT")
        val changed = java.util.concurrent.atomic.AtomicBoolean(false)
        server.register("/changes", Endpoint(v1, etag = "\"v1\"", lastModified = "Mon, 01 Jan 2024 00:00:00 GMT", plan = {
            if (changed.get()) ConnPlan(version = v2) else ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 50)
        }))
        val host1 = host()
        val task1 = newTask(id = 20, path = "/changes", host = host1, maxSegments = 4)
        task1.start()
        Thread.sleep(500)
        task1.stop()
        assertTrue(host1.pauseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS), "pause callback should fire")
        assertNull(host1.success, "download should not have completed before pause")

        changed.set(true)
        val host2 = host()
        newTask(id = 20, path = "/changes", host = host2, maxSegments = 4).resume()
        awaitDone(host2)
        assertNull(host2.success, "a mix of the old and the new file was published")
        assertEquals(DownloadError.ResumeNotSupported, host2.failure)
    }

    /**
     * After the pause the link serves the same file from a mirror: same Last-Modified, but the mirror's
     * own ETag. Resume must continue; only Last-Modified decides whether the file changed.
     */
    @Test
    fun sameFileFromMirrorWithOtherEtag_resumeContinues() {
        val bytes = randomData(2 * 1024 * 1024, seed = 24)
        val modified = "Mon, 01 Jan 2024 00:00:00 GMT"
        val mirror = Version(bytes, etag = "\"mirror-2\"", lastModified = modified)
        val switched = java.util.concurrent.atomic.AtomicBoolean(false)
        server.register("/mirror", Endpoint(bytes, etag = "\"mirror-1\"", lastModified = modified, plan = {
            if (switched.get()) ConnPlan(version = mirror) else ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 50)
        }))
        val host1 = host()
        val task1 = newTask(id = 24, path = "/mirror", host = host1, maxSegments = 4)
        task1.start()
        Thread.sleep(500)
        task1.stop()
        assertTrue(host1.pauseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS), "pause callback should fire")
        assertNull(host1.success, "download should not have completed before pause")

        switched.set(true)
        val host2 = host()
        newTask(id = 24, path = "/mirror", host = host2, maxSegments = 4).resume()
        awaitSuccess(host2)
        assertDownloaded(host2, bytes)
    }

    /**
     * A server without range support, and its one connection drops part-way, so nothing can be
     * continued. Resume must start over and complete instead of failing every time.
     */
    @Test
    fun noRangeSupport_resumeAfterFailure_startsOver() {
        val bytes = randomData(2 * 1024 * 1024, seed = 25)
        server.register("/no-ranges", Endpoint(bytes, resumeSupported = false, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(truncateAfterBytes = 512 * 1024) else ConnPlan()
        }))
        val host1 = host()
        newTask(id = 25, path = "/no-ranges", host = host1, maxSegments = 4).start()
        awaitDone(host1)
        assertEquals(DownloadError.ResumeNotSupported, host1.failure)

        val host2 = host()
        newTask(id = 25, path = "/no-ranges", host = host2, maxSegments = 4).resume()
        awaitSuccess(host2)
        assertDownloaded(host2, bytes)
    }

    /**
     * The temp file is deleted while the download is paused. Its bytes are gone, so resume must start
     * over: continuing would recreate an empty file and publish it with the paused progress missing.
     */
    @Test
    fun tempFileDeletedWhilePaused_resumeStartsOver() {
        val bytes = randomData(2 * 1024 * 1024, seed = 23)
        server.register("/temp-gone", Endpoint(bytes, plan = {
            ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 50)
        }))
        val host1 = host()
        val task1 = newTask(id = 23, path = "/temp-gone", host = host1, maxSegments = 4)
        task1.start()
        Thread.sleep(500)
        task1.stop()
        assertTrue(host1.pauseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS), "pause callback should fire")
        assertNull(host1.success, "download should not have completed before pause")
        val temp = tmpDir.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty()
        assertTrue(temp.isNotEmpty(), "no temp file to delete")
        temp.forEach { it.delete() }

        val host2 = host()
        newTask(id = 23, path = "/temp-gone", host = host2, maxSegments = 4).resume()
        awaitSuccess(host2)
        assertDownloaded(host2, bytes)
    }
}
