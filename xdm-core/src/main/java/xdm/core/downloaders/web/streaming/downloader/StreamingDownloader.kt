package xdm.core.downloaders.web.streaming.downloader

import xdm.core.downloaders.CommitResult
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.DownloaderTask
import xdm.core.downloaders.web.ProgressTracker
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.media.muxer.Muxer
import xdm.core.util.Logger
import xdm.core.util.ManifestUtils.downloadManifestAsFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

abstract class StreamingDownloader(
    protected val context: StreamingTaskContext,
    private val muxer: Muxer,
) : DownloaderTask {
    protected val executorService: ExecutorService = Executors.newFixedThreadPool(8)
    private val progressTracker = ProgressTracker()
    private val prgInfo = DownloadStatusInfo.ProgressInfo(id = context.id)
    private var lastUpdate: Long = 0

    override fun start() {
        Thread {
            File(context.tempFolder).mkdirs()
            download()
        }.start()
    }

    override fun stop() {
        context.stopFlag.set(true)
        muxer.stop()
        executorService.shutdownNow()
        synchronized(this) {
            context.chunks.filter { it.status.get() != ChunkStatus.Finished }.forEach {
                try {
                    it.fileHandle.get()?.close()
                } catch (e: Exception) {
                    //
                }
            }
        }
    }

    override fun resume() {
        TODO("Not yet implemented")
    }

    private fun download() {
        try {
            if (!context.init.get()) {
                val initInfo = initDownload()
                if (initInfo == null) {
                    Logger.error("XDM", "Failed to download manifest")
                    context.downloadHost.onDownloadFailed(context.id, DownloadError.InvalidResponse)
                    return
                }
                context.init.set(true)
                saveState()
                context.downloadHost.onDownloadInit(initInfo)
            }
            downloadChunks()
        } catch (ex: Exception) {
            Logger.error("XDM", "Download failed due to error", ex)
            context.downloadHost.onDownloadFailed(context.id, DownloadError.InternalError)
        }
    }

    abstract fun initDownload(): DownloadStatusInfo.InitInfo?

    abstract fun fileExt(): String

    private fun downloadChunks() {
        if (context.stopFlag.get()) return
        val count = context.chunks.count { it.status.get() != ChunkStatus.Finished }
        val latch = CountDownLatch(count)
        context.chunks.filter { it.status.get() != ChunkStatus.Finished }.forEach { pc ->
            val pg = createPieceGrabber(pc, latch)
            executorService.submit(pg)
        }
        try {
            latch.await()
            saveState()
            if (context.stopFlag.get()) return
            context.chunks.find { it.status.get() != ChunkStatus.Finished }?.let {
                Logger.error("XDM", "Not all chunks downloaded successfully")
                context.downloadHost.onDownloadFailed(context.id, it.error.get() ?: DownloadError.InternalError)
                return
            }
            assemble()
        } catch (ex: InterruptedException) {
            Logger.error("Thread interrupted", ex)
            Thread.currentThread().interrupt()
        }
    }

    private fun assemble() {
        if (context.stopFlag.get()) return
        context.assembling.set(true)
        context.downloadHost.onAssembleStart(context.id)
        val tmpFile = File(context.tempFolder, context.tempFileName + fileExt())
        val ret = if (context.hasSeparateStreams) {
            assembleStreams(tmpFile.absolutePath)
        } else {
            assembleSingle(tmpFile.absolutePath)
        }
        if (context.stopFlag.get()) return
        if (!ret) {
            context.downloadHost.onDownloadFailed(context.id, DownloadError.MuxError)
            return
        }
        when (val res = context.downloadHost.commitOutputFile(context.id, tmpFile.absolutePath)) {
            is CommitResult.Failed -> {
                if (context.stopFlag.get()) return
                context.diskError.set(true)
                saveState()
                context.downloadHost.onDownloadFailed(context.id, DownloadError.DiskSpaceError)
            }

            is CommitResult.Success -> {
                saveState()
                context.downloadHost.onDownloadSuccess(
                    DownloadStatusInfo.FinalInfo(
                        context.id,
                        File(res.outputDir, res.fileName).length(),
                        res.fileName,
                        res.outputDir
                    )
                )
            }
        }
    }

    private fun assembleStreams(outFile: String): Boolean {
        try {
            Logger.info("Mux multiple streams to $outFile")
            val audioChunks = context.chunks.filter { it.tag == "AUDIO" }
                .map { Paths.get(context.tempFolder, "temp-${it.id}.tmp").toAbsolutePath().toString() }
            val videoChunks = context.chunks.filter { it.tag == "VIDEO" }
                .map { Paths.get(context.tempFolder, "temp-${it.id}.tmp").toAbsolutePath().toString() }
            if (muxer.mux(audioChunks, videoChunks, outFile, {}, context.tempFolder)) {
                context.completed.set(true)
                cleanup()
                return true
            }
        } catch (e: Exception) {
            Logger.error("XDM", "Mux failed - assembleStreams", e)
        }
        return false
    }

    private fun assembleSingle(outFile: String): Boolean {
        try {
            Logger.info("Mux single stream to $outFile")
            val tempFiles =
                context.chunks.map { Paths.get(context.tempFolder, "temp-${it.id}.tmp").toAbsolutePath().toString() }
            if (muxer.mux(tempFiles, outFile, {}, context.tempFolder)) {
                context.completed.set(true)
                cleanup()
                return true
            }
        } catch (e: Exception) {
            Logger.error("XDM", "Mux failed - assembleSingle", e)
        }
        return false
    }

    private fun cleanup() {
        Logger.info("XDM", "Cleaning up temp files")
        try {
            val folderPath = Paths.get(context.tempFolder)
            if (!Files.exists(folderPath)) {
                return
            }
            Files.walk(folderPath).sorted(Comparator.reverseOrder()).use { files ->
                files.forEach { f: Path ->
                    try {
                        Files.delete(f)
                        Logger.info("XDM", "Successfully delete file : $f")
                    } catch (e: IOException) {
                        Logger.error("XDM", "Error deleting temp file: $f ", e)
                    }
                }
            }
        } catch (ex: Exception) {
            Logger.error("XDM", "Error cleaning up temp files", ex)
        }
    }

    protected fun loadManifest(
        url: String, manifestContent: AtomicReference<Iterator<String>>, latch: CountDownLatch, error: AtomicBoolean
    ) {
        executorService.submit {
            try {
                downloadManifestAsFile(
                    context.httpClient, url, context.headers, context.cookie, context.stopFlag
                )?.let { file ->
                    manifestContent.set(Files.lines(Paths.get(file)).iterator())
                }
            } catch (ex: Exception) {
                Logger.error("XDM", "Error downloading manifest", ex)
                error.set(true)
            } finally {
                latch.countDown()
            }
        }
    }

    private fun saveState() {}


    private fun onChunkProgress(pc: StreamingChunk, downloaded: Long) {
        synchronized(this) {
            val update = progressTracker.update(
                downloadedBytes = downloaded,
                totalSize = context.totalSize,
                chunks = context.chunks,
                context.pieceCompletedCount.get()
            )
            prgInfo.apply {
                this.progress = progressTracker.progress
                this.downloaded = progressTracker.totalDownloadedBytes
                this.eta = progressTracker.eta
                this.speed = progressTracker.downloadSpeed
            }
            context.downloaded.addAndGet(downloaded)
            if (update) {
                Logger.info("Progress $prgInfo")
                context.downloadHost.onDownloadProgress(prgInfo)
            }
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 5000) {
                saveState()
                lastUpdate = now
            }
        }
    }

    private fun onChunkComplete(pc: StreamingChunk, latch: CountDownLatch) {
        Logger.info("onChunkComplete")
        latch.countDown()
        synchronized(this) {
            Logger.info("onChunkComplete in - $pc")
            if (context.stopFlag.get()) return
            if (pc.status.get() == ChunkStatus.Finished) {
                context.pieceCompletedCount.addAndGet(1)
                Logger.info("total completed ${context.pieceCompletedCount.get()} of ${context.chunks.size}")
                context.chunks.filter { it.status.get() == ChunkStatus.Failed }.forEach {
                    val pg = createPieceGrabber(it, latch)
                    executorService.submit(pg)
                }
            } else {
                Logger.info("Chunk failed")
            }
            saveState()
        }
    }

    private fun createPieceGrabber(pc: StreamingChunk, latch: CountDownLatch) = PieceGrabber(
        piece = pc,
        httpClient = context.httpClient,
        stopFlag = context.stopFlag,
        headers = context.headers,
        cookie = context.cookie,
        tempDir = context.tempFolder,
        progressCallback = this::onChunkProgress
    ) { p ->
        onChunkComplete(p, latch)
    }
}