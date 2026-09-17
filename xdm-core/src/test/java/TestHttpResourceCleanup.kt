import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.ChunkStatus
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
 * Regression for CODE_REVIEW B10 (HTTP side): the per-task HTTP client must be closed whenever the
 * task ends - pause, failure, and the resume path that finds every chunk already finished - not
 * only when a fresh download succeeds.
 */
class TestHttpResourceCleanup : HttpDownloadTestBase() {

    private fun task(id: Long, url: String, host: TestDownloadHost, client: CountingHttpClient) =
        HttpDownloaderTask(
            HttpDownloadTaskInfo(
                id = id, url = url, fileName = "file-$id.bin", respectFileName = false,
                cookie = null, headers = null, origin = null, autoCategorize = false,
                defaultDownloadFolder = tmpDir.absolutePath, userSelectedDownloadFolder = null,
                maxPiece = 0, authInfo = null, knownFileSize = null,
            ),
            host, client, work.absolutePath, TestConfig(maxSegments = 2)
        )

    private fun assertClosed(client: CountingHttpClient, what: String) =
        assertTrue("HTTP client not closed after $what", waitFor(3000) { client.closeCount.get() >= 1 })

    @Test
    fun pause_closesHttpClient() {
        val bytes = randomData(2 * 1024 * 1024, seed = 50)
        server.register("/slow", Endpoint(bytes, plan = { ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 50) }))
        val host = host()
        val client = CountingHttpClient()
        val t = task(50, server.url("/slow"), host, client)
        t.start()
        assertTrue("download never started", waitFor(5000) { host.initInfo != null })

        t.stop()
        assertTrue("pause not acknowledged", host.pauseLatch.await(5, TimeUnit.SECONDS))
        assertClosed(client, "pause")
    }

    @Test
    fun failure_closesHttpClient() {
        val bytes = randomData(64 * 1024, seed = 51)
        server.register("/fail", Endpoint(bytes, plan = { ConnPlan(failStatus = 500) }))
        val host = host()
        val client = CountingHttpClient()
        val t = task(51, server.url("/fail"), host, client)
        try {
            t.start()
            awaitDone(host, 10)
            assertNotNull("expected a failure", host.failure)
            assertClosed(client, "failure")
        } finally {
            t.stop()
        }
    }

    @Test
    fun resumeWithAllChunksFinished_closesHttpClient() {
        val id = 52L
        val bytes = randomData(64 * 1024, seed = 52)
        val host = host()
        val tempName = "done-$id.tmp"
        File(tmpDir, tempName).writeBytes(bytes)
        val size = bytes.size.toLong()
        val chunks = ConcurrentHashMap<Long, Chunk>()
        chunks[1L] = Chunk(
            id = 1L, offset = 0, length = AtomicLong(size), downloaded = AtomicLong(size),
            status = AtomicReference(ChunkStatus.Finished), fileHandle = AtomicReference(null),
            lastTakeOver = AtomicLong(0),
        )
        saveState(
            HttpTaskContext(
                id = id, chunks = chunks, init = AtomicBoolean(true), totalSize = size,
                downloaded = AtomicLong(size), url = "http://127.0.0.1:1/unused", contentType = null,
                headers = null, cookie = null, stopFlag = AtomicBoolean(false), completed = AtomicBoolean(false),
                tempFileName = tempName, tempFileCreated = AtomicBoolean(true), diskError = AtomicBoolean(false),
                downloadHost = host, tempFolder = tmpDir.absolutePath,
            ),
            work.absolutePath
        )

        val client = CountingHttpClient()
        task(id, "http://127.0.0.1:1/unused", host, client).resume()
        awaitSuccess(host, 10)
        assertClosed(client, "resume found every chunk finished")
    }
}
