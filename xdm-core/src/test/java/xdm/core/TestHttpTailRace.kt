// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.partFileName
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.http.TailRacer
import xdm.core.downloaders.web.loadState
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tail racing: once nothing is left big enough to split, a free connection fetches the rest of a lingering
 * chunk into memory and, if it gets there first, writes it and finishes the chunk. Covers the racer winning
 * against a stalled chunk, losing to it, finishing it while its thread is mid-write, giving up on a bad
 * response, and pause / delete at each stage; after every one, no chunk or racer thread and no part-file
 * handle may be left behind.
 *
 * The file is [SIZE] bytes downloaded with 2 segments: the first connection (from 0) is throttled, so the
 * split-off second half ([HALF]) gets its first bytes before the first one runs into it, and is the tail.
 * Clients use a long read timeout, so only a racer can rescue a stalled read.
 */
class TestHttpTailRace : HttpDownloadTestBase() {

    private val started = ArrayList<HttpDownloaderTask>()
    private val clients = ArrayList<CountingHttpClient>()
    private var grace = 0L
    private lateinit var before: Set<Thread>

    @BeforeEach
    fun lowerGrace() {
        grace = HttpDownloaderTask.raceGraceMs
        HttpDownloaderTask.raceGraceMs = 300
        before = workerThreads()
    }

    @AfterEach
    fun restore() {
        started.forEach { runCatching { it.stop() } }
        clients.forEach { runCatching { it.close() } }
        HttpDownloaderTask.raceGraceMs = grace
    }

    private fun task(
        id: Long,
        path: String,
        host: TestDownloadHost,
        config: TestConfig = TestConfig(maxSegments = 2),
    ): HttpDownloaderTask {
        val client = CountingHttpClient(httpClientWithReadTimeout(600)).also { clients += it }
        return HttpDownloaderTask(
            HttpDownloadTaskInfo(
                id = id, url = server.url(path), fileName = "f$id.bin", respectFileName = false, cookie = null,
                headers = null, origin = null, categoryId = null, targetFolder = tmpDir.absolutePath,
                maxPiece = 0, authInfo = null, knownFileSize = null,
            ),
            host, client, work.absolutePath, config
        ).also { started += it }
    }

    /** Chunk and racer threads started since the test began. */
    private fun workerThreads(): Set<Thread> = Thread.getAllStackTraces().entries
        .filter { (t, st) -> t.name.startsWith(TailRacer.THREAD_PREFIX) || st.any { it.methodName == "retrieveChunk" } }
        .map { it.key }.toSet()

    private fun racerThreads() = workerThreads().filter { it.name.startsWith(TailRacer.THREAD_PREFIX) }

    private fun assertNoThreadsLeft(what: String) {
        val left = waitFor(3000) { (workerThreads() - before).isEmpty() }
        assertTrue(left, "threads still running after $what:\n" + (workerThreads() - before).joinToString("\n\n") { th ->
            th.name + "\n" + th.stackTrace.take(15).joinToString("\n") { "  at $it" }
        })
    }

    private fun assertNoHandlesLeft(storage: TestHttpSharedOutput.FaultyStorage, what: String) {
        assertTrue(waitFor(3000) { storage.open == 0 }, "${storage.open} part-file handle(s) still open after $what")
    }

    private fun assertClientClosed(what: String) {
        assertTrue(waitFor(3000) { clients.all { it.closeCount.get() >= 1 } }, "HTTP client not closed after $what")
    }

    private fun savedState(id: Long, host: TestDownloadHost) = loadState(
        id = id, configDir = work.absolutePath, http = CountingHttpClient(), host = host,
        tempFolder = tmpDir.absolutePath, tempFileName = partFileName(id),
    ).getOrThrow()

    /**
     * An endpoint for the 2-segment layout: the first connection throttled, the first connection to the
     * tail ([HALF]) as [tail], each later request into the tail (a racer, or a resume) as [racer].
     */
    private fun endpoint(
        path: String,
        bytes: ByteArray,
        tail: ConnPlan,
        racer: (index: Int) -> ConnPlan = { ConnPlan() },
    ): Endpoint {
        val tails = AtomicInteger()
        val racers = AtomicInteger()
        return server.register(path, Endpoint(bytes, plan = { ctx ->
            when {
                ctx.rangeStart == 0L -> ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 20)
                ctx.rangeStart == HALF && tails.getAndIncrement() == 0 -> tail
                else -> racer(racers.incrementAndGet())
            }
        }))
    }

    /** Requests that started inside the tail past [HALF]: racers (and resumes, where a test pauses). */
    private fun Endpoint.racerRequests() = requests.filter { it.rangeStart > HALF }

    @Test
    fun stalledTail_racerFinishesDownload() {
        val bytes = randomData(SIZE, seed = 900)
        val ep = endpoint("/stall", bytes, tail = ConnPlan(stallAfterBytes = STALL_AT, stallMs = -1))
        val storage = TestHttpSharedOutput.FaultyStorage()
        val host = host(storage)
        val t = task(900, "/stall", host)
        t.start()

        // The stalled read would hold the download for the 600 s read timeout without the racer.
        awaitSuccess(host, 15)
        assertChecksumMatches(host, bytes)
        assertEquals(listOf(HALF + STALL_AT), ep.racerRequests().map { it.rangeStart }, "one racer, from where the tail stalled")
        assertEquals(SIZE.toLong(), t.downloadedBytes, "racer bytes counted once")
        assertNoThreadsLeft("the racer finished the download")
        assertNoHandlesLeft(storage, "the racer finished the download")
        assertClientClosed("the racer finished the download")
    }

    @Test
    fun tailFinishesFirst_racerDiscardedAndReleased() {
        val bytes = randomData(SIZE, seed = 901)
        // The tail picks up again after a pause; the racer stalls forever after a little.
        val ep = endpoint(
            "/tail-wins", bytes,
            tail = ConnPlan(stallAfterBytes = STALL_AT, stallMs = 1500),
            racer = { ConnPlan(stallAfterBytes = 10 * 1024, stallMs = -1) },
        )
        val storage = TestHttpSharedOutput.FaultyStorage()
        val host = host(storage)
        val t = task(901, "/tail-wins", host)
        t.start()

        assertTrue(waitFor(5000) { ep.racerRequests().isNotEmpty() }, "racer never connected")
        awaitSuccess(host, 15)
        assertChecksumMatches(host, bytes)
        assertEquals(SIZE.toLong(), t.downloadedBytes, "nothing of the discarded racer counted")
        // Its read was blocked on a stalled connection: finishing the chunk must have aborted it.
        assertTrue(waitFor(2000) { racerThreads().isEmpty() }, "racer still blocked after the tail finished")
        assertNoThreadsLeft("the tail won")
        assertNoHandlesLeft(storage, "the tail won")
    }

    @Test
    fun racerCommitsWhileTailIsMidWrite_noDoubleCountNoCorruption() {
        val bytes = randomData(SIZE, seed = 902)
        val delayed = AtomicBoolean(false)
        val tailWriteDone = AtomicBoolean(false)
        // The tail's write at 3/4 of the file hangs (on the tail's own thread only) while the racer, which
        // asked from there, commits; then the tail's write goes through after all.
        val storage = TestHttpSharedOutput.FaultyStorage(beforeWrite = { position, _ ->
            if (position == HALF + 128 * 1024 && !Thread.currentThread().name.startsWith(TailRacer.THREAD_PREFIX)
                && delayed.compareAndSet(false, true)
            ) {
                Thread.sleep(1500)
                tailWriteDone.set(true)
            }
        })
        val ep = endpoint("/mid-write", bytes, tail = ConnPlan())
        val host = host(storage)
        val t = task(902, "/mid-write", host)
        t.start()

        awaitSuccess(host, 15)
        assertTrue(delayed.get(), "the tail never reached the delayed write")
        assertEquals(listOf(HALF + 128 * 1024), ep.racerRequests().map { it.rangeStart }, "racer asked from the stuck write")
        assertChecksumMatches(host, bytes)
        assertNoThreadsLeft("the racer won during a write")
        assertTrue(tailWriteDone.get(), "the tail's delayed write never returned")
        assertEquals(SIZE.toLong(), t.downloadedBytes, "the tail's late write counted on top of the racer's")
        val state = savedState(902, host)
        assertTrue(state.chunks.values.all { it.downloaded.get() == it.length.get() }, "a chunk counts more than its length")
        assertNoHandlesLeft(storage, "the racer won during a write")
    }

    @ParameterizedTest
    @ValueSource(strings = ["200", "503", "truncated", "no-length"])
    fun unusableRacerResponse_changesNothing(kind: String) {
        val bytes = randomData(SIZE, seed = 903)
        val ep = endpoint(
            "/bad-$kind", bytes,
            tail = ConnPlan(stallAfterBytes = STALL_AT, stallMs = 2000),
            racer = {
                when (kind) {
                    "200" -> ConnPlan(ignoreRange = true)
                    "503" -> ConnPlan(failStatus = 503)
                    "truncated" -> ConnPlan(truncateAfterBytes = 10 * 1024)
                    else -> ConnPlan(omitContentLength = true)
                }
            },
        )
        val storage = TestHttpSharedOutput.FaultyStorage()
        val host = host(storage)
        val t = task(903, "/bad-$kind", host)
        t.start()

        awaitSuccess(host, 20)
        assertChecksumMatches(host, bytes)
        assertEquals(1, ep.racerRequests().size, "a chunk is raced once, even if that race failed")
        assertEquals(SIZE.toLong(), t.downloadedBytes)
        assertFalse(savedState(903, host).noRangeSupport, "a racer's bad response must not mark the server rangeless")
        assertNoThreadsLeft("a failed race")
        assertNoHandlesLeft(storage, "a failed race")
    }

    @Test
    fun pauseWhileRacerFetching_releasesEverythingAndResumes() {
        val bytes = randomData(SIZE, seed = 904)
        val healthy = AtomicBoolean(false)
        val ep = endpoint(
            "/pause", bytes,
            tail = ConnPlan(stallAfterBytes = STALL_AT, stallMs = -1),
            racer = { if (healthy.get()) ConnPlan() else ConnPlan(stallAfterBytes = 10 * 1024, stallMs = -1) },
        )
        val storage = TestHttpSharedOutput.FaultyStorage()
        val host = host(storage)
        val t = task(904, "/pause", host)
        t.start()
        assertTrue(waitFor(5000) { ep.racerRequests().isNotEmpty() }, "racer never connected")
        Thread.sleep(200) // the racer blocked in its stalled read

        t.stop()
        assertTrue(host.pauseLatch.await(5, TimeUnit.SECONDS), "pause not acknowledged")
        assertNull(host.failure)
        assertNoThreadsLeft("pause during a race")
        assertNoHandlesLeft(storage, "pause during a race")
        assertClientClosed("pause during a race")

        // The race is not saved: the state holds the two chunks, the tail where its own thread got to.
        val state = savedState(904, host)
        assertEquals(2, state.chunks.size, "racer left a chunk in the saved state")
        val tail = state.chunks.values.single { it.offset == HALF }
        assertEquals(STALL_AT, tail.downloaded.get(), "the racer's unused bytes must not be saved")
        assertTrue(tail.status.get() != ChunkStatus.Finished)

        healthy.set(true)
        val host2 = host(storage)
        task(904, "/pause", host2).resume()
        awaitSuccess(host2, 15)
        assertChecksumMatches(host2, bytes)
        assertNoThreadsLeft("resume after a paused race")
        assertNoHandlesLeft(storage, "resume after a paused race")
    }

    @Test
    fun pauseDuringGracePeriod_racerNeverConnects() {
        HttpDownloaderTask.raceGraceMs = 3000
        val bytes = randomData(SIZE, seed = 905)
        val ep = endpoint("/pause-grace", bytes, tail = ConnPlan(stallAfterBytes = STALL_AT, stallMs = -1))
        val storage = TestHttpSharedOutput.FaultyStorage()
        val host = host(storage)
        val t = task(905, "/pause-grace", host)
        t.start()
        assertTrue(waitFor(5000) { racerThreads().isNotEmpty() }, "no racer started for the stalled tail")

        t.stop()
        assertTrue(host.pauseLatch.await(5, TimeUnit.SECONDS), "pause not acknowledged")
        assertTrue(waitFor(1000) { racerThreads().isEmpty() }, "racer still waiting out its grace after pause")
        Thread.sleep(500)
        assertTrue(ep.racerRequests().isEmpty(), "racer connected after pause")
        assertNoThreadsLeft("pause during the grace period")
        assertNoHandlesLeft(storage, "pause during the grace period")
    }

    @Test
    fun deleteWhileRacerFetching_releasesEverythingAndRemovesPartFile() {
        val bytes = randomData(SIZE, seed = 906)
        val ep = endpoint(
            "/delete", bytes,
            tail = ConnPlan(stallAfterBytes = STALL_AT, stallMs = -1),
            racer = { ConnPlan(stallAfterBytes = 10 * 1024, stallMs = -1) },
        )
        val storage = TestHttpSharedOutput.FaultyStorage()
        val host = host(storage)
        val t = task(906, "/delete", host)
        t.start()
        assertTrue(waitFor(5000) { ep.racerRequests().isNotEmpty() }, "racer never connected")

        // What deleting a running download does: stop, then remove the part file.
        t.stop()
        assertTrue(host.pauseLatch.await(5, TimeUnit.SECONDS), "stop not acknowledged")
        t.deleteTemp()
        assertNoThreadsLeft("delete during a race")
        assertNoHandlesLeft(storage, "delete during a race")
        assertClientClosed("delete during a race")
        assertFalse(File(tmpDir, partFileName(906)).exists(), "part file left behind")
    }

    @Test
    fun singleSegment_neverRaces() {
        val bytes = randomData(200 * 1024, seed = 907)
        val ep = server.register("/single", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(stallAfterBytes = 100 * 1024, stallMs = 1200) else ConnPlan()
        }))
        val host = host()
        task(907, "/single", host, TestConfig(maxSegments = 1)).start()

        awaitSuccess(host, 15)
        assertDownloaded(host, bytes)
        assertEquals(1, ep.connCount.get(), "a 1-segment download must not open a racer connection")
        assertNoThreadsLeft("a 1-segment download")
    }

    @Test
    fun speedLimit_neverRaces() {
        val bytes = randomData(SIZE, seed = 908)
        val ep = endpoint("/limited", bytes, tail = ConnPlan(stallAfterBytes = STALL_AT, stallMs = 1500))
        val config = TestConfig(maxSegments = 2).apply {
            speedLimiterEnabled = true
            speedLimit = 1024 * 1024 // KB/s: on, but far above what the test moves
        }
        val host = host()
        task(908, "/limited", host, config).start()

        awaitSuccess(host, 15)
        assertChecksumMatches(host, bytes)
        assertTrue(ep.racerRequests().isEmpty(), "raced under a speed limit")
        assertNoThreadsLeft("a speed-limited download")
    }

    @Test
    fun smallFile_stalledSingleChunk_racedWithSpareSlot() {
        // Too small to split at all: the only chunk is the tail from the start.
        val bytes = randomData(200 * 1024, seed = 909)
        val ep = server.register("/small", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(stallAfterBytes = 64 * 1024, stallMs = -1) else ConnPlan()
        }))
        val storage = TestHttpSharedOutput.FaultyStorage()
        val host = host(storage)
        task(909, "/small", host, TestConfig(maxSegments = 4)).start()

        awaitSuccess(host, 15)
        assertChecksumMatches(host, bytes)
        assertEquals(listOf(64L * 1024), ep.requests.drop(1).map { it.rangeStart }, "one racer, from the stall")
        assertNoThreadsLeft("a raced single chunk")
        assertNoHandlesLeft(storage, "a raced single chunk")
    }

    private companion object {
        const val SIZE = 512 * 1024
        const val HALF = SIZE / 2L
        /** On a 64 KB fill boundary: the chunk thread writes (and counts) in 64 KB fills. */
        const val STALL_AT = 128L * 1024
    }
}
