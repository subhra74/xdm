package xdm.core.downloaders.web

import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.streaming.downloader.StreamingChunk
import xdm.core.util.FormatHelper.getEtaAsSec
import java.util.*

data class SegmentProgress(var start: Long = 0, var length: Long = 0, var downloaded: Long = 0, val id: Long = 0)

class ProgressTracker(val singleFile: Boolean) {
    var init = false
    var totalDownloadedBytes: Long = 0
    var lastDownloadedBytes: Long = 0
    val speedHistory = LinkedList<Float>()
    var speedSum = 0.0
    var downloadedBytesSinceStartOrResume: Long = 0
    val ticksAtDownloadStartOrResume = System.currentTimeMillis()
    var lastProgressUpdatedAt: Long = 0
    var lastChunkStatsUpdated: Long = 0
    var progress = 0
    var downloadSpeed = 0f
    var eta: Long = 0
    var totalSize: Long? = null

    /**
     * Streaming only: the bytes the chunks hold, finished or partial. Unlike [totalDownloadedBytes]
     * (counted since this tracker was made) it includes what was downloaded before a resume.
     */
    var streamDownloadedBytes: Long = 0
    private val offsetComparator = compareBy<SegmentProgress> { it.start }
    private val segments = ArrayList<SegmentProgress>()
    private val sortedSegments = sortedSetOf(offsetComparator)

    /**
     * A snapshot for the progress window, which paints it on the EDT while the download threads keep
     * updating the live segments under this tracker's lock.
     */
    val segmentData: Collection<SegmentProgress>
        @Synchronized get() = (if (singleFile) sortedSegments else segments).map { it.copy() }

    /**
     * [prg] (percent) and [remainingBytes] are only asked for on the ticks that recompute speed and
     * ETA, about twice a second, as working them out can mean walking every chunk.
     */
    private fun update(
        ticks: Long,
        ticksElapsed: Long,
        downloadedBytes: Long,
        totalSize: Long?,
        prg: () -> Float?,
        remainingBytes: () -> Long?,
    ) {
        this.totalSize = totalSize
        this.totalDownloadedBytes += downloadedBytes
        this.downloadedBytesSinceStartOrResume += downloadedBytes
        if (ticks - this.lastProgressUpdatedAt > 500 && ticksElapsed > 0) {
            val instantSpeed =
                (((this.totalDownloadedBytes - this.lastDownloadedBytes) * 1000f) / (ticks - this.lastProgressUpdatedAt))
            if (speedHistory.size > 5) {
                this.speedSum -= speedHistory.removeFirst()
            }
            speedHistory.add(instantSpeed)
            speedSum += instantSpeed
            val instanceSpeedBounded = speedSum / speedHistory.size
            val avgSpeed = (this.downloadedBytesSinceStartOrResume * 1000.0f) / ticksElapsed
            this.lastProgressUpdatedAt = ticks
            this.lastDownloadedBytes = this.totalDownloadedBytes
            this.downloadSpeed = instanceSpeedBounded.toFloat()
            val percent = prg() ?: return
            this.progress = percent.toInt()
            val remaining = remainingBytes()
            if (remaining != null) {
                this.eta = getEtaAsSec(remaining.coerceAtLeast(0).toDouble(), avgSpeed)
            } else if (percent > 0) {
                // No byte count to go on: assume the rest goes as fast as what is done so far.
                this.eta = (ticksElapsed * (100 - percent) / (percent * 1000)).toLong()
            }
        }
    }

    @Synchronized
    fun update(downloadedBytes: Long, totalSize: Long?, chunks: Map<Long, Chunk>): Boolean {
        val prg = if (totalSize != null) (totalDownloadedBytes + downloadedBytes) * 100.0f / totalSize else null
        val ticks = System.currentTimeMillis()
        val ticksElapsed = ticks - this.ticksAtDownloadStartOrResume
        update(ticks, ticksElapsed, downloadedBytes, totalSize, { prg }, { totalSize?.minus(totalDownloadedBytes) })
        if (ticks - this.lastChunkStatsUpdated > 1000 && ticksElapsed > 0) {
            updateChunkProgressDataSingleFile(chunks)
            this.lastChunkStatsUpdated = ticks
            return true
        }
        return false
    }

    /**
     * Streaming: progress by bytes when every chunk's size is known (byte-range segments, or each one
     * already answered), else by finished chunks. The ETA counts the bytes left, estimating unknown
     * chunk sizes from the known ones. [totalSize] wins when the caller knows it.
     */
    @Synchronized
    fun update(downloadedBytes: Long, totalSize: Long?, chunks: List<StreamingChunk>, completed: Int): Boolean {
        val ticks = System.currentTimeMillis()
        val ticksElapsed = ticks - this.ticksAtDownloadStartOrResume
        var size: StreamSize? = null
        fun size() = size ?: streamSize(chunks, totalSize).also {
            size = it
            streamDownloadedBytes = chunks.sumOf { c -> c.downloaded.get() }
        }
        update(
            ticks, ticksElapsed, downloadedBytes, totalSize,
            prg = {
                val s = size()
                if (s.exact && s.bytes > 0) (streamDownloadedBytes * 100.0f / s.bytes).coerceAtMost(100f)
                else completed * 100.0f / chunks.size
            },
            remainingBytes = { size().takeIf { it.bytes > 0 }?.let { it.bytes - streamDownloadedBytes } },
        )
        if (ticks - this.lastChunkStatsUpdated > 1000 && ticksElapsed > 0) {
            updateChunkProgressData(chunks)
            this.lastChunkStatsUpdated = ticks
            return true
        }
        return false
    }

    /**
     * Speed, ETA and [progress] only, for a task that builds its own segment data. Returns true about
     * once a second, when the caller should report progress.
     */
    @Synchronized
    fun update(downloadedBytes: Long, totalSize: Long?, progress: Float): Boolean {
        val ticks = System.currentTimeMillis()
        val ticksElapsed = ticks - this.ticksAtDownloadStartOrResume
        update(ticks, ticksElapsed, downloadedBytes, totalSize, { progress }, { totalSize?.minus(totalDownloadedBytes) })
        if (ticks - this.lastChunkStatsUpdated > 1000 && ticksElapsed > 0) {
            this.lastChunkStatsUpdated = ticks
            return true
        }
        return false
    }

    /** A stream's size in bytes; [exact] when every chunk's size is known, else estimated (0: no idea yet). */
    private class StreamSize(val bytes: Long, val exact: Boolean)

    /**
     * Sums the chunk sizes that are known (from the response, or the segment's byte range) and gives
     * each unknown chunk the average of the known ones.
     */
    private fun streamSize(chunks: List<StreamingChunk>, totalSize: Long?): StreamSize {
        if (totalSize != null && totalSize > 0) return StreamSize(totalSize, true)
        var known = 0L
        var knownCount = 0
        for (c in chunks) {
            val len = c.length.get().takeIf { it > 0 } ?: c.byteRange?.second?.takeIf { it > 0 } ?: continue
            known += len
            knownCount++
        }
        return when (knownCount) {
            0 -> StreamSize(0, false)
            chunks.size -> StreamSize(known, true)
            else -> StreamSize(known + known / knownCount * (chunks.size - knownCount), false)
        }
    }

    private fun updateChunkProgressDataSingleFile(chunks: Map<Long, Chunk>) {
        while (true) {
            var segToRemove: SegmentProgress? = null
            for (seg in sortedSegments) {
                if (!chunks.containsKey(seg.id)) {
                    segToRemove = seg
                    break
                }
            }
            if (segToRemove == null) {
                break
            } else {
                sortedSegments.remove(segToRemove)
            }
        }
        for (chunk in chunks.values) {
            val seg = sortedSegments.find { it.id == chunk.id }
            if (seg == null) {
                sortedSegments.add(
                    SegmentProgress(
                        id = chunk.id,
                        start = chunk.offset,
                        downloaded = chunk.downloaded.get(),
                        length = chunk.length.get()
                    )
                )
            } else {
                seg.start = chunk.offset
                seg.length = chunk.length.get()
                seg.downloaded = chunk.downloaded.get()
            }
        }
    }

    /**
     * Streaming chunks are separate files whose sizes are mostly unknown up front, so each one gets an
     * equal slot of [STREAM_SLOT] units on the bar, filled by the fraction of it downloaded.
     */
    private fun updateChunkProgressData(chunks: List<StreamingChunk>) {
        while (segments.size < chunks.size) {
            segments.add(SegmentProgress())
        }
        for ((index, chunk) in chunks.withIndex()) {
            val len = chunk.length.get()
            val fraction = when {
                chunk.status.get() == ChunkStatus.Finished -> 1.0
                len > 0 -> (chunk.downloaded.get().toDouble() / len).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            val s = segments[index]
            s.start = index * STREAM_SLOT
            s.length = STREAM_SLOT
            s.downloaded = (fraction * STREAM_SLOT).toLong()
        }
    }

    private companion object {
        const val STREAM_SLOT = 1000L
    }
}