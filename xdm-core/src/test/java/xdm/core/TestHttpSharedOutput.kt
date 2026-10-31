// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.partFileName
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.storage.LocalStorage
import xdm.core.storage.PositionalOutput
import xdm.core.storage.Storage
import xdm.core.storage.StorageFile
import xdm.core.storage.StorageFolder
import java.io.File
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * The part file's shared write handle ([PositionalOutput]): one handle per session whatever the chunks do
 * (splits, takeovers, retries), closed before a pause, a failure or the publish is reported, and the file
 * intact through pauses at random moments and disk errors (simulated with [FaultyStorage]).
 */
class TestHttpSharedOutput : HttpDownloadTestBase() {

    /**
     * [LocalStorage] that counts the shared handles it opens and closes, and can fail like a disk: when
     * opening, or once [failAfterBytes] were written ("disk full"). [writeDelayMs] slows each write, so a
     * pause finds writes in flight. [beforeWrite] sees every write first, with its position and length.
     */
    class FaultyStorage(
        private val failAfterBytes: Long = -1,
        private val failOnOpen: Boolean = false,
        private val writeDelayMs: Long = 0,
        private val beforeWrite: ((position: Long, len: Int) -> Unit)? = null,
    ) : Storage {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val written = AtomicLong()
        val forces = AtomicInteger()
        val open: Int get() = opened.get() - closed.get()

        override fun folder(location: String): StorageFolder = Folder(LocalStorage.folder(location))

        private inner class Folder(private val real: StorageFolder) : StorageFolder by real {
            override fun child(name: String): StorageFolder = Folder(real.child(name))
            override fun file(name: String): StorageFile = StorageFile(this, name)

            override fun openPositional(name: String, create: Boolean, length: Long?): PositionalOutput {
                if (failOnOpen) throw IOException("Simulated: cannot open $name")
                return Output(real.openPositional(name, create, length)).also { opened.incrementAndGet() }
            }
        }

        private inner class Output(private val real: PositionalOutput) : PositionalOutput by real {
            override fun write(position: Long, b: ByteArray, off: Int, len: Int) {
                beforeWrite?.invoke(position, len)
                if (writeDelayMs > 0) Thread.sleep(writeDelayMs)
                if (failAfterBytes >= 0 && written.get() + len > failAfterBytes) {
                    throw IOException("Simulated: no space left on device")
                }
                real.write(position, b, off, len)
                written.addAndGet(len.toLong())
            }

            override fun force() {
                forces.incrementAndGet()
                real.force()
            }

            override fun close() {
                real.close()
                closed.incrementAndGet()
            }
        }
    }

    private fun throttled(path: String, bytes: ByteArray, sliceKb: Int = 16, sleepMs: Long = 20) =
        server.register(path, Endpoint(bytes, plan = { ConnPlan(throttleChunk = sliceKb * 1024, throttleSleepMs = sleepMs) }))

    /** Sessions that wrote data and were then interrupted: evidence the rounds did not pass trivially. */
    private var interruptedMidway = 0

    /**
     * Starts (or resumes) [id], lets it run [runMs] past its first write, pauses it and checks the handle was
     * closed by then. Timed from the first write, not the start, so a slow machine still pauses mid-write.
     */
    private fun runAndPause(id: Long, path: String, first: Boolean, runMs: Long, storage: FaultyStorage): TestDownloadHost {
        val host = host(storage)
        val task = newTask(id, path, host, TestConfig(maxSegments = 8))
        if (first) task.start() else task.resume()
        assertTrue(waitFor(10_000) { storage.written.get() > 0 || host.latch.count == 0L }, "nothing written")
        if (host.latch.await(runMs, TimeUnit.MILLISECONDS)) return host
        task.stop()
        assertTrue(host.pauseLatch.await(10, TimeUnit.SECONDS), "pause reported")
        assertEquals(0, storage.open, "the shared handle is closed when the pause is reported")
        assertTrue(storage.opened.get() <= 1, "at most one handle per session, got ${storage.opened.get()}")
        if (storage.written.get() > 0) interruptedMidway++
        return host
    }

    /** Splits and takeovers on 8 connections all write through one handle, closed before the publish. */
    @Test
    fun segmentedDownload_oneHandle_closedBeforePublish() {
        val bytes = randomData(6 * 1024 * 1024, seed = 1)
        throttled("/one", bytes)
        val storage = FaultyStorage()
        val host = host(storage)
        val openAtCommit = AtomicInteger(-1)
        host.onCommit = { openAtCommit.set(storage.open) }
        newTask(1, "/one", host, TestConfig(maxSegments = 8)).start()
        awaitSuccess(host, 60)
        assertTrue(host.maxSegmentsSeen.get() > 1, "the download was segmented")
        assertEquals(1, storage.opened.get(), "one shared handle for the whole download")
        assertEquals(0, openAtCommit.get(), "closed before the publish")
        assertEquals(0, storage.open, "nothing left open")
        assertChecksumMatches(host, bytes)
    }

    /**
     * The periodic sync runs on time: a download running for several intervals forces its data (and saves
     * its progress) more than once before the final force at completion.
     */
    @Test
    fun periodicSync_onTime() {
        val bytes = randomData(4 * 1024 * 1024, seed = 12)
        throttled("/timed", bytes, sliceKb = 8, sleepMs = 40)
        val storage = FaultyStorage()
        val host = host(storage)
        val forcesAtCommit = AtomicInteger(-1)
        host.onCommit = { forcesAtCommit.set(storage.forces.get()) }
        val interval = HttpDownloaderTask.syncIntervalMs
        HttpDownloaderTask.syncIntervalMs = 300
        try {
            newTask(12, "/timed", host, TestConfig(maxSegments = 8)).start()
            awaitSuccess(host, 60)
        } finally {
            HttpDownloaderTask.syncIntervalMs = interval
        }
        assertTrue(forcesAtCommit.get() >= 3, "periodic forces plus the final one, got ${forcesAtCommit.get()}")
        assertChecksumMatches(host, bytes)
    }

    /** Not on bytes: 72 MB, more than the old 64 MB trigger, inside one interval gets only the final force. */
    @Test
    fun periodicSync_notOnBytes() {
        val bytes = randomData(72 * 1024 * 1024, seed = 13)
        server.register("/big", Endpoint(bytes))
        val storage = FaultyStorage()
        val host = host(storage)
        val forcesAtCommit = AtomicInteger(-1)
        host.onCommit = { forcesAtCommit.set(storage.forces.get()) }
        newTask(13, "/big", host, TestConfig(maxSegments = 8)).start()
        awaitSuccess(host, 60)
        assertEquals(1, forcesAtCommit.get(), "only the completion's force")
        assertChecksumMatches(host, bytes)
    }

    /**
     * Holds the first write of the second chunk (the first thread whose first write is not at offset 0) until
     * the first chunk writes over that offset, which it can only do once it took the held chunk over: the
     * held chunk has downloaded nothing, so it is the one a takeover removes. Then lets the held write go on,
     * or throws what the chunk's closed view would ([closeInstead]).
     */
    private class HoldTakenOverWrite(private val closeInstead: Boolean) {
        private val firstWrite = ConcurrentHashMap<Thread, Long>()
        private val holding = AtomicBoolean()
        @Volatile
        private var heldAt = -1L
        @Volatile
        private var heldThread: Thread? = null
        val takenOver = CountDownLatch(1)
        val released = AtomicBoolean()

        fun beforeWrite(position: Long, len: Int) {
            val thread = Thread.currentThread()
            if (firstWrite.putIfAbsent(thread, position) == null && position > 0 && holding.compareAndSet(false, true)) {
                heldThread = thread
                heldAt = position
                assertTrue(takenOver.await(60, TimeUnit.SECONDS), "the first chunk never reached the held one")
                released.set(true)
                if (closeInstead) throw ClosedChannelException()
                return
            }
            val at = heldAt
            if (at >= 0 && thread !== heldThread && position <= at && at < position + len) takenOver.countDown()
        }
    }

    /**
     * A chunk taken over while it writes its first bytes: the takeover closed its view, so the write fails
     * as closed. That is a cancel, not a disk error: the download completes and the file is intact.
     */
    @Test
    fun takenOverChunk_writeFailsAsClosed_isCancel() = takenOverChunkWrite(closeInstead = true, id = 14)

    /**
     * A chunk taken over while its first write is already in the channel: the write lands, at offsets the
     * first chunk writes with the same bytes, and is not counted. The download completes intact.
     */
    @Test
    fun takenOverChunk_writeInFlightLands_fileIntact() = takenOverChunkWrite(closeInstead = false, id = 15)

    private fun takenOverChunkWrite(closeInstead: Boolean, id: Long) {
        val bytes = randomData(3 * 1024 * 1024, seed = id)
        throttled("/takeover$id", bytes)
        val hold = HoldTakenOverWrite(closeInstead)
        val storage = FaultyStorage(beforeWrite = hold::beforeWrite)
        val host = host(storage)
        newTask(id, "/takeover$id", host, TestConfig(maxSegments = 2)).start()
        awaitSuccess(host, 90)
        assertTrue(hold.released.get(), "a second chunk's first write was held and released")
        assertEquals(0L, hold.takenOver.count, "the first chunk took the held chunk over")
        assertEquals(0, storage.open, "nothing left open")
        assertChecksumMatches(host, bytes)
    }

    /** A server that sends no length: one stream, one handle, the file intact. */
    @Test
    fun unknownLength_oneHandle() {
        val bytes = randomData(3 * 1024 * 1024 + 123, seed = 2)
        server.register("/nolen", Endpoint(bytes, plan = { ConnPlan(omitContentLength = true) }))
        val storage = FaultyStorage()
        val host = host(storage)
        newTask(2, "/nolen", host, TestConfig(maxSegments = 8)).start()
        awaitSuccess(host)
        assertEquals(1, storage.opened.get(), "one handle")
        assertEquals(0, storage.open, "closed")
        assertChecksumMatches(host, bytes)
    }

    /**
     * Paused at random moments many times over, each session with its own handle, then run to the end:
     * the file is intact, so a close in the middle of the chunks' writes loses or misplaces nothing.
     */
    @Test
    fun manyPausesAtRandomMoments_fileIntact() {
        val bytes = randomData(16 * 1024 * 1024, seed = 3)
        throttled("/pauses", bytes, sleepMs = 30)
        val random = Random(3)
        for (round in 0 until 12) {
            val host = runAndPause(3, "/pauses", round == 0, runMs = random.nextLong(300), storage = FaultyStorage())
            if (host.success != null) return assertChecksumMatches(host, bytes)
            assertNull(host.failure, "no failure from a pause: ${host.failure}")
        }
        assertEquals(12, interruptedMidway, "every round paused mid-download")
        val storage = FaultyStorage()
        val host = host(storage)
        newTask(3, "/pauses", host, TestConfig(maxSegments = 8)).resume()
        awaitSuccess(host, 60)
        assertEquals(0, storage.open, "closed")
        assertChecksumMatches(host, bytes)
    }

    /** Pauses that land while writes are in flight (each write is slowed down): the file is still intact. */
    @Test
    fun pauseWithWritesInFlight_fileIntact() {
        // 256 writes of 64 KB at 60 ms each over 8 connections: about 2 s of writing, 1 s of it done here.
        val bytes = randomData(16 * 1024 * 1024, seed = 4)
        throttled("/inflight", bytes, sliceKb = 64, sleepMs = 1)
        for (round in 0 until 5) {
            val storage = FaultyStorage(writeDelayMs = 60)
            val host = runAndPause(4, "/inflight", round == 0, runMs = 150, storage = storage)
            assertNull(host.success, "slowed down, it cannot finish in one go")
        }
        assertEquals(5, interruptedMidway, "every round wrote before its pause")
        val storage = FaultyStorage()
        val host = host(storage)
        newTask(4, "/inflight", host, TestConfig(maxSegments = 8)).resume()
        awaitSuccess(host, 60)
        assertChecksumMatches(host, bytes)
    }

    /**
     * The disk fills up mid-download: the write fails, the download fails with a disk error, the handle is
     * closed by the time that is reported, and the state is saved. With space again, resume completes the
     * file intact.
     */
    @Test
    fun diskFullMidDownload_failsThenResumesIntact() {
        val bytes = randomData(6 * 1024 * 1024, seed = 5)
        throttled("/full", bytes)
        val full = FaultyStorage(failAfterBytes = 2L * 1024 * 1024)
        val host1 = host(full)
        newTask(5, "/full", host1, TestConfig(maxSegments = 8)).start()
        awaitDone(host1, 30)
        assertEquals(DownloadError.DiskSpaceError, host1.failure, "a failed write is a disk error")
        assertNull(host1.success, "not published")
        assertEquals(0, full.open, "the handle is closed when the failure is reported")
        assertEquals(1, full.opened.get(), "one handle")
        assertTrue(File(work, "5.state").isFile, "the state is saved")
        assertTrue(File(tmpDir, partFileName(5)).isFile, "the part file is kept")
        assertTrue(waitFor(5000) { liveChunkThreads() == 0 }, "every chunk stopped")

        val storage = FaultyStorage()
        val host2 = host(storage)
        newTask(5, "/full", host2, TestConfig(maxSegments = 8)).resume()
        awaitSuccess(host2, 60)
        assertTrue(storage.written.get() < bytes.size, "resumed, not downloaded again (${storage.written.get()} bytes)")
        assertChecksumMatches(host2, bytes)
    }

    /** The part file cannot be opened (a read-only or unplugged drive): a disk error, and nothing left open. */
    @Test
    fun cannotOpen_failsWithDiskError() {
        val bytes = randomData(1024 * 1024, seed = 6)
        throttled("/noopen", bytes)
        val storage = FaultyStorage(failOnOpen = true)
        val host = host(storage)
        newTask(6, "/noopen", host, TestConfig(maxSegments = 8)).start()
        awaitDone(host, 30)
        assertEquals(DownloadError.DiskSpaceError, host.failure, "an open that fails is a disk error")
        assertEquals(0, storage.opened.get(), "nothing opened")
    }

    /**
     * Pauses and disk failures at random moments, alternating, then a clean run: whatever the mix of
     * interruptions, the file comes out intact.
     */
    @Test
    fun randomPausesAndDiskFailures_fileIntact() {
        val bytes = randomData(8 * 1024 * 1024, seed = 7)
        throttled("/mixed", bytes)
        val random = Random(7)
        for (round in 0 until 10) {
            val host = if (round % 2 == 0) {
                runAndPause(7, "/mixed", round == 0, runMs = random.nextLong(250), storage = FaultyStorage())
            } else {
                val storage = FaultyStorage(failAfterBytes = random.nextLong(64 * 1024, 768 * 1024))
                val host = host(storage)
                newTask(7, "/mixed", host, TestConfig(maxSegments = 8)).resume()
                awaitDone(host, 30)
                if (host.success == null) {
                    assertEquals(DownloadError.DiskSpaceError, host.failure, "round $round fails with a disk error")
                    assertEquals(0, storage.open, "round $round closed its handle")
                    interruptedMidway++
                }
                host
            }
            if (host.success != null) return assertChecksumMatches(host, bytes)
        }
        assertEquals(10, interruptedMidway, "every round was interrupted mid-download")
        val storage = FaultyStorage()
        val host = host(storage)
        newTask(7, "/mixed", host, TestConfig(maxSegments = 8)).resume()
        awaitSuccess(host, 60)
        assertChecksumMatches(host, bytes)
    }

    /** Deleting a paused download closes the handle (if any) and removes the part file. */
    @Test
    fun deleteAfterPause_removesPartFile() {
        val bytes = randomData(4 * 1024 * 1024, seed = 8)
        throttled("/delete", bytes)
        val storage = FaultyStorage()
        val host = host(storage)
        val task = newTask(8, "/delete", host, TestConfig(maxSegments = 8))
        task.start()
        assertTrue(waitFor(10_000) { storage.written.get() > 0 }, "some data written")
        task.stop()
        assertTrue(host.pauseLatch.await(10, TimeUnit.SECONDS), "paused")
        task.deleteTemp()
        assertEquals(0, storage.open, "closed")
        assertFalse(File(tmpDir, partFileName(8)).exists(), "part file deleted")
    }

    private fun liveChunkThreads(): Int = Thread.getAllStackTraces().entries.count { (_, st) ->
        st.any { it.className.endsWith("HttpChunkRetriever") }
    }
}
