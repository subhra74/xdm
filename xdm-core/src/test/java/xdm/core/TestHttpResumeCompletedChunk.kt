package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.http.HttpChunkRetriever
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.http.HttpTaskContext
import xdm.core.downloaders.web.saveState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression for CODE_REVIEW B1: resuming a download whose chunk has all its bytes on disk but
 * was persisted without `Finished` (pause/crash between the last write and the status update).
 *
 * `HttpChunkRetriever.isAlreadyDone()` calls `onChunkFinished()` - which takes the write lock -
 * while still holding the read lock. `ReentrantReadWriteLock` cannot upgrade, so the chunk thread
 * parks on itself and every later `write {}` (including `stop()`) blocks behind it.
 *
 * Fixed in two layers: such chunks are marked Finished when the state is restored, and
 * `isAlreadyDone` takes the write lock and marks the chunk itself (tested directly below).
 * When every chunk is complete, a correct resume must commit the file without ever connecting (the
 * URL points at a closed port); otherwise only the incomplete chunks may be requested.
 */
class TestHttpResumeCompletedChunk : HttpDownloadTestBase() {

    private val size = 256 * 1024L
    private val half = size / 2

    /** Persisted progress of one of the two half-size chunks. */
    private data class SavedChunk(val status: ChunkStatus, val downloaded: Long)

    /**
     * Writes the temp file + `<id>.state` exactly as a pause in the finish window leaves them. By
     * default the first chunk is Finished and the second has all its bytes but was never marked
     * Finished. Bytes a chunk has not downloaded are zeroed in the temp file.
     */
    private fun persistPausedState(
        id: Long,
        host: TestDownloadHost,
        bytes: ByteArray,
        url: String = UNREACHABLE,
        first: SavedChunk = SavedChunk(ChunkStatus.Finished, half),
        second: SavedChunk = SavedChunk(ChunkStatus.Downloading, half),
    ) {
        val tempName = "resume-$id.tmp"
        val onDisk = bytes.copyOf()
        onDisk.fill(0, first.downloaded.toInt(), half.toInt())
        onDisk.fill(0, (half + second.downloaded).toInt(), size.toInt())
        File(tmpDir, tempName).writeBytes(onDisk)

        fun chunk(cid: Long, offset: Long, saved: SavedChunk) = Chunk(
            id = cid,
            offset = offset,
            length = AtomicLong(half),
            downloaded = AtomicLong(saved.downloaded),
            status = AtomicReference(saved.status),
            fileHandle = AtomicReference(null),
            lastTakeOver = AtomicLong(0),
        )

        val chunks = ConcurrentHashMap<Long, Chunk>()
        chunks[1L] = chunk(1L, 0, first)
        chunks[2L] = chunk(2L, half, second)

        val ctx = HttpTaskContext(
            id = id,
            chunks = chunks,
            init = AtomicBoolean(true),
            totalSize = size,
            downloaded = AtomicLong(first.downloaded + second.downloaded),
            url = url,
            contentType = null,
            headers = null,
            cookie = null,
            stopFlag = AtomicBoolean(false),
            completed = AtomicBoolean(false),
            tempFileName = tempName,
            tempFileCreated = AtomicBoolean(true),
            diskError = AtomicBoolean(false),
            downloadHost = host,
            tempFolder = tmpDir.absolutePath,
        )
        saveState(ctx, work.absolutePath)
    }

    /** Threads parked inside `isAlreadyDone` - direct evidence of the read→write upgrade. */
    private fun stuckChunkThreads(): String =
        Thread.getAllStackTraces().entries
            .filter { (t, st) ->
                t.state == Thread.State.WAITING && st.any { it.methodName == "isAlreadyDone" }
            }
            .joinToString("\n\n") { (t, st) ->
                "${t.name} (${t.state})\n" + st.take(12).joinToString("\n") { "    at $it" }
            }

    @Test
    fun resume_chunkCompleteButNotFinished_commitsFile() {
        val id = 900L
        val bytes = randomData(size.toInt(), seed = 900)
        val host = host()
        persistPausedState(id, host, bytes)

        newTaskForUrl(id, UNREACHABLE, host, TestConfig(maxSegments = 2)).resume()

        val settled = host.latch.await(10, TimeUnit.SECONDS)
        assertTrue(
            settled,
            "resume never completed; chunk thread deadlocked upgrading read->write lock:\n" +
                stuckChunkThreads())
        awaitSuccess(host, 0)
        assertDownloaded(host, bytes)
    }

    @Test
    fun resume_chunkCompleteButNotFinished_pauseStillWorks() {
        val id = 901L
        val bytes = randomData(size.toInt(), seed = 901)
        val host = host()
        persistPausedState(id, host, bytes)

        val task = newTaskForUrl(id, UNREACHABLE, host, TestConfig(maxSegments = 2))
        task.resume()
        // Wait until the chunk thread has reached (and, if buggy, parked in) isAlreadyDone.
        waitFor(3000) { host.latch.count == 0L || stuckChunkThreads().isNotEmpty() }

        task.stop()
        val settled = host.latch.count == 0L || host.pauseLatch.await(5, TimeUnit.SECONDS)
        assertTrue(
            settled,
            "stop() never delivered onDownloadPaused/onDownloadSuccess; write lock is held hostage:\n" +
                stuckChunkThreads())
    }

    @Test
    fun resume_oneChunkCompleteOtherPending_downloadsOnlyTheRest() {
        val id = 902L
        val bytes = randomData(size.toInt(), seed = 902)
        val ep = server.register("/partial", Endpoint(bytes))
        val host = host()
        persistPausedState(
            id, host, bytes,
            url = server.url("/partial"),
            first = SavedChunk(ChunkStatus.Downloading, half), // complete, never marked Finished
            second = SavedChunk(ChunkStatus.Ready, 0),
        )

        newTaskForUrl(id, server.url("/partial"), host, TestConfig(maxSegments = 2)).resume()

        val settled = host.latch.await(15, TimeUnit.SECONDS)
        assertTrue( settled,"resume never completed; stuck chunk threads:\n" + stuckChunkThreads())
        awaitSuccess(host, 0)
        assertDownloaded(host, bytes)
        assertTrue(
            ep.requests.none { it.rangeStart < half }
        , "complete chunk was downloaded again: ${ep.requests.map { it.rangeStart }}")
    }

    /**
     * The restore-time fix hides `isAlreadyDone` on resume, so drive it directly: undo the
     * normalization on the loaded context and run a retriever for the complete chunk. It must mark
     * the chunk Finished and commit instead of deadlocking or leaving the download at 100%.
     */
    @Test
    fun isAlreadyDone_completeChunkNotFinished_finishesWithoutRestoreFix() {
        val id = 903L
        val bytes = randomData(size.toInt(), seed = 903)
        val host = host()
        persistPausedState(id, host, bytes)

        val config = TestConfig(maxSegments = 2)
        val task = newTaskForUrl(id, UNREACHABLE, host, config)
        val ctx = HttpDownloaderTask::class.java.getDeclaredField("context")
            .apply { isAccessible = true }.get(task) as HttpTaskContext
        ctx.chunks[2L]!!.status.set(ChunkStatus.Downloading) // as if the restore fix had not run

        val retriever = Thread { HttpChunkRetriever(2L, ctx, task, config).retrieveChunk() }
            .apply { isDaemon = true; start() }

        val settled = host.latch.await(10, TimeUnit.SECONDS)
        assertTrue( settled,"isAlreadyDone did not finish the download; stuck:\n" + stuckChunkThreads())
        awaitSuccess(host, 0)
        assertDownloaded(host, bytes)
        assertEquals(ChunkStatus.Finished, ctx.chunks[2L]!!.status.get())
        retriever.join(2000)
    }

    /**
     * A download of unknown size that finished but failed to publish is saved as completed. Resume
     * must only publish it again: starting over, as an unfinished download of unknown size does,
     * would fetch the whole file a second time.
     */
    @Test
    fun resume_completedDownloadOfUnknownSize_publishesWithoutDownloading() {
        val id = 904L
        val bytes = randomData(size.toInt(), seed = 904)
        val ep = server.register("/unknown-size", Endpoint(bytes))
        val host = host()
        val tempName = "resume-$id.tmp"
        File(tmpDir, tempName).writeBytes(bytes)
        val chunks = ConcurrentHashMap<Long, Chunk>()
        chunks[1L] = Chunk(
            id = 1L,
            offset = 0,
            length = AtomicLong(0),
            downloaded = AtomicLong(size),
            status = AtomicReference(ChunkStatus.Finished),
            fileHandle = AtomicReference(null),
            lastTakeOver = AtomicLong(0),
        )
        val ctx = HttpTaskContext(
            id = id,
            chunks = chunks,
            init = AtomicBoolean(true),
            totalSize = null,
            downloaded = AtomicLong(size),
            url = server.url("/unknown-size"),
            contentType = null,
            headers = null,
            cookie = null,
            stopFlag = AtomicBoolean(false),
            completed = AtomicBoolean(true),
            tempFileName = tempName,
            tempFileCreated = AtomicBoolean(true),
            diskError = AtomicBoolean(true),
            downloadHost = host,
            tempFolder = tmpDir.absolutePath,
        )
        saveState(ctx, work.absolutePath)

        newTaskForUrl(id, server.url("/unknown-size"), host, TestConfig(maxSegments = 2)).resume()

        awaitSuccess(host, timeoutSec = 10)
        assertDownloaded(host, bytes)
        assertEquals(0, ep.connCount.get(), "a completed download was fetched again")
    }

    /**
     * Resume flips the restored segments to Downloading and starts their threads one by one. If a
     * segment's thread runs splitChuck before that loop ends, splitChuck restarts the segments that
     * are still Failed, and the loop then starts them again: two threads share one `downloaded`
     * counter but each writes only its own bytes, so the segment ends Finished with part of its range
     * never written.
     *
     * Segment 1 is restored complete but not Finished (undoing the restore fix, as above), so its
     * thread reaches splitChuck through isAlreadyDone, with no network round trip, while the loop is
     * still starting the failed segments.
     */
    @Test
    fun resume_failedSegments_eachStartOnce() {
        val id = 905L
        val segments = 24
        val segLen = 512 * 1024
        val bytes = randomData(segments * segLen, seed = 905)
        // Slow enough that two threads on one segment overlap rather than one finishing it alone.
        val ep = server.register("/failed-segments", Endpoint(bytes, plan = {
            ConnPlan(throttleChunk = 32 * 1024, throttleSleepMs = 5)
        }))

        // Load the engine and OkHttp classes first, so class loading cannot hold segment 1's thread
        // back until the resume loop is over.
        server.register("/warm-up", Endpoint(randomData(64 * 1024, seed = 906)))
        val warmHost = host()
        newTaskForUrl(906L, server.url("/warm-up"), warmHost, TestConfig(maxSegments = 1)).start()
        awaitSuccess(warmHost)

        val host = host()
        val tempName = "resume-$id.tmp"
        File(tmpDir, tempName).writeBytes(bytes.copyOf().also { it.fill(0, segLen, it.size) })
        val chunks = ConcurrentHashMap<Long, Chunk>()
        for (i in 0 until segments) {
            chunks[i + 1L] = Chunk(
                id = i + 1L,
                offset = i.toLong() * segLen,
                length = AtomicLong(segLen.toLong()),
                downloaded = AtomicLong(if (i == 0) segLen.toLong() else 0L),
                status = AtomicReference(if (i == 0) ChunkStatus.Downloading else ChunkStatus.Failed),
                fileHandle = AtomicReference(null),
                lastTakeOver = AtomicLong(0),
            )
        }
        val saved = HttpTaskContext(
            id = id,
            chunks = chunks,
            init = AtomicBoolean(true),
            totalSize = bytes.size.toLong(),
            downloaded = AtomicLong(segLen.toLong()),
            url = server.url("/failed-segments"),
            contentType = null,
            headers = null,
            cookie = null,
            stopFlag = AtomicBoolean(false),
            completed = AtomicBoolean(false),
            tempFileName = tempName,
            tempFileCreated = AtomicBoolean(true),
            diskError = AtomicBoolean(false),
            downloadHost = host,
            tempFolder = tmpDir.absolutePath,
        )
        saveState(saved, work.absolutePath)

        val task = newTaskForUrl(id, server.url("/failed-segments"), host, TestConfig(maxSegments = 32))
        val ctx = HttpDownloaderTask::class.java.getDeclaredField("context")
            .apply { isAccessible = true }.get(task) as HttpTaskContext
        ctx.chunks[1L]!!.status.set(ChunkStatus.Downloading) // as if the restore fix had not run
        task.resume()

        awaitSuccess(host, timeoutSec = 60)
        val startedTwice = ep.requests.map { it.rangeStart }
            .filter { it % segLen == 0L }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys.map { it / segLen + 1 }
        assertAll(
            { assertTrue(startedTwice.isEmpty(), "segments started on two threads: $startedTwice") },
            { assertDownloaded(host, bytes) },
        )
    }

    private fun segment(cid: Long, offset: Long, length: Long, downloaded: Long, status: ChunkStatus) = Chunk(
        id = cid,
        offset = offset,
        length = AtomicLong(length),
        downloaded = AtomicLong(downloaded),
        status = AtomicReference(status),
        fileHandle = AtomicReference(null),
        lastTakeOver = AtomicLong(0),
    )

    /** Writes [onDisk] as the temp file and a `.state` of a download that size, made of [segments]. */
    private fun persistState(id: Long, host: TestDownloadHost, url: String, onDisk: ByteArray, segments: List<Chunk>) {
        val tempName = "resume-$id.tmp"
        File(tmpDir, tempName).writeBytes(onDisk)
        val ctx = HttpTaskContext(
            id = id,
            chunks = ConcurrentHashMap(segments.associateBy { it.id }),
            init = AtomicBoolean(true),
            totalSize = onDisk.size.toLong(),
            downloaded = AtomicLong(segments.sumOf { it.downloaded.get() }),
            url = url,
            contentType = null,
            headers = null,
            cookie = null,
            stopFlag = AtomicBoolean(false),
            completed = AtomicBoolean(false),
            tempFileName = tempName,
            tempFileCreated = AtomicBoolean(true),
            diskError = AtomicBoolean(false),
            downloadHost = host,
            tempFolder = tmpDir.absolutePath,
        )
        saveState(ctx, work.absolutePath)
    }

    /**
     * The segments leave 64 KB after the first half uncovered, as an accounting bug would. When the
     * last one finishes the file must not be published: those bytes were never downloaded.
     */
    @Test
    fun finish_segmentsLeaveGap_isNotPublished() {
        val id = 907L
        val bytes = randomData(size.toInt(), seed = 907)
        val gap = 64 * 1024L
        server.register("/gap", Endpoint(bytes))
        val host = host()
        persistState(
            id, host, server.url("/gap"),
            onDisk = bytes.copyOf().also { it.fill(0, half.toInt(), size.toInt()) },
            segments = listOf(
                segment(1L, 0, half, half, ChunkStatus.Finished),
                segment(2L, half + gap, size - half - gap, 0, ChunkStatus.Ready),
            ),
        )

        newTaskForUrl(id, server.url("/gap"), host, TestConfig(maxSegments = 2)).resume()

        awaitDone(host, 15)
        assertNull(host.success, "a file with a gap was published")
        assertEquals(DownloadError.InternalError, host.failure)
    }

    /**
     * Saved as finished, but the segments leave a gap. Resume must not publish that file: it starts
     * over and downloads the whole file again.
     */
    @Test
    fun resume_finishedWithGap_startsOver() {
        val id = 908L
        val bytes = randomData(size.toInt(), seed = 908)
        val gap = 64 * 1024L
        val ep = server.register("/gap-finished", Endpoint(bytes))
        val host = host()
        persistState(
            id, host, server.url("/gap-finished"),
            onDisk = bytes.copyOf().also { it.fill(0, half.toInt(), (half + gap).toInt()) },
            segments = listOf(
                segment(1L, 0, half, half, ChunkStatus.Finished),
                segment(2L, half + gap, size - half - gap, size - half - gap, ChunkStatus.Finished),
            ),
        )

        newTaskForUrl(id, server.url("/gap-finished"), host, TestConfig(maxSegments = 2)).resume()

        awaitSuccess(host, 15)
        assertDownloaded(host, bytes)
        assertTrue(ep.connCount.get() > 0, "the file was not downloaded again")
    }

    /**
     * The restored download has more unfinished segments (8, all failed) than its segment count, the
     * number saved with it (2, while the global setting says 8). Resume must run at most that many at a
     * time; the rest wait their turn.
     */
    @Test
    fun resume_moreSegmentsThanItsCount_runsAtMostThatMany() {
        val id = 909L
        val segLen = 256 * 1024L
        val bytes = randomData((8 * segLen).toInt(), seed = 909)
        server.register("/limit", Endpoint(bytes, plan = { ConnPlan(throttleChunk = 32 * 1024, throttleSleepMs = 10) }))
        val host = host()
        persistState(
            id, host, server.url("/limit"),
            onDisk = ByteArray(bytes.size),
            segments = (0 until 8).map { segment(it + 1L, it * segLen, segLen, 0, ChunkStatus.Failed) },
        )

        val task = newTaskForUrl(id, server.url("/limit"), host, TestConfig(maxSegments = 8), maxPiece = 2)
        val ctx = HttpDownloaderTask::class.java.getDeclaredField("context")
            .apply { isAccessible = true }.get(task) as HttpTaskContext
        val maxRunning = AtomicInteger(0)
        val watching = AtomicBoolean(true)
        val watcher = Thread {
            while (watching.get()) {
                val running = ctx.chunks.values.count {
                    it.status.get() == ChunkStatus.Downloading || it.status.get() == ChunkStatus.Ready
                }
                maxRunning.updateAndGet { maxOf(it, running) }
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; start() }
        try {
            task.resume()
            awaitSuccess(host, 30)
        } finally {
            watching.set(false)
            watcher.join(1000)
        }
        assertDownloaded(host, bytes)
        assertTrue(maxRunning.get() <= 2, "${maxRunning.get()} segments ran at once with a segment count of 2")
    }

    private companion object {
        const val UNREACHABLE = "http://127.0.0.1:1/unreachable"
    }
}
