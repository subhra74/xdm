package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.http.HttpTaskContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * When a segment finishes while others are still running, its connection must go to the largest
 * remaining range (split it and start a new segment on the second half), not sit idle until a
 * slow segment drains on its own.
 */
class TestHttpSegmentStealing : HttpDownloadTestBase() {

    private fun contextOf(task: HttpDownloaderTask): HttpTaskContext =
        HttpDownloaderTask::class.java.getDeclaredField("context").apply { isAccessible = true }
            .get(task) as HttpTaskContext

    /** Polls the task's chunk table and records the most chunks ever active (Downloading or Ready) at once. */
    private class ActiveChunkWatcher(private val ctx: HttpTaskContext) {
        val maxActive = AtomicInteger(0)
        private val running = AtomicBoolean(true)
        private val thread = Thread {
            while (running.get()) {
                val n = ctx.chunks.values.count {
                    val s = it.status.get()
                    s == ChunkStatus.Downloading || s == ChunkStatus.Ready
                }
                maxActive.updateAndGet { if (n > it) n else it }
                Thread.sleep(2)
            }
        }.apply { isDaemon = true; start() }

        fun stop() {
            running.set(false)
            thread.join(1000)
        }
    }

    @Test
    fun finishedSegment_takesOverLargestRemainingRange() {
        val size = 8 * 1024 * 1024
        val half = size / 2L
        val bytes = randomData(size, seed = 300)
        // The first connection (offset 0) is slow; everything else is fast. With 2 segments the second
        // half finishes quickly and must then split the slow first half instead of idling.
        val ep = server.register("/steal", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 40) else ConnPlan()
        }))
        val host = host()
        val started = System.currentTimeMillis()
        newTask(id = 300, path = "/steal", host = host, maxSegments = 2).start()

        awaitSuccess(host, timeoutSec = 60)
        val elapsed = System.currentTimeMillis() - started
        assertChecksumMatches(host, bytes)
        val stolen = ep.requests.filter { it.hasRange && it.rangeStart in 1 until half }
        assertTrue(stolen.isNotEmpty(), "no segment took over part of the slow first half: ${ep.requests}")
        // Draining the slow half alone takes ~10s (4 MB at ~400 KB/s).
        assertTrue(elapsed < 8000, "download took ${elapsed}ms; the slow segment was not relieved")
    }

    @Test
    fun takingOver_neverExceedsMaxSegments() {
        val bytes = randomData(12 * 1024 * 1024, seed = 301)
        val rnd = Random(301)
        val ep = server.register("/cap", Endpoint(bytes, plan = {
            // Mixed speeds so segments finish at different times and keep handing work over.
            val sleep = synchronized(rnd) { rnd.nextLong(0, 6) }
            ConnPlan(throttleChunk = 32 * 1024, throttleSleepMs = sleep)
        }))
        val host = host()
        val task = newTask(id = 301, path = "/cap", host = host, maxSegments = 4)
        task.start()
        assertTrue(waitFor(5000) { runCatching { contextOf(task) }.isSuccess }, "no context")
        val watcher = ActiveChunkWatcher(contextOf(task))
        try {
            awaitSuccess(host, timeoutSec = 60)
        } finally {
            watcher.stop()
        }
        assertChecksumMatches(host, bytes)
        assertTrue(watcher.maxActive.get() <= 4, "saw ${watcher.maxActive.get()} active segments with a cap of 4")
        assertTrue(ep.connCount.get() > 4, "expected finished segments to start new ones, saw ${ep.connCount.get()} connections")
    }

    /**
     * Splits otherwise only happen when a segment connects, finishes or takes over. Here the first
     * segment takes over the second (whose connection never sends anything) and is then left alone with
     * a slot free, inside its 5 s takeover cooldown. Once the cooldown is over it must be split again
     * instead of running to the end on one connection.
     */
    @Test
    fun loneSegmentAfterTakeover_isSplitOnceCooldownEnds() {
        val half = 1536 * 1024
        val bytes = randomData(2 * half, seed = 303)
        val ep = server.register("/cooldown", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 2) ConnPlan(delayBeforeMs = 30_000)
            else ConnPlan(throttleChunk = 20 * 1024, throttleSleepMs = 100)
        }))
        val host = host()
        newTask(id = 303, path = "/cooldown", host = host, maxSegments = 2).start()

        awaitSuccess(host, timeoutSec = 40)
        assertChecksumMatches(host, bytes)
        assertTrue(
            ep.requests.any { it.connIndex >= 3 && it.rangeStart > half },
            "the segment that took over was never split again: ${ep.requests.map { it.rangeStart }}"
        )
    }

    @Test
    fun singleSegment_finishingDoesNotSpawnMore() {
        val bytes = randomData(2 * 1024 * 1024, seed = 302)
        val ep = server.register("/one", Endpoint(bytes))
        val host = download(id = 302, path = "/one", maxSegments = 1)

        awaitSuccess(host)
        assertChecksumMatches(host, bytes)
        assertEquals(1, ep.connCount.get(), "a single-segment download must use one connection")
    }

    private val ownTasks = ArrayList<HttpDownloaderTask>()

    @org.junit.jupiter.api.AfterEach
    fun stopOwnTasks() {
        ownTasks.forEach { runCatching { it.stop() } }
    }

    /**
     * A task whose client gives up on a silent read after 1s. The mock server cannot drop a socket
     * mid-body, so a stalled response stands in for a broken connection.
     */
    private fun stallTask(id: Long, path: String, host: TestDownloadHost, maxSegments: Int) =
        HttpDownloaderTask(
            HttpDownloadTaskInfo(
                id = id, url = server.url(path), fileName = "f$id.bin", respectFileName = false, cookie = null,
                headers = null, origin = null, autoCategorize = false, defaultDownloadFolder = tmpDir.absolutePath,
                userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null, knownFileSize = null,
            ),
            host, CountingHttpClient(httpClientWithReadTimeout(1)), work.absolutePath, TestConfig(maxSegments)
        ).also { ownTasks += it }

    /**
     * A segment that failed after writing bytes (so no neighbour can absorb it) is restarted ahead of
     * any split when another segment finishes, and the download completes.
     */
    @Test
    fun failedSegment_isRestartedWhenAnotherFinishes() {
        val size = 4 * 1024 * 1024
        val half = size / 2L
        val bytes = randomData(size, seed = 303)
        val secondHalf = AtomicInteger(0)
        server.register("/retry-first", Endpoint(bytes, plan = { ctx ->
            if (ctx.hasRange && ctx.rangeStart >= half) {
                when (secondHalf.incrementAndGet()) {
                    // Some bytes, then silence: read timeout, 5s retry wait, then a refusal fails the chunk.
                    1 -> ConnPlan(stallAfterBytes = 64 * 1024, stallMs = -1)
                    2 -> ConnPlan(failStatus = 404)
                    else -> ConnPlan()
                }
            } else {
                // ~13s for the first half: still running when the second half fails at ~6s.
                ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 100)
            }
        }))
        val host = host()
        stallTask(303, "/retry-first", host, maxSegments = 2).start()

        awaitSuccess(host, timeoutSec = 60)
        assertChecksumMatches(host, bytes)
        assertTrue(secondHalf.get() >= 3, "the failed segment was never restarted (${secondHalf.get()} requests)")
    }

    /** A range that is refused for good still ends in a reported failure once nothing is left running. */
    @Test
    fun permanentlyFailedSegment_isReportedInsteadOfHanging() {
        val size = 4 * 1024 * 1024
        val half = size / 2L
        val bytes = randomData(size, seed = 305)
        val secondHalf = AtomicInteger(0)
        server.register("/fail", Endpoint(bytes, plan = { ctx ->
            if (ctx.hasRange && ctx.rangeStart >= half) {
                if (secondHalf.incrementAndGet() == 1) ConnPlan(stallAfterBytes = 64 * 1024, stallMs = -1)
                else ConnPlan(failStatus = 404)
            } else {
                ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 20)
            }
        }))
        val host = host()
        stallTask(305, "/fail", host, maxSegments = 2).start()

        awaitDone(host, timeoutSec = 60)
        assertNull(host.success, "download cannot succeed with a refused range")
        // With bytes on disk and more than two chunks, reportFailure words a refusal as an expired session.
        assertTrue(
            host.failure == DownloadError.InvalidResponse || host.failure == DownloadError.SessionExpired,
            "unexpected failure: ${host.failure}"
        )
    }

    @Test
    fun pauseWhileTakingOver_thenResume_completesWithExactBytes() {
        val bytes = randomData(8 * 1024 * 1024, seed = 304)
        server.register("/pause-steal", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 60)
            else ConnPlan(throttleChunk = 32 * 1024, throttleSleepMs = 30)
        }))
        val host1 = host()
        val task1 = newTask(id = 304, path = "/pause-steal", host = host1, maxSegments = 3)
        task1.start()
        // The fast segments (~1 MB/s) finish their ~2.7 MB first and start taking over; pause then.
        assertTrue(waitFor(15_000) { contextOf(task1).chunks.size > 3 }, "no segment took over before pause")
        task1.stop()
        assertTrue(host1.pauseLatch.await(5, TimeUnit.SECONDS), "pause callback should fire")
        assertNull(host1.success, "download should not have completed before pause")
        Thread.sleep(300) // let chunk threads see the stop before the state is reloaded

        val host2 = host()
        newTask(id = 304, path = "/pause-steal", host = host2, maxSegments = 3).resume()
        awaitSuccess(host2, timeoutSec = 60)
        assertChecksumMatches(host2, bytes)
    }

    @Test
    fun randomSpeedsStallsAndCuts_manyRuns_alwaysCompleteIntact() {
        repeat(6) { run ->
            val id = 310L + run
            val bytes = randomData(6 * 1024 * 1024 + run * 12_345, seed = id)
            val rnd = Random(id)
            server.register("/stress$run", Endpoint(bytes, plan = { ctx ->
                synchronized(rnd) {
                    val sleep = rnd.nextLong(0, 8)
                    val plan = ConnPlan(throttleChunk = 32 * 1024, throttleSleepMs = sleep)
                    if (ctx.connIndex == 1) return@synchronized plan
                    // Some connections stall (read-timeout retry), some drop mid-body, so both kinds of
                    // retry mix with hand-overs.
                    val at = rnd.nextLong(16 * 1024, 300 * 1024)
                    when (rnd.nextInt(8)) {
                        0 -> plan.copy(stallAfterBytes = at, stallMs = -1)
                        1, 2 -> plan.copy(truncateAfterBytes = at)
                        else -> plan
                    }
                }
            }))
            val host = host()
            stallTask(id, "/stress$run", host, maxSegments = 2 + run).start()
            awaitSuccess(host, timeoutSec = 90)
            assertChecksumMatches(host, bytes)
        }
    }

    /**
     * Only the first connection ever works. Every other one drops mid-body, stalls, or gets a 503, so
     * the other segments keep failing and retrying (each with a 5s wait). The download must still
     * finish with the exact bytes.
     */
    @Test
    fun onlyOneHealthySegment_othersFailIntermittently_completesIntact() {
        listOf(320L, 321L, 322L).forEach { id ->
            val bytes = randomData(3 * 1024 * 1024 + id.toInt(), seed = id)
            val rnd = Random(id)
            val ep = server.register("/one-healthy$id", Endpoint(bytes, plan = { ctx ->
                if (ctx.connIndex == 1) {
                    ConnPlan(throttleChunk = 32 * 1024, throttleSleepMs = 10)
                } else synchronized(rnd) {
                    val at = rnd.nextLong(8 * 1024, 256 * 1024)
                    when (rnd.nextInt(4)) {
                        0 -> ConnPlan(failStatus = 503)
                        1 -> ConnPlan(stallAfterBytes = at, stallMs = -1)
                        else -> ConnPlan(truncateAfterBytes = at)
                    }
                }
            }))
            val host = host()
            stallTask(id, "/one-healthy$id", host, maxSegments = 4).start()
            awaitSuccess(host, timeoutSec = 120)
            assertChecksumMatches(host, bytes)
            assertTrue(ep.connCount.get() > 4, "expected the failing segments to retry (${ep.connCount.get()} connections)")
        }
    }

    /**
     * Only the first connection works and every other one is refused before sending a byte. Refused
     * segments hold no data, so the healthy segment absorbs each one as it reaches it.
     */
    @Test
    fun onlyOneHealthySegment_othersAlwaysRefused_healthyOneAbsorbsAll() {
        val bytes = randomData(3 * 1024 * 1024, seed = 323)
        val ep = server.register("/absorb", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(throttleChunk = 64 * 1024, throttleSleepMs = 10)
            else ConnPlan(failStatus = 404)
        }))
        val host = host()
        stallTask(323, "/absorb", host, maxSegments = 4).start()

        awaitSuccess(host, timeoutSec = 60)
        assertChecksumMatches(host, bytes)
        assertTrue(ep.connCount.get() > 1, "expected split segments to be attempted")
    }

    /**
     * The server serves one connection at a time and turns the rest away with 503. The other segments
     * keep retrying (and would download whenever the slot is free), but the first connection holds the
     * slot throughout, so it must end up downloading the whole file by absorbing every refused segment.
     */
    @Test
    fun serverAllowsOneConnection_firstSegmentDownloadsWholeFile() {
        val bytes = randomData(3 * 1024 * 1024, seed = 330)
        val ep = server.register("/one-slot", Endpoint(bytes, maxConcurrent = 1, plan = {
            ConnPlan(throttleChunk = 32 * 1024, throttleSleepMs = 10)
        }))
        val host = host()
        stallTask(330, "/one-slot", host, maxSegments = 4).start()

        awaitSuccess(host, timeoutSec = 60)
        assertChecksumMatches(host, bytes)
        assertEquals(1, ep.peakActive.get(), "the server served more than one connection at once")
        assertTrue(ep.refusedByLimit.get() > 0, "the other segments never tried to connect")
        assertEquals(
            1, ep.connCount.get() - ep.refusedByLimit.get(),
            "only the first connection should have been served: ${ep.requests}"
        )
    }

    /**
     * The server serves at most 3 connections at once (503 beyond that) while 8 segments are allowed.
     * Refused segments retry or get absorbed, and the download completes intact.
     */
    @Test
    fun serverAllowsThreeConnections_completesIntact() {
        val bytes = randomData(8 * 1024 * 1024 + 4321, seed = 331)
        val ep = server.register("/three-slots", Endpoint(bytes, maxConcurrent = 3, plan = {
            ConnPlan(throttleChunk = 32 * 1024, throttleSleepMs = 10)
        }))
        val host = host()
        stallTask(331, "/three-slots", host, maxSegments = 8).start()

        awaitSuccess(host, timeoutSec = 90)
        assertChecksumMatches(host, bytes)
        assertTrue(ep.peakActive.get() <= 3, "server limit broken: ${ep.peakActive.get()} at once")
        assertTrue(ep.refusedByLimit.get() > 0, "expected segments beyond the limit to be refused")
        assertTrue(
            ep.connCount.get() - ep.refusedByLimit.get() > 1,
            "expected more than one connection to be served"
        )
    }

    /**
     * One connection at a time, and every served connection is cut part-way, the first included. When
     * the slot holder drops, another segment gets the slot, so some segment is always alive: the
     * download must finish intact without hanging or giving up.
     */
    @Test
    fun serverAllowsOneConnection_everyConnectionCut_completesIntact() {
        listOf(332L, 333L).forEach { id ->
            val size = 2 * 1024 * 1024 + id.toInt()
            val bytes = randomData(size, seed = id)
            val rnd = Random(id)
            val served = java.util.concurrent.CopyOnWriteArrayList<ReqCtx>()
            val ep = server.register("/one-slot-cut$id", Endpoint(bytes, maxConcurrent = 1, plan = { ctx ->
                served += ctx
                val at = synchronized(rnd) { rnd.nextLong(64 * 1024, 384 * 1024) }
                ConnPlan(throttleChunk = 32 * 1024, throttleSleepMs = 5, truncateAfterBytes = at)
            }))
            val host = host()
            stallTask(id, "/one-slot-cut$id", host, maxSegments = 4).start()

            awaitSuccess(host, timeoutSec = 120)
            assertChecksumMatches(host, bytes)
            assertEquals(1, ep.peakActive.get(), "the server served more than one connection at once")
            assertTrue(ep.refusedByLimit.get() > 0, "the other segments never tried to connect")
            assertTrue(
                served.any { it.rangeStart >= size / 2 },
                "no other segment downloaded while the first was down: $served"
            )
        }
    }
}
