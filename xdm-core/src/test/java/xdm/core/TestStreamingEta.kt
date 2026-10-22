package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.web.ProgressTracker
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.streaming.downloader.StreamingChunk
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Progress and ETA of HLS/DASH downloads. They used to go by finished chunks only, with an ETA of
 * elapsed × (100 − progress) × (progress step), recomputed only when a chunk finished: a split-track
 * download at 97% and 550 KB/s showed 24 minutes left.
 */
class TestStreamingEta {

    private fun chunk(
        seq: Long, downloaded: Long = 0, length: Long = 0, range: Long? = null, finished: Boolean = false,
    ) = StreamingChunk(
        id = seq, sequence = seq, downloaded = AtomicLong(downloaded), length = AtomicLong(length),
        status = AtomicReference(if (finished) ChunkStatus.Finished else ChunkStatus.Ready),
        error = AtomicReference(null), url = "https://h/s$seq", byteRange = range?.let { seq * it to it },
        keyUrl = null, tag = "VIDEO", fileHandle = AtomicReference(null), encrypted = false,
    )

    /** The tracker only recomputes speed and ETA once more than 500 ms have passed. */
    private fun afterAWhile() = Thread.sleep(700)

    @Test
    fun byteRangeSegments_progressAndEtaByBytes() {
        val chunks = listOf(
            chunk(0, 100_000, 100_000, 100_000, finished = true),
            chunk(1, 50_000, 100_000, 100_000),
            chunk(2, range = 100_000),
            chunk(3, range = 100_000),
        )
        val t = ProgressTracker(false)
        afterAWhile()
        t.update(150_000, null, chunks, completed = 1)
        assertEquals(37, t.progress, "150 of 400 KB, not 1 of 4 chunks")
        assertEquals(150_000L, t.streamDownloadedBytes, "bytes held by the chunks")
        // 250 KB left at about 150 KB per 0.7 s.
        assertTrue(t.eta in 1..2, "seconds left by bytes, was ${t.eta}")
    }

    @Test
    fun unknownSizes_etaFromAverageOfKnownChunks() {
        val chunks = listOf(
            chunk(0, 100_000, 100_000, finished = true),
            chunk(1), chunk(2), chunk(3),
        )
        val t = ProgressTracker(false)
        afterAWhile()
        t.update(100_000, null, chunks, completed = 1)
        assertEquals(25, t.progress, "estimated sizes: progress stays by chunks")
        // About 300 KB left (3 more chunks like the first) at about 100 KB per 0.7 s.
        assertTrue(t.eta in 1..3, "seconds left by estimated bytes, was ${t.eta}")
    }

    @Test
    fun resumedDownload_countsBytesFromBefore() {
        val chunks = listOf(
            chunk(0, 300_000, 300_000, 300_000, finished = true),
            chunk(1, 10_000, 100_000, 100_000),
        )
        val t = ProgressTracker(false)
        afterAWhile()
        t.update(10_000, null, chunks, completed = 1)
        assertEquals(310_000L, t.streamDownloadedBytes, "includes what was downloaded before the resume")
        assertEquals(77, t.progress, "310 of 400 KB")
    }

    @Test
    fun percentOnly_etaFromTimeSoFar() {
        val t = ProgressTracker(false)
        afterAWhile()
        t.update(1, null, 10f)
        // 0.7 s for 10%, so about 6.3 s for the other 90%.
        assertTrue(t.eta in 5..8, "elapsed × (100 − p) / p, was ${t.eta}")
    }
}
