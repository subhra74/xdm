// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import xdm.core.storage.LocalStorage
import xdm.core.storage.NioFileOps
import xdm.core.storage.PlatformFileOps
import xdm.core.storage.PlatformFiles
import xdm.core.storage.PositionalOutput
import xdm.core.storage.StorageFolder
import xdm.core.storage.at
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * [PositionalOutput], the part file handle all of a download's chunks share: the desktop one ([NioFileOps],
 * one channel for the writes and one for flushes) and the default that takes turns on one handle (Android,
 * SAF).
 */
class TestPositionalOutput {
    @TempDir
    lateinit var root: File

    enum class Impl { Shared, Locked }

    /** [PlatformFileOps] with the interface's own [PlatformFileOps.openPositional], the default Android gets. */
    private object LockedOps : PlatformFileOps by NioFileOps {
        override fun openPositional(file: File, length: Long?): PositionalOutput =
            super<PlatformFileOps>.openPositional(file, length)
    }

    private fun <T> using(impl: Impl, body: (StorageFolder) -> T): T {
        if (impl == Impl.Locked) PlatformFiles.install(LockedOps)
        try {
            return body(LocalStorage.folder(root.path))
        } finally {
            PlatformFiles.install(NioFileOps)
        }
    }

    private fun data(size: Int, seed: Int) = Random(seed).nextBytes(size)

    /** Eight writers, each filling its own region in 64 KB pieces at once: every byte lands where it belongs. */
    @ParameterizedTest
    @EnumSource(Impl::class)
    fun parallelWriters_eachByteAtItsOffset(impl: Impl) = using(impl) { folder ->
        val region = 1024 * 1024
        val writers = 8
        val expected = data(region * writers, 1)
        val errors = AtomicReference<Throwable>()
        folder.openPositional("part", create = true, length = expected.size.toLong()).use { out ->
            assertEquals(expected.size.toLong(), out.length(), "presized")
            val start = CyclicBarrier(writers)
            (0 until writers).map { w ->
                Thread {
                    try {
                        start.await()
                        // Each writer walks its region in a random order of pieces, so the writes interleave.
                        val pieces = (0 until region / PIECE).shuffled(Random(w))
                        for (p in pieces) {
                            val off = w * region + p * PIECE
                            out.write(off.toLong(), expected, off, PIECE)
                        }
                    } catch (e: Throwable) {
                        errors.compareAndSet(null, e)
                    }
                }.apply { start() }
            }.forEach { it.join() }
            out.force()
        }
        errors.get()?.let { throw it }
        assertArrayEquals(expected, File(root, "part").readBytes(), "content after parallel writes")
    }

    /** Each writer's view moves forward with its writes; closing one view leaves the output and the others open. */
    @ParameterizedTest
    @EnumSource(Impl::class)
    fun views_writeInSequence_andCloseAlone(impl: Impl) = using(impl) { folder ->
        val expected = data(4 * PIECE, 2)
        folder.openPositional("part", create = true, length = expected.size.toLong()).use { out ->
            val a = out.at(0)
            val b = out.at(2L * PIECE)
            a.write(expected, 0, PIECE)
            b.write(expected, 2 * PIECE, PIECE)
            assertEquals(PIECE.toLong(), a.position(), "a moved forward")
            assertEquals(3L * PIECE, b.position(), "b moved forward")
            a.close()
            assertThrows(ClosedChannelException::class.java, { a.write(expected, PIECE, PIECE) }, "a closed view refuses writes")
            b.write(expected, 3 * PIECE, PIECE)
            out.write(PIECE.toLong(), expected, PIECE, PIECE)
        }
        assertArrayEquals(expected, File(root, "part").readBytes(), "the output stayed open for the others")
    }

    /**
     * Eight chunks write their regions at once through views (as the chunks do), each view closed and
     * reopened halfway like a reconnect: every byte lands at its offset, a closed view refuses writes while
     * its writer's next view goes on, and after the output closes no view can write.
     */
    @ParameterizedTest
    @EnumSource(Impl::class)
    fun views_inParallel_reopenAndClose(impl: Impl) = using(impl) { folder ->
        val region = 1024 * 1024
        val writers = 8
        val expected = data(region * writers, 9)
        val errors = AtomicReference<Throwable>()
        val out = folder.openPositional("part", create = true, length = expected.size.toLong())
        val start = CyclicBarrier(writers)
        (0 until writers).map { w ->
            Thread {
                try {
                    start.await()
                    var view = out.at(w.toLong() * region)
                    for (off in w * region until (w + 1) * region step PIECE) {
                        if (off == w * region + region / 2) {
                            view.close()
                            assertThrows(ClosedChannelException::class.java, { view.write(expected, off, PIECE) }, "a closed view refuses writes")
                            view = out.at(off.toLong())
                        }
                        view.write(expected, off, PIECE)
                    }
                } catch (e: Throwable) {
                    errors.compareAndSet(null, e)
                }
            }.apply { start() }
        }.forEach { it.join() }
        out.force()
        val late = out.at(0)
        out.close()
        errors.get()?.let { throw it }
        assertArrayEquals(expected, File(root, "part").readBytes(), "content after parallel writer views")
        assertThrows(IOException::class.java, { late.write(expected, 0, PIECE) }, "no view writes after the output closed")
        assertThrows(IOException::class.java, { out.at(0).write(expected, 0, PIECE) }, "nor a new view's")
    }

    /**
     * The periodic sync: [PositionalOutput.force] while eight writers are writing neither fails them nor
     * loses a byte, and [PositionalOutput.length] answers meanwhile.
     */
    @ParameterizedTest
    @EnumSource(Impl::class)
    fun forceWhileWriting_everyByteLands(impl: Impl) = using(impl) { folder ->
        val region = 1024 * 1024
        val writers = 8
        val expected = data(region * writers, 10)
        val errors = AtomicReference<Throwable>()
        val running = CountDownLatch(writers)
        folder.openPositional("part", create = true, length = expected.size.toLong()).use { out ->
            val threads = (0 until writers).map { w ->
                Thread {
                    try {
                        val view = out.at(w.toLong() * region)
                        for (off in w * region until (w + 1) * region step PIECE) {
                            view.write(expected, off, PIECE)
                            if (off == w * region) running.countDown()
                        }
                    } catch (e: Throwable) {
                        errors.compareAndSet(null, e)
                    }
                }.apply { start() }
            }
            assertTrue(running.await(10, TimeUnit.SECONDS), "writers started")
            while (threads.any { it.isAlive }) {
                out.force()
                assertEquals(expected.size.toLong(), out.length(), "length while writing")
            }
            threads.forEach { it.join() }
        }
        errors.get()?.let { throw it }
        assertArrayEquals(expected, File(root, "part").readBytes(), "content after forces during the writes")
    }

    /**
     * Closing the output while writers are in the middle of writing (a pause) fails their writes with an
     * IOException, the error the chunks treat as a cancel when stopping; nothing hangs, and nothing else is thrown.
     */
    @ParameterizedTest
    @EnumSource(Impl::class)
    fun closeWhileWriting_failsWritesWithIOException(impl: Impl) = using(impl) { folder ->
        val size = 8 * 1024 * 1024
        val src = data(size, 3)
        val out = folder.openPositional("part", create = true, length = size.toLong())
        val writers = 8
        val running = CountDownLatch(writers)
        val outcomes = Array<Throwable?>(writers) { null }
        val threads = (0 until writers).map { w ->
            Thread {
                var first = true
                try {
                    while (true) {
                        for (off in w * PIECE until size step writers * PIECE) {
                            out.write(off.toLong(), src, off, PIECE)
                            if (first) running.countDown().also { first = false }
                        }
                    }
                } catch (e: Throwable) {
                    outcomes[w] = e
                }
            }.apply { start() }
        }
        assertTrue(running.await(10, TimeUnit.SECONDS), "writers started")
        out.close()
        threads.forEach { it.join(10_000) }
        assertTrue(threads.none { it.isAlive }, "every writer ended after the close")
        outcomes.forEachIndexed { w, e ->
            assertTrue(e is IOException, "writer $w ended with an IOException, got $e")
        }
        assertThrows(IOException::class.java, { out.write(0, src, 0, PIECE) }, "a write after the close")
    }

    /** A writer interrupted while writing still waits for its write, which lands; the interrupt is kept. */
    @ParameterizedTest
    @EnumSource(Impl::class)
    fun interruptedWriter_writeStillLands(impl: Impl) = using(impl) { folder ->
        val expected = data(16 * PIECE, 4)
        folder.openPositional("part", create = true, length = expected.size.toLong()).use { out ->
            val stillInterrupted = AtomicReference<Boolean>()
            val error = AtomicReference<Throwable>()
            Thread {
                try {
                    Thread.currentThread().interrupt()
                    for (p in 0 until 16) out.write(p.toLong() * PIECE, expected, p * PIECE, PIECE)
                    stillInterrupted.set(Thread.currentThread().isInterrupted)
                } catch (e: Throwable) {
                    error.set(e)
                }
            }.apply { start() }.join()
            error.get()?.let { throw it }
            assertEquals(true, stillInterrupted.get(), "the interrupt is restored")
        }
        assertArrayEquals(expected, File(root, "part").readBytes(), "every write landed")
    }

    /** Presizing a new file leaves it sparse-able and the right length; an existing file keeps its bytes. */
    @ParameterizedTest
    @EnumSource(Impl::class)
    fun presize_newGrowsExistingKeepsContent(impl: Impl) = using(impl) { folder ->
        folder.openPositional("new", create = true, length = 3_000_000).use {
            assertEquals(3_000_000, it.length(), "new file sized")
        }
        assertEquals(3_000_000, File(root, "new").length(), "size on disk")

        val head = data(1000, 5)
        File(root, "short").writeBytes(head)
        folder.openPositional("short", create = true, length = 5000).use {
            assertEquals(5000, it.length(), "existing file grown")
        }
        val grown = File(root, "short").readBytes()
        assertArrayEquals(head, grown.copyOf(1000), "grown file keeps its bytes")
        assertTrue(grown.copyOfRange(1000, 5000).all { it == 0.toByte() }, "the gap reads as zeros")

        File(root, "long").writeBytes(data(5000, 6))
        folder.openPositional("long", create = true, length = 1000).use {
            assertEquals(1000, it.length(), "existing file shrunk")
        }
    }

    /** Resuming opens the part file without creating it: a missing one is an error, not a new empty file. */
    @ParameterizedTest
    @EnumSource(Impl::class)
    fun openExisting_missingFileThrows(impl: Impl) = using(impl) { folder ->
        assertThrows(FileNotFoundException::class.java, { folder.openPositional("gone", create = false) }, "missing")
        assertTrue(!File(root, "gone").exists(), "nothing created")
        val bytes = data(2 * PIECE, 7)
        File(root, "kept").writeBytes(bytes)
        folder.openPositional("kept", create = false).use {
            assertEquals(bytes.size.toLong(), it.length(), "existing length kept")
            it.write(PIECE.toLong(), bytes, 0, PIECE)
        }
        assertArrayEquals(bytes.copyOf(PIECE), File(root, "kept").readBytes().copyOf(PIECE), "first half untouched")
    }

    private companion object {
        const val PIECE = 64 * 1024
    }
}
