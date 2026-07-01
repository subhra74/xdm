import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for the redundant "race" connection that rescues a segmented download stuck on a
 * slow/stalled last segment: the stall monitor spawns a second connection over the same
 * remaining bytes, the first to finish wins, and pause is handled cleanly throughout.
 *
 * Kept separate from [TestHttpSegmentedDownload] so it runs in its own JVM fork (stalled
 * connections here would otherwise leak threads/sockets into the plain tests).
 */
class TestHttpSegmentedDownloadRace : HttpDownloadTestBase() {

    @Test
    fun stalledLastSegment_raceConnectionRescuesDownload() {
        val bytes = randomData(1024 * 1024, seed = 10)
        // Connection #1 delivers a prefix then stalls forever; the race connection (#2) is fast.
        val ep = server.register("/stall", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(stallAfterBytes = 300 * 1024, stallMs = -1) else ConnPlan()
        }))
        val host = download(id = 10, path = "/stall", maxSegments = 1)

        // Would hang forever without the race; must finish quickly.
        awaitSuccess(host)
        assertDownloaded(host, bytes)
        assertEquals("final size must be exact (no double counting)", bytes.size.toLong(), host.success!!.fileSize)
        assertTrue("a race connection should have been spawned", ep.connCount.get() >= 2)
    }

    @Test
    fun raceWherePrimaryFinishesFirst_stillExactBytes() {
        val bytes = randomData(1024 * 1024, seed = 11)
        // #1 stalls only briefly (long enough to trigger a race) then finishes fast.
        // The race (#2+) is delayed so the primary wins; output must still be correct.
        val ep = server.register("/primwin", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(stallAfterBytes = 300 * 1024, stallMs = 800)
            else ConnPlan(delayBeforeMs = 4000)
        }))
        val host = download(id = 11, path = "/primwin", maxSegments = 1)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
        assertEquals(bytes.size.toLong(), host.success!!.fileSize)
        assertTrue("a race connection should have been attempted", ep.connCount.get() >= 2)
    }

    @Test
    fun raceConnectionFails_isDiscardedAndPrimaryCompletes() {
        val bytes = randomData(1024 * 1024, seed = 12)
        // #1 stalls a while then finishes. Race connections ignore the Range and return the
        // full body -> content-length mismatch -> NoResume -> race discarded, no corruption.
        server.register("/racefail", Endpoint(bytes, plan = { ctx ->
            if (ctx.connIndex == 1) ConnPlan(stallAfterBytes = 300 * 1024, stallMs = 1500)
            else ConnPlan(ignoreRange = true)
        }))
        val host = download(id = 12, path = "/racefail", maxSegments = 1)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
    }

    @Test
    fun pauseDuringRace_pausesThenResumesToExactBytes() {
        val bytes = randomData(1024 * 1024, seed = 13)
        val phase = AtomicReference("race")
        val ep = server.register("/pauserace", Endpoint(bytes, plan = { ctx ->
            when {
                phase.get() == "normal" -> ConnPlan()
                ctx.connIndex == 1 -> ConnPlan(stallAfterBytes = 300 * 1024, stallMs = -1)
                else -> ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 50) // slow race
            }
        }))

        val host1 = host()
        val task1 = newTask(id = 13, path = "/pauserace", host = host1, maxSegments = 1)
        task1.start()

        // Wait until the race connection has actually been spawned, then a touch longer.
        assertTrue("race should start", waitFor(8000) { ep.connCount.get() >= 2 })
        Thread.sleep(300)
        task1.stop()
        assertTrue("pause should fire even mid-race", host1.pauseLatch.await(5, TimeUnit.SECONDS))

        // Resume against a now-healthy server.
        phase.set("normal")
        val host2 = host()
        val task2 = newTask(id = 13, path = "/pauserace", host = host2, maxSegments = 1)
        task2.resume()
        awaitSuccess(host2)
        assertDownloaded(host2, bytes)
    }

    @Test
    fun multiSegment_lastSegmentStalls_raceCompletes() {
        val bytes = randomData(2 * 1024 * 1024, seed = 14)
        val tailStalled = AtomicBoolean(false)
        server.register("/multistall", Endpoint(bytes, plan = { ctx ->
            val tailThreshold = (ctx.total * 3) / 4
            // Stall the FIRST connection that reaches into the last quarter; serve its race fast.
            if (ctx.rangeStart >= tailThreshold && tailStalled.compareAndSet(false, true)) {
                ConnPlan(stallAfterBytes = 0, stallMs = -1)
            } else {
                ConnPlan()
            }
        }))
        val host = download(id = 14, path = "/multistall", maxSegments = 4)

        awaitSuccess(host)
        assertDownloaded(host, bytes)
    }
}
