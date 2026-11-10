// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.media.muxer.Muxer
import xdm.core.storage.StorageFile
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pausing an HLS download while it assembles: the pause is reported only once the mux has stopped and
 * dropped its partial output, and a resume assembles again from the downloaded segments. A full disk
 * while muxing fails with [DownloadError.DiskSpaceError], not the generic mux error.
 */
class TestStreamingAssemblePause : StreamingE2EBase() {

    private lateinit var mock: MockHttpServer
    private val segmentHits = AtomicInteger()

    @BeforeEach
    fun startServer() {
        mock = MockHttpServer()
        val playlist = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n")
            repeat(4) { append("#EXTINF:1.0,\n").append(mock.url("/s%02d.ts".format(it))).append('\n') }
            append("#EXT-X-ENDLIST\n")
        }
        mock.register("/index.m3u8", Endpoint(playlist.toByteArray()))
        repeat(4) { i ->
            mock.register("/s%02d.ts".format(i), Endpoint(ByteArray(16 * 1024) { (i + it).toByte() }, plan = {
                segmentHits.incrementAndGet(); ConnPlan()
            }))
        }
    }

    @AfterEach
    fun stopServer() = mock.stop()

    private fun task(id: Long, muxer: Muxer): Pair<HlsDownloaderTask, StreamingTestHost> {
        val h = host("assemble-$id")
        val info = HlsDownloadTaskInfo(
            id = id, fileName = "v.mp4",
            tempDir = File(root, "assemble-$id/tmp").absolutePath, respectFileName = false,
            cookie = null, headers = null, origin = null, categoryId = null,
            targetFolder = File(root, "assemble-$id/out").absolutePath, maxPiece = 0, authInfo = null,
            url = mock.url("/index.m3u8"), audioUrl = null, audioOnly = false, independent = false,
        )
        return HlsDownloaderTask(info, newClient(), muxer, h, root.absolutePath, config(2)) to h
    }

    /** Only the single-list mux is used here; the others fail. */
    private abstract class FakeMuxer : Muxer {
        val stopped = AtomicBoolean(false)
        override fun mux(
            file1: String, file2: String, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String
        ) = false

        override fun mux(
            audioSegments: List<String>, videoSegments: List<String>, output: StorageFile,
            progressCallback: (Int) -> Unit, tempDir: String, independentSegement: Boolean, isMp4: Boolean,
            discontinuous: Boolean,
        ) = false

        override fun stop() = stopped.set(true)
    }

    /** Writes part of the output, then waits for a stop; slow to clean up, like a large output. */
    private class BlockingMuxer : FakeMuxer() {
        val started = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        @Volatile var output: StorageFile? = null

        override fun mux(
            segments: List<String>, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String,
            independentSegement: Boolean, isMp4: Boolean, discontinuous: Boolean,
        ): Boolean {
            this.output = output
            output.open(create = true).use { it.write(ByteArray(1024), 0, 1024) }
            progressCallback(10)
            started.countDown()
            val deadline = System.currentTimeMillis() + 10_000
            while (!stopped.get() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            Thread.sleep(300)
            output.delete()
            returned.set(true)
            return false
        }
    }

    /** Joins the segments into the output. */
    private class ConcatMuxer : FakeMuxer() {
        val calls = AtomicInteger()
        override fun mux(
            segments: List<String>, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String,
            independentSegement: Boolean, isMp4: Boolean, discontinuous: Boolean,
        ): Boolean {
            calls.incrementAndGet()
            output.open(create = true).use { out ->
                out.setLength(0)
                segments.forEach { val b = File(it).readBytes(); out.write(b, 0, b.size) }
            }
            return true
        }
    }

    /** Fails the way the transmuxer does: with [lastError] set, or by throwing it. */
    private class FailingMuxer(private val error: Throwable, private val thrown: Boolean) : FakeMuxer() {
        override var lastError: Throwable? = null
        override fun mux(
            segments: List<String>, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String,
            independentSegement: Boolean, isMp4: Boolean, discontinuous: Boolean,
        ): Boolean {
            if (thrown) throw error
            lastError = error
            return false
        }
    }

    @Test
    fun pauseWhileAssemblingStopsMuxThenResumeAssemblesAgain() {
        val blocking = BlockingMuxer()
        val (first, h1) = task(8001, blocking)
        first.start()
        assertTrue(blocking.started.await(20, TimeUnit.SECONDS), "assembling never started")
        val hitsBefore = segmentHits.get()

        first.stop()
        assertTrue(h1.latch.await(15, TimeUnit.SECONDS), "pause not acknowledged")
        assertTrue(blocking.returned.get(), "the pause was reported before the mux stopped")
        assertFalse(blocking.output!!.exists, "the partial output must be gone")
        assertNull(h1.failure, "a pause while assembling is not a failure: ${h1.failure}")
        assertNull(h1.success, "a paused download must not complete")

        val concat = ConcatMuxer()
        val (second, h2) = task(8001, concat)
        second.resume()
        awaitSuccess(h2, 20)
        assertEquals(1, concat.calls.get(), "the resume must assemble again")
        assertEquals(hitsBefore, segmentHits.get(), "the downloaded segments must not be fetched again")
        assertEquals(4 * 16 * 1024L, h2.finalFile!!.length(), "the output must hold every segment")
    }

    @Test
    fun diskFullWhileMuxingIsDiskSpaceError() {
        val (t, h) = task(8002, FailingMuxer(IOException("write failed: ENOSPC (No space left on device)"), false))
        t.start()
        assertEquals(DownloadError.DiskSpaceError, awaitFailure(h, 20), "a full disk must not read as a mux error")
    }

    @Test
    fun diskFullThrownFromMuxIsDiskSpaceError() {
        val (t, h) = task(8003, FailingMuxer(IOException("There is not enough space on the disk"), true))
        t.start()
        assertEquals(DownloadError.DiskSpaceError, awaitFailure(h, 20), "a full disk must not read as a mux error")
    }

    @Test
    fun otherMuxFailureStaysMuxError() {
        val (t, h) = task(8004, FailingMuxer(IOException("Input/output error"), false))
        t.start()
        assertEquals(DownloadError.MuxError, awaitFailure(h, 20), "only a full disk changes the error")
        assertNotNull(h.failure, "expected a failure")
    }
}
