// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.core.downloaders.web.ProgressTracker
import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.streaming.downloader.StreamingChunk
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The segment data behind the progress window's table: every segment for the bar, the live ones (being
 * downloaded right now) flagged for the table, in bar order, with their own bytes.
 */
class TestSegmentTable {

    private fun stream(seq: Long, status: ChunkStatus, downloaded: Long = 0, length: Long = 0) = StreamingChunk(
        id = seq, sequence = seq, downloaded = AtomicLong(downloaded), length = AtomicLong(length),
        status = AtomicReference(status), error = AtomicReference(null), url = "https://h/s$seq", byteRange = null,
        keyUrl = null, tag = "VIDEO", fileHandle = AtomicReference(null), encrypted = false,
    )

    private fun part(id: Long, offset: Long, length: Long, downloaded: Long, status: ChunkStatus) = Chunk(
        id = id, offset = offset, length = AtomicLong(length), downloaded = AtomicLong(downloaded),
        status = AtomicReference(status), fileHandle = AtomicReference(null), lastTakeOver = AtomicLong(0),
    )

    /** Segment data is rebuilt at most once a second. */
    private fun nextTick() = Thread.sleep(1100)

    @Test
    fun streaming_liveSegmentsOnly_andListTrimmedToChunks() {
        val t = ProgressTracker(false)
        val first = listOf(
            stream(0, ChunkStatus.Finished, 500, 500),
            stream(1, ChunkStatus.Downloading, 200, 800),
            stream(2, ChunkStatus.Downloading, 300),
            stream(3, ChunkStatus.Ready),
            stream(4, ChunkStatus.Ready),
        )
        Thread.sleep(5)
        t.update(0, null, first, completed = 1)
        val segs = t.segmentData
        assertEquals(5, segs.size, "the bar has every chunk")
        assertEquals(listOf(200L, 300L), segs.filter { it.live }.map { it.bytes }, "only the chunks being downloaded, own bytes")
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), segs.map { it.id }, "each segment keeps its chunk index as id")

        nextTick()
        t.update(0, null, first.take(3), completed = 1)
        assertEquals(3, t.segmentData.size, "never more segments than the download has chunks")
    }

    @Test
    fun http_unfinishedPartsAreLive_inFileOrder() {
        val t = ProgressTracker(true)
        val parts = linkedMapOf(
            3L to part(3, 500, 500, 100, ChunkStatus.Ready),
            1L to part(1, 0, 250, 250, ChunkStatus.Finished),
            2L to part(2, 250, 250, 40, ChunkStatus.Downloading),
        )
        Thread.sleep(5)
        t.update(0, 1000, parts)
        val segs = t.segmentData
        assertEquals(listOf(0L, 250L, 500L), segs.map { it.start }, "in file order")
        assertEquals(listOf(40L, 100L), segs.filter { it.live }.map { it.bytes }, "finished parts leave the table")
    }
}
