import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case coverage for the segmented HTTP downloader: connection drops mid-transport (retry +
 * resume) and speed-limiter behaviour. Separate class => separate JVM fork (see surefire
 * reuseForks=false) so dropped sockets don't leak into other suites.
 */
class TestHttpDownloadEdgeCases : HttpDownloadTestBase() {

    private fun limited(maxSegments: Int, kbPerSec: Int) = TestConfig(maxSegments).apply {
        speedLimiterEnabled = true
        speedLimit = kbPerSec
    }

    // ---------------------------------------------------------------------------------------
    // Connection drops mid-transport
    // ---------------------------------------------------------------------------------------

    @Test
    fun connectionDrop_midTransport_resumesAndCompletes() {
        // The raw server drops the first TCP connection after 200KB (RST); the retry reconnects
        // with a Range and resumes from where it left off.
        val bytes = randomData(512 * 1024, seed = 30)
        val raw = RawDropServer(bytes, dropAfterBytes = 200 * 1024) { idx, _ -> idx == 1 }
        rawServers.add(raw)
        val host = host()
        newTaskForUrl(id = 30, url = raw.url("/drop"), host = host, config = TestConfig(maxSegments = 1)).start()

        awaitSuccess(host) // retry sleeps ~5s before resuming
        assertDownloaded(host, bytes)
        assertTrue("expected a reconnect after the drop", raw.requestedRanges.size >= 2)
    }

    @Test
    fun connectionDrop_everyEarlyAttemptDrops_stillCompletes() {
        // The first two attempts (initial + first retry) both drop; only the third survives.
        // Exercises repeated resume from an advancing offset.
        val bytes = randomData(512 * 1024, seed = 31)
        val raw = RawDropServer(bytes, dropAfterBytes = 120 * 1024) { idx, _ -> idx <= 2 }
        rawServers.add(raw)
        val host = host()
        newTaskForUrl(id = 31, url = raw.url("/dropx2"), host = host, config = TestConfig(maxSegments = 1)).start()

        awaitSuccess(host)
        assertDownloaded(host, bytes)
        assertTrue("expected multiple reconnects", raw.requestedRanges.size >= 3)
    }

    // ---------------------------------------------------------------------------------------
    // Speed limiter
    // ---------------------------------------------------------------------------------------

    @Test
    fun speedLimit_isRespectedOnPlainDownload() {
        val bytes = randomData(2 * 1024 * 1024, seed = 33)
        server.register("/limited", Endpoint(bytes))
        val host = host()
        val start = System.currentTimeMillis()
        newTask(id = 33, path = "/limited", host = host, config = limited(maxSegments = 4, kbPerSec = 512)).start()

        awaitSuccess(host)
        val elapsed = System.currentTimeMillis() - start
        assertDownloaded(host, bytes)
        // 2MB at 512 KB/s ~= 4s; assert it clearly took real throttling time (never instant).
        assertTrue("expected throttled download, took only ${elapsed}ms", elapsed >= 2500)
    }
}
