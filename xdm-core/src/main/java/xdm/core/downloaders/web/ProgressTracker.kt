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
    var lastProgress = 0
    var downloadSpeed = 0f
    var eta: Long = 0
    var totalSize: Long? = null
    private val offsetComparator = compareBy<SegmentProgress> { it.start }
    private val segments = ArrayList<SegmentProgress>()
    private val sortedSegments = sortedSetOf(offsetComparator)

    /**
     * A snapshot for the progress window, which paints it on the EDT while the download threads keep
     * updating the live segments under this tracker's lock.
     */
    val segmentData: Collection<SegmentProgress>
        @Synchronized get() = (if (singleFile) sortedSegments else segments).map { it.copy() }

    private fun update(
        ticks: Long,
        ticksElapsed: Long,
        downloadedBytes: Long,
        totalSize: Long?,
        prg: Float?
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
            if (prg != null) {
                this.progress = prg.toInt()
                if (totalSize != null) {
                    this.eta = getEtaAsSec(
                        totalSize.toDouble() - totalDownloadedBytes, avgSpeed
                    )
                } else {
                    val prgDiff = progress - lastProgress
                    lastProgress = progress
                    if (prgDiff > 0) {
                        this.eta = (ticksElapsed * (100 - progress) / 1000 * prgDiff)
                    }
                }
            }
        }
    }

    @Synchronized
    fun update(downloadedBytes: Long, totalSize: Long?, chunks: Map<Long, Chunk>): Boolean {
        val prg = if (totalSize != null) (totalDownloadedBytes + downloadedBytes) * 100.0f / totalSize else null
        val ticks = System.currentTimeMillis()
        val ticksElapsed = ticks - this.ticksAtDownloadStartOrResume
        update(ticks, ticksElapsed, downloadedBytes, totalSize, prg)
        if (ticks - this.lastChunkStatsUpdated > 1000 && ticksElapsed > 0) {
            updateChunkProgressDataSingleFile(chunks)
            this.lastChunkStatsUpdated = ticks
            return true
        }
        return false
    }

    @Synchronized
    fun update(downloadedBytes: Long, totalSize: Long?, chunks: List<StreamingChunk>, completed: Int): Boolean {
        val prg = completed * 100.0f / chunks.size
        val ticks = System.currentTimeMillis()
        val ticksElapsed = ticks - this.ticksAtDownloadStartOrResume
        update(ticks, ticksElapsed, downloadedBytes, totalSize, prg)
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
        update(ticks, ticksElapsed, downloadedBytes, totalSize, progress)
        if (ticks - this.lastChunkStatsUpdated > 1000 && ticksElapsed > 0) {
            this.lastChunkStatsUpdated = ticks
            return true
        }
        return false
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