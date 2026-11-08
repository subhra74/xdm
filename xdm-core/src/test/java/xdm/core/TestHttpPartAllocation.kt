// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.partFileName
import xdm.core.downloaders.web.http.PartAllocation
import xdm.core.storage.LocalStorage
import xdm.core.storage.PositionalOutput
import xdm.core.storage.Storage
import xdm.core.storage.StorageFile
import xdm.core.storage.StorageFolder
import java.io.File
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Downloads to a disk without holes (FAT32, exFAT): the part file is filled front to back in steps before
 * the download splits ([PartAllocation]), no data write lands past the filled part, and pause, resume and a
 * full disk during the fill behave like they do mid-download.
 */
class TestHttpPartAllocation : HttpDownloadTestBase() {

    /**
     * [LocalStorage] on a pretend FAT disk ([fillsGaps]). Checks every data write ends inside the file as it is
     * (counting [violations]) and how long the file was at the first one ([shortestAtDataWrite]), counts the
     * fill's own writes, and can slow them ([fillDelayMs]) or fail the fill once [failFillAfter] steps were
     * written ("disk full").
     */
    class FatStorage(
        private val fillsGaps: Boolean = true,
        private val fillDelayMs: Long = 0,
        private val failFillAfter: Int = -1,
    ) : Storage {
        val fillWrites = AtomicInteger()
        val dataWrites = AtomicInteger()
        val violations = AtomicInteger()

        /** The part file's length at the earliest data write: its full size when the fill came first. */
        val shortestAtDataWrite = AtomicLong(Long.MAX_VALUE)

        /** Flushes from the download's own threads, not the fill. */
        val dataForces = AtomicInteger()
        val opened = AtomicInteger()
        val closed = AtomicInteger()

        override fun folder(location: String): StorageFolder = Folder(LocalStorage.folder(location))

        private inner class Folder(private val real: StorageFolder) : StorageFolder by real {
            override fun child(name: String): StorageFolder = Folder(real.child(name))
            override fun file(name: String): StorageFile = StorageFile(this, name)
            override fun fillsGaps(): Boolean = fillsGaps

            override fun openPositional(name: String, create: Boolean, length: Long?): PositionalOutput =
                Output(real.openPositional(name, create, length)).also { opened.incrementAndGet() }
        }

        private inner class Output(private val real: PositionalOutput) : PositionalOutput by real {
            override fun write(position: Long, b: ByteArray, off: Int, len: Int) {
                if (Thread.currentThread().name.startsWith("part-file-fill")) {
                    if (fillDelayMs > 0) Thread.sleep(fillDelayMs)
                    if (failFillAfter >= 0 && fillWrites.get() >= failFillAfter) {
                        throw IOException("Simulated: no space left on device")
                    }
                    fillWrites.incrementAndGet()
                } else {
                    dataWrites.incrementAndGet()
                    val length = real.length()
                    shortestAtDataWrite.accumulateAndGet(length, ::minOf)
                    // On a real FAT disk this write would zero-fill the gap: it must never happen.
                    if (position + len > length) violations.incrementAndGet()
                }
                real.write(position, b, off, len)
            }

            override fun force() {
                if (!Thread.currentThread().name.startsWith("part-file-fill")) dataForces.incrementAndGet()
                real.force()
            }

            override fun close() {
                real.close()
                closed.incrementAndGet()
            }
        }
    }

    private fun throttled(path: String, bytes: ByteArray, sleepMs: Long = 2) =
        server.register(path, Endpoint(bytes, plan = { ConnPlan(throttleChunk = 64 * 1024, throttleSleepMs = sleepMs) }))

    private val mb = 1024 * 1024

    @Test
    fun fatDisk_fillsFirstThenSplits_fileIntact() {
        val bytes = randomData(12 * mb, seed = 1)
        val ep = throttled("/fat", bytes)
        val storage = FatStorage()
        val host = host(storage)
        newTask(1, "/fat", host, TestConfig(maxSegments = 6)).start()

        awaitSuccess(host, 60)
        assertDownloaded(host, bytes)
        assertEquals(0, storage.violations.get(), "a data write went past the filled part of the file")
        assertEquals(bytes.size.toLong(), storage.shortestAtDataWrite.get(), "no data was written before the file was filled")
        assertEquals(24, storage.fillWrites.get(), "one write per 512 KB step")
        assertEquals(-1, host.allocateReports.last(), "the fill reports when it is done")
        assertEquals(100, host.allocateReports[host.allocateReports.size - 2], "the fill reached 100%")
        assertTrue(ep.connCount.get() > 1, "the download split after the fill")
    }

    @Test
    fun otherDisk_noFill() {
        val bytes = randomData(12 * mb, seed = 2)
        throttled("/plain", bytes)
        val storage = FatStorage(fillsGaps = false)
        val host = host(storage)
        newTask(2, "/plain", host, TestConfig(maxSegments = 6)).start()

        awaitSuccess(host, 60)
        assertDownloaded(host, bytes)
        assertEquals(0, storage.fillWrites.get(), "no fill where the disk can have holes")
        assertTrue(host.allocateReports.isEmpty(), "no fill reported: ${host.allocateReports}")
    }

    /** On a FAT disk the download flushes about every [PartAllocation.FLUSH_EVERY] bytes; elsewhere only at the end. */
    @Test
    fun fatDisk_flushesDataAsItGoes() {
        val bytes = randomData(40 * mb, seed = 7)
        throttled("/flushfat", bytes, sleepMs = 0)
        val fat = FatStorage()
        val host1 = host(fat)
        newTask(7, "/flushfat", host1, TestConfig(maxSegments = 4)).start()
        awaitSuccess(host1, 60)
        assertDownloaded(host1, bytes)
        assertTrue(fat.dataForces.get() >= 4, "flushed as it went on a FAT disk: ${fat.dataForces.get()} flushes")

        val bytes2 = randomData(40 * mb, seed = 8)
        throttled("/flushplain", bytes2, sleepMs = 0)
        val plain = FatStorage(fillsGaps = false)
        val host2 = host(plain)
        newTask(8, "/flushplain", host2, TestConfig(maxSegments = 4)).start()
        awaitSuccess(host2, 60)
        assertDownloaded(host2, bytes2)
        assertTrue(plain.dataForces.get() <= 2, "only the final flush elsewhere: ${plain.dataForces.get()} flushes")
    }

    @Test
    fun smallFile_onFatDisk_noFill() {
        val bytes = randomData(3 * mb, seed = 3)
        throttled("/small", bytes)
        val storage = FatStorage()
        val host = host(storage)
        newTask(3, "/small", host, TestConfig(maxSegments = 6)).start()

        awaitSuccess(host, 60)
        assertDownloaded(host, bytes)
        assertTrue(host.allocateReports.isEmpty(), "a small file is sized in one go: ${host.allocateReports}")
    }

    @Test
    fun oneSegment_onFatDisk_noFill() {
        val bytes = randomData(12 * mb, seed = 4)
        throttled("/single", bytes, sleepMs = 0)
        val storage = FatStorage()
        val host = host(storage)
        newTask(4, "/single", host, TestConfig(maxSegments = 1)).start()

        awaitSuccess(host, 60)
        assertDownloaded(host, bytes)
        assertTrue(host.allocateReports.isEmpty(), "one stream never leaves a gap: ${host.allocateReports}")
    }

    /** Paused partway through the fill: the part file stays as far as it got, and resume goes on from there. */
    @Test
    fun pauseDuringFill_resumesFillAndCompletes() {
        val bytes = randomData(16 * mb, seed = 5)
        throttled("/pause", bytes, sleepMs = 5)
        val slow = FatStorage(fillDelayMs = 60)
        val host1 = host(slow)
        val task = newTask(5, "/pause", host1, TestConfig(maxSegments = 6))
        task.start()
        assertTrue(waitFor(10_000) { host1.allocateReports.any { it >= 25 } }, "the fill got going")
        task.stop()
        assertTrue(host1.pauseLatch.await(10, TimeUnit.SECONDS), "pause reported")
        assertFalse(host1.allocateReports.contains(-1), "paused before the fill ended")
        assertTrue(waitFor(5000) { slow.opened.get() == slow.closed.get() }, "the handle is closed after the pause")
        val part = File(tmpDir, partFileName(5))
        val filled = part.length()
        assertTrue(filled in 1 until bytes.size, "the part file is partly filled: $filled")
        assertEquals(0, slow.dataWrites.get(), "no data is written while the file is being filled")

        val storage = FatStorage()
        val host2 = host(storage)
        newTask(5, "/pause", host2, TestConfig(maxSegments = 6)).resume()
        awaitSuccess(host2, 60)
        assertDownloaded(host2, bytes)
        assertEquals(0, storage.violations.get(), "a data write went past the filled part of the file after resume")
        assertEquals(bytes.size.toLong(), storage.shortestAtDataWrite.get(), "no chunk wrote before the resumed fill ended")
        assertTrue(host2.allocateReports.first() > 0, "the fill went on from where it was: ${host2.allocateReports}")
        assertEquals(-1, host2.allocateReports.last(), "the resumed fill reports when it is done")
    }

    /** The disk fills up during the fill: a disk error, the handle closed, and nothing published. */
    @Test
    fun diskFullDuringFill_failsWithDiskError() {
        val bytes = randomData(16 * mb, seed = 6)
        throttled("/full", bytes, sleepMs = 5)
        val storage = FatStorage(failFillAfter = 4)
        val host = host(storage)
        newTask(6, "/full", host, TestConfig(maxSegments = 6)).start()

        awaitDone(host, 30)
        assertEquals(DownloadError.DiskSpaceError, host.failure, "a failed fill is a disk error")
        assertNull(host.success, "not published")
        assertTrue(waitFor(5000) { storage.opened.get() == storage.closed.get() }, "the handle is closed")
        assertEquals(0, storage.dataWrites.get(), "no data is written when the fill fails")
    }

    // -----------------------------------------------------------------------------------------------------
    // PartAllocation on its own
    // -----------------------------------------------------------------------------------------------------

    private class MemoryOutput : PositionalOutput {
        val writes = AtomicInteger()
        val length = AtomicLong()
        override fun write(position: Long, b: ByteArray, off: Int, len: Int) {
            writes.incrementAndGet()
            length.accumulateAndGet(position + len, ::maxOf)
        }

        override fun length(): Long = length.get()
        override fun force() {}
        override fun close() {}
    }

    private fun writeInBackground(out: PositionalOutput, position: Long, len: Int): Pair<CountDownLatch, AtomicReference<Throwable?>> {
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        Thread {
            try {
                out.write(position, ByteArray(len), 0, len)
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                done.countDown()
            }
        }.start()
        return done to error
    }

    @Test
    fun guard_holdsEveryWriteUntilFilled() {
        val allocation = PartAllocation()
        val inner = MemoryOutput()
        val out = allocation.Guarded(inner)
        out.write(0, ByteArray(10), 0, 10)
        assertEquals(1, inner.writes.get(), "nothing is held back before a fill begins")

        allocation.begin()
        val (done, error) = writeInBackground(out, 0, 100)
        assertFalse(done.await(300, TimeUnit.MILLISECONDS), "even a write at the start waits for the fill")
        assertEquals(1, inner.writes.get(), "not written while filling")
        allocation.finish()
        assertTrue(done.await(2, TimeUnit.SECONDS), "goes ahead once the file is filled")
        assertNull(error.get(), "no error")
        assertEquals(2, inner.writes.get(), "written")

        out.write(10_000, ByteArray(10), 0, 10)
        assertEquals(3, inner.writes.get(), "nothing is held back once the file is filled")
    }

    @Test
    fun guard_closeFailsWaitingWriteAsClosed() {
        val allocation = PartAllocation()
        allocation.begin()
        val out = allocation.Guarded(MemoryOutput())
        val (done, error) = writeInBackground(out, 0, 10)
        assertFalse(done.await(300, TimeUnit.MILLISECONDS), "waits for the fill")
        out.close()
        assertTrue(done.await(2, TimeUnit.SECONDS), "close ends the wait")
        assertTrue(error.get() is ClosedChannelException, "a pause reads as closed, not a disk error: ${error.get()}")
    }

    @Test
    fun guard_failedFillFailsWaitingWrite() {
        val allocation = PartAllocation()
        allocation.begin()
        val out = allocation.Guarded(MemoryOutput())
        val (done, error) = writeInBackground(out, 0, 10)
        assertFalse(done.await(300, TimeUnit.MILLISECONDS), "waits for the fill")
        allocation.fail(IOException("No space left on device"))
        assertTrue(done.await(2, TimeUnit.SECONDS), "a failed fill ends the wait")
        assertTrue(error.get() is IOException && error.get() !is ClosedChannelException, "a disk error: ${error.get()}")
        assertThrows(IOException::class.java, { out.write(5, ByteArray(10), 0, 10) }, "later writes fail too")
    }
}
