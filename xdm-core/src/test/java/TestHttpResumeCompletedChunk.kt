import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
            "resume never completed; chunk thread deadlocked upgrading read->write lock:\n" +
                stuckChunkThreads(),
            settled
        )
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
            "stop() never delivered onDownloadPaused/onDownloadSuccess; write lock is held hostage:\n" +
                stuckChunkThreads(),
            settled
        )
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
        assertTrue("resume never completed; stuck chunk threads:\n" + stuckChunkThreads(), settled)
        awaitSuccess(host, 0)
        assertDownloaded(host, bytes)
        assertTrue(
            "complete chunk was downloaded again: ${ep.requests.map { it.rangeStart }}",
            ep.requests.none { it.rangeStart < half }
        )
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
        assertTrue("isAlreadyDone did not finish the download; stuck:\n" + stuckChunkThreads(), settled)
        awaitSuccess(host, 0)
        assertDownloaded(host, bytes)
        assertEquals(ChunkStatus.Finished, ctx.chunks[2L]!!.status.get())
        retriever.join(2000)
    }

    private companion object {
        const val UNREACHABLE = "http://127.0.0.1:1/unreachable"
    }
}
