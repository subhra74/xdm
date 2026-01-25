package xdm.core.downloaders.web

import xdm.core.downloaders.web.http.Chunk
import xdm.core.util.FormatUtilities
import java.util.*

data class SegmentProgress(var start: Long = 0, var length: Long = 0, var downloaded: Long = 0, val id: Long = 0)

class ProgressTracker {
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
    private val offsetComparator = compareBy<SegmentProgress> { it.start }
    private val segments = ArrayList<SegmentProgress>()
    private val sortedSegments = sortedSetOf(offsetComparator)

    @Synchronized
    fun update(downloadedBytes: Long, totalSize: Long?, chunks: Map<Long, Chunk>, singleFile: Boolean): Boolean {
        val ticks = System.currentTimeMillis()
        this.totalDownloadedBytes += downloadedBytes
        this.downloadedBytesSinceStartOrResume += downloadedBytes
        val prg = if (totalSize != null) totalDownloadedBytes * 100.0f / totalSize else null
        val ticksElapsed = ticks - this.ticksAtDownloadStartOrResume
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
            if (prg != null && totalSize != null) {
                this.progress = prg.toInt()
                if (singleFile) {
                    this.eta = FormatUtilities.getEtaAsSec(
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
        if (ticks - this.lastChunkStatsUpdated > 1000 && ticksElapsed > 0) {
            if (singleFile) {
                updateChunkProgressDataSingleFile(chunks)
            } else {
                updateChunkProgressData(chunks)
            }
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

    private fun updateChunkProgressData(chunks: Map<Long, Chunk>) {
        if (segments.size < chunks.size) {
            for (n in 0 until chunks.size - segments.size) {
                segments.add(SegmentProgress())
            }
        }
        for ((index, chunk) in chunks.values.withIndex()) {
            val s = segments[index]
            s.length = chunk.length.get()
            s.start = chunk.offset
            s.downloaded = chunk.downloaded.get()
            segments[index] = s
        }
    }

}