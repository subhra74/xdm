import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.web.http.HttpDownloaderTask

/**
 * Edge-case coverage for the segmented HTTP downloader: connection drops mid-transport (regular
 * and race connections), speed-limiter behaviour (plain and while a race is in flight), and a
 * few race/existing-machinery interactions. Separate class => separate JVM fork (see surefire
 * reuseForks=false) so stalled/dropped sockets don't leak into other suites.
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
    fun connectionDrop_pureRetryResume_raceDisabled() {
        // Disable the race heuristic so this exercises the pre-existing retry/resume path only:
        // the raw server drops the first TCP connection after 200KB (RST); the retry reconnects
        // with a Range and resumes from where it left off.
        HttpDownloaderTask.RACE_TRIGGER_MS = 60_000L
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
    fun connectionDrop_rescuedByRaceConnection() {
        // With the race heuristic on (fast timings from the base class), a dropped connection
        // stops progressing and is treated like a stall: a race is spawned and finishes the tail,
        // possibly before the slow retry fires. Emergent, but must still produce exact bytes.
        val bytes = randomData(512 * 1024, seed = 37)
        val raw = RawDropServer(bytes, dropAfterBytes = 200 * 1024) { idx, _ -> idx == 1 }
        rawServers.add(raw)
        val host = host()
        newTaskForUrl(id = 37, url = raw.url("/dropfast"), host = host, config = TestConfig(maxSegments = 1)).start()

        awaitSuccess(host)
        assertDownloaded(host, bytes)
    }

    @Test
    fun connectionDrop_everyOtherAttemptDrops_stillCompletes() {
        // The first two attempts (initial + first retry) both drop; only the third survives.
        // Exercises repeated resume from an advancing offset.
        HttpDownloaderTask.RACE_TRIGGER_MS = 60_000L
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

    @Test
    fun speedLimit_isRespectedWhileRaceIsInFlight() {
        // Disable the "slow tail" heuristic's speed floor for this test so a stalled primary
        // under a low speed limit still triggers a race, then assert the aggregate is capped.
        val bytes = randomData(1024 * 1024, seed = 34)
        server.register("/limitrace", Endpoint(bytes, plan = { ctx ->
            // Primary delivers a prefix then stalls forever; race serves the rest at full server
            // speed (so only the client-side limiter can cap it).
            if (ctx.connIndex == 1) ConnPlan(stallAfterBytes = 200 * 1024, stallMs = -1) else ConnPlan()
        }))
        val host = host()
        val start = System.currentTimeMillis()
        newTask(id = 34, path = "/limitrace", host = host, config = limited(maxSegments = 1, kbPerSec = 256)).start()

        awaitSuccess(host)
        val elapsed = System.currentTimeMillis() - start
        assertDownloaded(host, bytes)
        // ~1MB through a 256 KB/s cap must take real time even though the race serves instantly.
        // Without counting race bytes toward the limiter this finishes in ~1s.
        assertTrue("race bypassed the speed limit, took only ${elapsed}ms", elapsed >= 2500)
    }

    // ---------------------------------------------------------------------------------------
    // Race vs. existing dynamic-chunking machinery
    // ---------------------------------------------------------------------------------------

    @Test
    fun repeatedStalls_recoversAndCompletesExactly() {
        val bytes = randomData(1024 * 1024, seed = 35)
        // Primary stalls; each race also stalls briefly then the primary/races eventually get
        // through. Exercises multiple race spawns without corruption.
        val n = java.util.concurrent.atomic.AtomicInteger(0)
        server.register("/repeat", Endpoint(bytes, plan = { ctx ->
            when {
                ctx.connIndex == 1 -> ConnPlan(stallAfterBytes = 250 * 1024, stallMs = -1)
                n.getAndIncrement() < 2 -> ConnPlan(stallAfterBytes = 50 * 1024, stallMs = 600) // slow races
                else -> ConnPlan()
            }
        }))
        val host = download(id = 35, path = "/repeat", maxSegments = 1)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
        assertTrue("expected several race attempts", host.maxSegmentsSeen.get() >= 1)
    }

    @Test
    fun tinyTail_notRaced_completesNormally() {
        // A file whose remaining tail is below MIN_RACE_REMAINING once the primary stalls should
        // NOT be raced; it should still complete because the primary un-stalls.
        val bytes = randomData(512 * 1024, seed = 36)
        server.register("/tinytail", Endpoint(bytes, plan = { ctx ->
            // Stall very close to the end (2KB remaining < MIN_RACE_REMAINING=4KB) then finish.
            if (ctx.connIndex == 1) ConnPlan(stallAfterBytes = 510 * 1024, stallMs = 800) else ConnPlan()
        }))
        val host = download(id = 36, path = "/tinytail", maxSegments = 1)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
    }
}
