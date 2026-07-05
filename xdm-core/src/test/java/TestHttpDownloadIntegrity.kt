import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * File-integrity coverage for the segmented HTTP downloader: every test verifies the reassembled
 * output by SHA-256 checksum (see [assertChecksumMatches]), not just size, so a single transposed
 * or duplicated segment byte is caught. The scenarios stress the segment boundaries under adverse
 * conditions the byte-exact tests in the sibling suites don't cover:
 *
 *  - mixed fast/slow connections racing to finish different segments,
 *  - per-chunk speed fluctuation (a connection that speeds up and slows down mid-stream),
 *  - network disconnections spread across several concurrent segments,
 *  - repeated pause/resume cycles that keep re-slicing the remaining ranges.
 *
 * Separate class => separate JVM fork (surefire reuseForks=false) so the many networked, dropped
 * and stalled sockets here cannot leak threads into the other suites.
 */
class TestHttpDownloadIntegrity : HttpDownloadTestBase() {

    // ---------------------------------------------------------------------------------------
    // Baseline: checksum of a clean multi-segment download
    // ---------------------------------------------------------------------------------------

    @Test
    fun multiSegment_clean_checksumMatches() {
        val bytes = randomData(4 * 1024 * 1024, seed = 101)
        server.register("/clean", Endpoint(bytes))
        val host = download(id = 101, path = "/clean", maxSegments = 8)

        awaitSuccess(host)
        assertChecksumMatches(host, bytes)
        assertEquals(sha256(bytes), sha256(host.finalFile!!))
    }

    // ---------------------------------------------------------------------------------------
    // Mixed fast / slow connections
    // ---------------------------------------------------------------------------------------

    @Test
    fun mixedFastSlowConnections_checksumMatches() {
        // Odd connections are fast; even connections are heavily throttled. Because segments
        // finish at wildly different times, the downloader's finished-segment stitching and
        // idle-connection re-splitting are both exercised - the file must still reassemble intact.
        val bytes = randomData(6 * 1024 * 1024, seed = 102)
        val ep = server.register("/mixed", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex % 2 == 0) {
                ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 25) // slow lane
            } else {
                ConnPlan() // fast lane, full speed
            }
        }))
        val host = download(id = 102, path = "/mixed", maxSegments = 8)

        awaitSuccess(host, timeoutSec = 60)
        assertChecksumMatches(host, bytes)
        assertTrue(
            "expected several connections across fast/slow lanes, saw ${ep.connCount.get()}",
            ep.connCount.get() >= 3
        )
    }

    // ---------------------------------------------------------------------------------------
    // Speed fluctuation within a single connection / across chunks
    // ---------------------------------------------------------------------------------------

    @Test
    fun speedFluctuationAcrossChunks_checksumMatches() {
        // Each connection picks a random-but-seeded throttle profile keyed off its byte offset,
        // so different chunks of the file transfer at different, shifting speeds (some near-idle,
        // some full throttle). Reassembly must be independent of transfer rate.
        val bytes = randomData(5 * 1024 * 1024, seed = 103)
        server.register("/fluct", Endpoint(bytes, plan = { ctx ->
            // Derive a stable per-range speed so retries of the same range behave consistently.
            val r = Random(ctx.rangeStart xor 0x5DEECE66DL)
            val sleep = longArrayOf(0, 5, 20, 40)[r.nextInt(4)]
            val chunk = intArrayOf(8, 32, 128, 256)[r.nextInt(4)] * 1024
            ConnPlan(throttleChunk = chunk, throttleSleepMs = sleep)
        }))
        val host = download(id = 103, path = "/fluct", maxSegments = 6)

        awaitSuccess(host, timeoutSec = 60)
        assertChecksumMatches(host, bytes)
    }

    // ---------------------------------------------------------------------------------------
    // Network disconnections across multiple concurrent segments
    // ---------------------------------------------------------------------------------------

    @Test
    fun disconnections_acrossMultipleSegments_resumeAndChecksumMatches() {
        // Every one of the first few connections is RST mid-body, regardless of which segment /
        // offset it is serving. Because the download is split into several parallel segments, this
        // forces independent mid-transport resumes on more than one range at once. The retries
        // reconnect with Range headers and the final file must checksum-match the source.
        val bytes = randomData(2 * 1024 * 1024, seed = 104)
        val dropped = java.util.concurrent.atomic.AtomicInteger(0)
        val raw = RawDropServer(bytes, dropAfterBytes = 48 * 1024) { _, _ ->
            // Drop the first 4 connections we ever see, then serve cleanly so the test terminates.
            dropped.getAndIncrement() < 4
        }
        rawServers.add(raw)
        val host = host()
        newTaskForUrl(id = 104, url = raw.url("/multidrop"), host = host, config = TestConfig(maxSegments = 4)).start()

        awaitSuccess(host, timeoutSec = 90) // retries sleep ~5s each before resuming
        assertChecksumMatches(host, bytes)
        assertTrue(
            "expected reconnects beyond the initial segment opens, saw ${raw.requestedRanges.size}",
            raw.requestedRanges.size >= 5
        )
    }

    // ---------------------------------------------------------------------------------------
    // Repeated pause / resume
    // ---------------------------------------------------------------------------------------

    @Test
    fun repeatedPauseResume_checksumMatches() {
        // Pause and resume several times through the lifetime of the download. Each resume loads
        // the persisted .state, re-slices whatever ranges remain, and continues. After the final
        // resume the reassembled file must checksum-match the original source bytes exactly.
        val bytes = randomData(6 * 1024 * 1024, seed = 106)
        // Throttle hard so the download can never finish inside a single ~400ms window - every
        // cycle genuinely interrupts an in-flight transfer.
        server.register("/repause", Endpoint(bytes, plan = {
            ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 20)
        }))

        val cycles = 3
        // First run.
        var host = host()
        var task = newTask(id = 106, path = "/repause", host = host, maxSegments = 4)
        task.start()

        repeat(cycles) { i ->
            Thread.sleep(400)
            task.stop()
            assertTrue(
                "pause callback should fire on cycle $i",
                host.pauseLatch.await(5, TimeUnit.SECONDS)
            )
            assertTrue("expected paused state on cycle $i", host.paused)
            assertNull("download must not complete before final resume (cycle $i)", host.success)

            // Resume in a fresh task instance, exactly as the app does after a restart.
            host = host()
            task = newTask(id = 106, path = "/repause", host = host, maxSegments = 4)
            task.resume()
        }

        // Let the final resume run to completion without interruption.
        awaitSuccess(host, timeoutSec = 90)
        assertChecksumMatches(host, bytes)
    }

    @Test
    fun pauseResume_underThrottle_singleSegment_checksumMatches() {
        // A single non-splittable stream (maxSegments=1) that is paused once partway and resumed.
        // Verifies the single-segment resume offset math produces a byte-intact file by checksum.
        val bytes = randomData(2 * 1024 * 1024, seed = 107)
        server.register("/pause1", Endpoint(bytes, plan = {
            ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 20)
        }))

        val host1 = host()
        val task1 = newTask(id = 107, path = "/pause1", host = host1, maxSegments = 1)
        task1.start()
        Thread.sleep(500)
        task1.stop()
        assertTrue("pause callback should fire", host1.pauseLatch.await(5, TimeUnit.SECONDS))
        assertNull("should not have completed before pause", host1.success)

        val host2 = host()
        newTask(id = 107, path = "/pause1", host = host2, maxSegments = 1).resume()
        awaitSuccess(host2, timeoutSec = 60)
        assertChecksumMatches(host2, bytes)
    }
}
