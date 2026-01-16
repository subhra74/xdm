//package xdm.core.downloaders
//
//import xdm.core.util.FormatUtilities
//import java.util.*
//
//class DownloadProgressData(
//    val downloader: AbstractDownloader, var totalDownloadedBytes: Long
//) {
//    var lastDownloadedBytes: Long = 0
//    val speedHistory = LinkedList<Double>()
//    var speedSum = 0.0
//    var downloadedBytesSinceStartOrResume: Long = 0
//    val ticksAtDownloadStartOrResume = System.currentTimeMillis()
//    var lastProgressUpdatedAt: Long = 0
//    var lastChunkStatsUpdated: Long = 0
//
//    var progress = 0
//
//    var downloadSpeed = 0f
//
//    var eta: Long = 0
//
//    var segmentDetails: SegmentDetails? = null
//
//    var lastProgress = 0
//    val adaptiveDownloaderTypes: EnumSet<DownloaderType> =
//        EnumSet.of(DownloaderType.Hls, DownloaderType.Dash, DownloaderType.EncryptedHls)
//
//    @Synchronized
//    fun updateDownloadInfo(downloadedBytes: Long, chunks: List<Chunk>?) {
//        val ticks = System.currentTimeMillis()
//        this.totalDownloadedBytes += downloadedBytes
//        this.downloadedBytesSinceStartOrResume += downloadedBytes
//        val ticksElapsed = ticks - this.ticksAtDownloadStartOrResume
//        if (ticks - this.lastProgressUpdatedAt > 500 && ticksElapsed > 0) {
//            val instantSpeed =
//                (((this.totalDownloadedBytes - this.lastDownloadedBytes) * 1000) / (ticks - this.lastProgressUpdatedAt).toDouble())
//            if (speedHistory.size > 5) {
//                this.speedSum -= speedHistory.removeFirst()
//            }
//            speedHistory.add(instantSpeed)
//            speedSum += instantSpeed
//            val instanceSpeedBounded = speedSum / speedHistory.size
//            val avgSpeed = (this.downloadedBytesSinceStartOrResume * 1000.0) / ticksElapsed
//            this.lastProgressUpdatedAt = ticks
//            this.lastDownloadedBytes = this.totalDownloadedBytes
//            this.progress = downloader.progress.get()
//            this.downloadSpeed = instanceSpeedBounded.toFloat()
//            if (adaptiveDownloaderTypes.contains(downloader.downloaderType)) {
//                val prgDiff = progress - lastProgress
//                lastProgress = progress
//                if (prgDiff > 0) {
//                    this.eta = (ticksElapsed * (100 - progress) / 1000 * prgDiff)
//                }
//            } else {
//                this.eta = FormatUtilities.getEtaAsSec(
//                    downloader.size.toDouble() - totalDownloadedBytes, avgSpeed.toFloat()
//                )
//            }
//        }
//        if (ticks - this.lastChunkStatsUpdated > 1000 && ticksElapsed > 0) {
//            this.updateChunkProgressData(chunks)
//            this.lastChunkStatsUpdated = ticks
//        }
//    }
//
//    fun updateChunkProgressData(chunks: List<Chunk>?) {
//        if (segmentDetails == null) {
//            segmentDetails = SegmentDetails()
//        }
//        if (adaptiveDownloaderTypes.contains(downloader.downloaderType)) {
//            if (segmentDetails!!.capacity < downloader.maxCount) {
//                segmentDetails!!.extend(downloader.maxCount)
//            }
//            val prg = downloader.progress.get()
//            for (i in 0..<downloader.maxCount) {
//                val info = segmentDetails!!.chunkUpdates[i]
//                info.downloaded = prg.toLong()
//                info.start = i * 100L
//                info.length = 100
//            }
//        } else {
//            if (chunks == null) {
//                return
//            }
//            if (segmentDetails!!.capacity < chunks.size) {
//                segmentDetails!!.extend(chunks.size - segmentDetails!!.capacity)
//            }
//            segmentDetails!!.chunkCount = chunks.size.toLong()
//            for (i in chunks.indices) {
//                val s = chunks[i]
//                val info = segmentDetails!!.chunkUpdates[i]
//                info.downloaded = s.downloaded
//                info.start = s.startOffset
//                info.length = s.length
//            }
//        }
//    }
//}
