import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.http.HttpDownloaderTask
import java.util.concurrent.TimeUnit

/**
 * Regression for CODE_REVIEW B11 (HTTP): a connection that stops sending data must time out and be
 * retried from where it stopped; a server that never delivers must eventually fail the download;
 * Pause must release a chunk blocked on a stalled read.
 */
class TestHttpStalls : HttpDownloadTestBase() {

    private fun task(id: Long, path: String, host: TestDownloadHost, config: TestConfig) =
        HttpDownloaderTask(
            HttpDownloadTaskInfo(
                id = id, url = server.url(path), fileName = "f$id.bin", respectFileName = false, cookie = null,
                headers = null, origin = null, autoCategorize = false, defaultDownloadFolder = tmpDir.absolutePath,
                userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null, knownFileSize = null,
            ),
            host, CountingHttpClient(httpClientWithReadTimeout(1)), work.absolutePath, config
        ).also { started += it }

    private val started = ArrayList<HttpDownloaderTask>()

    @org.junit.After
    fun stopStarted() {
        started.forEach { runCatching { it.stop() } }
    }

    private fun chunkThreads(): Set<Thread> = Thread.getAllStackTraces().entries
        .filter { (_, st) -> st.any { it.methodName == "retrieveChunk" } }.map { it.key }.toSet()

    @Test
    fun stalledConnection_timesOutAndResumes() {
        val bytes = randomData(512 * 1024, seed = 80)
        val ep = server.register("/stall-once", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(stallAfterBytes = 100 * 1024, stallMs = -1) else ConnPlan()
        }))
        val host = host()
        task(80, "/stall-once", host, TestConfig(maxSegments = 1)).start()

        assertTrue("download hung on a stalled connection", host.latch.await(30, TimeUnit.SECONDS))
        assertDownloaded(host, bytes)
        assertTrue("expected a reconnect after the stall", ep.connCount.get() >= 2)
        assertTrue("retry must resume, not restart", ep.requests.drop(1).any { it.rangeStart > 0 })
    }

    @Test
    fun serverThatNeverSendsData_failsAfterMaxRetries() {
        val bytes = randomData(256 * 1024, seed = 81)
        server.register("/stall-always", Endpoint(bytes, plan = { ConnPlan(stallAfterBytes = 0, stallMs = -1) }))
        val host = host()
        task(81, "/stall-always", host, TestConfig(maxSegments = 1, maxRetries = 1)).start()

        assertTrue("download hung instead of failing", host.latch.await(40, TimeUnit.SECONDS))
        assertEquals(DownloadError.NetworkError, host.failure)
    }

    @Test
    fun pauseDuringStalledRead_releasesChunkThread() {
        val bytes = randomData(256 * 1024, seed = 82)
        val ep = server.register("/stall-pause", Endpoint(bytes, plan = { ConnPlan(stallAfterBytes = 50 * 1024, stallMs = -1) }))
        val host = host()
        // Long timeout: only Pause can release the read.
        val t = HttpDownloaderTask(
            HttpDownloadTaskInfo(
                id = 82, url = server.url("/stall-pause"), fileName = "f82.bin", respectFileName = false, cookie = null,
                headers = null, origin = null, autoCategorize = false, defaultDownloadFolder = tmpDir.absolutePath,
                userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null, knownFileSize = null,
            ),
            host, CountingHttpClient(httpClientWithReadTimeout(600)), work.absolutePath, TestConfig(maxSegments = 1)
        )
        started += t
        val before = chunkThreads()
        t.start()
        assertTrue(waitFor(5000) { ep.connCount.get() >= 1 && host.initInfo != null })
        Thread.sleep(500) // blocked in the stalled read

        t.stop()
        assertTrue("pause not acknowledged", host.pauseLatch.await(5, TimeUnit.SECONDS))
        val released = waitFor(3000) { (chunkThreads() - before).isEmpty() }
        assertTrue(
            "chunk thread still blocked after Pause:\n" + (chunkThreads() - before).joinToString("\n\n") { th ->
                th.name + "\n" + th.stackTrace.take(15).joinToString("\n") { "  at $it" }
            },
            released
        )
    }
}
