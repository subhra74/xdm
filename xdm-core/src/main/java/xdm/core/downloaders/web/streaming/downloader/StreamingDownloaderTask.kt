package xdm.core.downloaders.web.streaming.downloader

import xdm.core.downloaders.*
import xdm.core.downloaders.web.ProgressTracker
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.media.muxer.Muxer
import xdm.core.util.Logger
import xdm.core.util.ManifestUtils.downloadManifestAsFile
import xdm.core.util.getFileExtFromUrl
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

abstract class StreamingDownloaderTask(
    protected val context: StreamingTaskContext,
    protected val configDir: String,
    private val muxer: Muxer,
) : DownloaderTask {
    protected val executorService: ExecutorService = Executors.newFixedThreadPool(8)
    private val progressTracker = ProgressTracker()
    private val prgInfo = DownloadStatusInfo.ProgressInfo(id = context.id)
    private var lastUpdate: Long = 0

    abstract fun initDownload(): DownloadStatusInfo.InitInfo?
    abstract fun fileExt(): String
    abstract fun downloadType(): DownloadType
    abstract fun saveContext()
    abstract fun isIndependentSegment(): Boolean
    abstract fun postProcessChunks()

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
        start()
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
                saveContext()
                context.downloadHost.onDownloadInit(initInfo, downloadType())
            }
            downloadChunks()
        } catch (ex: Exception) {
            Logger.error("XDM", "Download failed due to error", ex)
            context.downloadHost.onDownloadFailed(context.id, DownloadError.InternalError)
        }
    }

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
            saveContext()
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
        postProcessChunks()
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
        Logger.info("Committing to final file")
        when (val res = context.downloadHost.commitOutputFile(context.id, tmpFile.absolutePath, downloadType())) {
            is CommitResult.Failed -> {
                if (context.stopFlag.get()) return
                Logger.info("Committing to final file - Failed!!")
                context.diskError.set(true)
                saveContext()
                context.downloadHost.onDownloadFailed(context.id, DownloadError.DiskSpaceError)
            }

            is CommitResult.Success -> {
                saveContext()
                cleanup()
                Logger.info("Committing to final file - Success --> ${res.outputDir} ${res.fileName}")
                context.downloadHost.onDownloadSuccess(
                    DownloadStatusInfo.FinalInfo(
                        context.id, File(res.outputDir, res.fileName).length(), res.fileName, res.outputDir
                    )
                )
            }
        }
    }

    private fun isMp4(): Boolean {
        return context.chunks.any {
            it.contentType?.let { t ->
                if (t.contains("mp4")) return true
            }
            getFileExtFromUrl(it.url)?.let { e ->
                return e.endsWith(".mp4") || e.endsWith(".m4s") || e.endsWith(".m4v") || e.endsWith(".fmp4")
            }
            return false
        }
    }

    private fun assembleStreams(outFile: String): Boolean {
        try {
            Logger.info("Mux multiple streams to $outFile")
            val audioChunks = context.chunks.filter { it.tag == "AUDIO" }.map { getChunkTempFileName(it) }
            val videoChunks = context.chunks.filter { it.tag == "VIDEO" }.map { getChunkTempFileName(it) }
            if (muxer.mux(audioChunks, videoChunks, outFile, {}, context.tempFolder, isIndependentSegment(), isMp4())) {
                context.completed.set(true)
                return true
            }
        } catch (e: Exception) {
            Logger.error("XDM", "Mux failed - assembleStreams", e)
        }
        return false
    }

    fun getChunkTempFileName(chunk: StreamingChunk): String {
        val ext = getFileExtFromUrl(chunk.url) ?: ""
        val suffix = if (chunk.encrypted) ".enc" else ""
        return Paths.get(context.tempFolder, "temp-${chunk.id}.tmp.$ext$suffix").toAbsolutePath().toString()
    }

    private fun assembleSingle(outFile: String): Boolean {
        try {
            Logger.info("Mux single stream to $outFile")
            val tempFiles = context.chunks.map {
                getChunkTempFileName(it)
            }
            if (muxer.mux(tempFiles, outFile, {}, context.tempFolder, isIndependentSegment(), isMp4())) {
                context.completed.set(true)
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
                saveContext()
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
            saveContext()
        }
    }

    private fun createPieceGrabber(pc: StreamingChunk, latch: CountDownLatch) = StreamingChunkRetriever(
        piece = pc,
        httpClient = context.httpClient,
        stopFlag = context.stopFlag,
        headers = context.headers,
        cookie = context.cookie,
        tempDir = context.tempFolder,
        progressCallback = this::onChunkProgress,
        fileNameCallback = this::getChunkTempFileName,
    ) { p ->
        onChunkComplete(p, latch)
    }
}