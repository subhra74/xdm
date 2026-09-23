package xdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import xdm.integration.EventChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The long poll that lets the extension see state changes - and XDM exiting - without waiting for
 * its next alarm.
 *
 * Methods run in name order because [EventChannel] is a singleton and `zz_shutdown` latches it into
 * the shutting-down state for the rest of the JVM.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EventChannelTest {

    private fun awaitInThread(clientId: String, since: Long, timeoutMs: Long): ArrayBlockingQueue<EventChannel.Outcome> {
        val result = ArrayBlockingQueue<EventChannel.Outcome>(1)
        Thread { result.put(EventChannel.await(clientId, since, timeoutMs)) }.start()
        return result
    }

    private fun take(q: ArrayBlockingQueue<EventChannel.Outcome>, waitMs: Long = 5000): EventChannel.Outcome =
        q.poll(waitMs, TimeUnit.MILLISECONDS) ?: throw AssertionError("poll did not return in ${waitMs}ms")

    @Test
    fun a_clientBehindCurrentVersionIsAnsweredWithoutParking() {
        EventChannel.notifyChanged()
        assertEquals(EventChannel.Outcome.CHANGED, EventChannel.await("a", 0, 50))
    }

    @Test
    fun b_upToDateClientParksUntilTimeout() {
        val started = System.currentTimeMillis()
        assertEquals(EventChannel.Outcome.TIMEOUT, EventChannel.await("b", EventChannel.currentVersion, 300))
        assertTrue("should have waited for the timeout", System.currentTimeMillis() - started >= 250)
    }

    @Test
    fun c_parkedPollIsReleasedByAStateChange() {
        val result = awaitInThread("c", EventChannel.currentVersion, 10_000)
        // Give the poll time to park, then change something.
        Thread.sleep(200)
        EventChannel.notifyChanged()
        assertEquals(EventChannel.Outcome.CHANGED, take(result))
    }

    @Test
    fun d_secondPollFromSameClientSupersedesTheFirst() {
        val first = awaitInThread("d", EventChannel.currentVersion, 10_000)
        Thread.sleep(200)
        val second = awaitInThread("d", EventChannel.currentVersion, 400)
        assertEquals(EventChannel.Outcome.SUPERSEDED, take(first))
        // The replacement keeps waiting on its own.
        assertEquals(EventChannel.Outcome.TIMEOUT, take(second))
    }

    @Test
    fun e_waitersAreCappedSoOneClientCannotParkEveryThread() {
        val version = EventChannel.currentVersion
        val parked = (1..8).map { awaitInThread("cap-$it", version, 2000) }
        Thread.sleep(300)
        assertEquals(EventChannel.Outcome.BUSY, EventChannel.await("cap-overflow", version, 2000))
        parked.forEach { assertEquals(EventChannel.Outcome.TIMEOUT, take(it)) }
        // Once they drain, a new client is accepted again.
        assertEquals(EventChannel.Outcome.TIMEOUT, EventChannel.await("cap-after", EventChannel.currentVersion, 100))
    }

    @Test
    fun zz_shutdownReleasesParkedPollsWithBye() {
        val parked = awaitInThread("bye", EventChannel.currentVersion, 10_000)
        val late = CountDownLatch(1)
        Thread.sleep(200)
        EventChannel.shutdown()
        assertEquals(EventChannel.Outcome.BYE, take(parked))
        // A poll that arrives after shutdown is told immediately rather than parked.
        Thread { assertEquals(EventChannel.Outcome.BYE, EventChannel.await("late", 0, 10_000)); late.countDown() }.start()
        assertTrue("late poll should not park", late.await(2, TimeUnit.SECONDS))
    }
}
