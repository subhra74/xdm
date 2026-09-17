import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.ChunkStatus
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
 * No network is involved: the state file and temp file are complete, so a correct resume must
 * commit the file without ever connecting (the URL points at a closed port).
 */
class TestHttpResumeCompletedChunk : HttpDownloadTestBase() {

    private val size = 256 * 1024L
    private val half = size / 2

    /** Writes the temp file + `<id>.state` exactly as a pause in the finish window leaves them. */
    private fun persistPausedState(id: Long, host: TestDownloadHost, bytes: ByteArray) {
        val tempName = "resume-$id.tmp"
        File(tmpDir, tempName).writeBytes(bytes)

        fun chunk(cid: Long, offset: Long, status: ChunkStatus) = Chunk(
            id = cid,
            offset = offset,
            length = AtomicLong(half),
            downloaded = AtomicLong(half), // fully downloaded...
            status = AtomicReference(status),
            fileHandle = AtomicReference(null),
            lastTakeOver = AtomicLong(0),
        )

        val chunks = ConcurrentHashMap<Long, Chunk>()
        chunks[1L] = chunk(1L, 0, ChunkStatus.Finished)
        chunks[2L] = chunk(2L, half, ChunkStatus.Downloading) // ...but never marked Finished

        val ctx = HttpTaskContext(
            id = id,
            chunks = chunks,
            init = AtomicBoolean(true),
            totalSize = size,
            downloaded = AtomicLong(size),
            url = "http://127.0.0.1:1/unreachable",
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

        newTaskForUrl(id, "http://127.0.0.1:1/unreachable", host, TestConfig(maxSegments = 2)).resume()

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

        val task = newTaskForUrl(id, "http://127.0.0.1:1/unreachable", host, TestConfig(maxSegments = 2))
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
}
