package xdm.core.downloaders.web.streaming.downloader

import xdm.core.CoreConfig
import xdm.core.downloaders.*
import xdm.core.downloaders.web.ProgressTracker
import xdm.core.downloaders.web.SpeedLimiter
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.streaming.downloader.hls.DecryptionException
import xdm.core.media.muxer.Muxer
import xdm.core.network.http.isTlsVerificationError
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.ManifestUtils.downloadManifestAsFile
import xdm.core.util.getFileExtFromUrl
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

abstract class StreamingDownloaderTask(
    protected val context: StreamingTaskContext,
    protected val configDir: String,
    private val muxer: Muxer,
    private val config: CoreConfig
) : DownloaderTask {
    protected val executorService: ExecutorService = Executors.newFixedThreadPool(config.maxSegments)
    private val progressTracker = ProgressTracker(false)
    private val prgInfo = DownloadStatusInfo.ProgressInfo(id = context.id)
    private var lastUpdate: Long = 0
    private val throttle: SpeedLimiter = SpeedLimiter(config)
    private val stopRequested = AtomicBoolean(false)
    private val startRequested = AtomicBoolean(false)
    private var lastAssembleProgress = -1

    /** Set when a manifest or key fetch failed TLS verification, so setup failures report [DownloadError.TlsError]. */
    private val tlsFailure = AtomicBoolean(false)

    /** Pass to manifest/key downloads so a TLS verification failure is remembered. */
    protected val recordFetchError: (Throwable) -> Unit = { if (isTlsVerificationError(it)) tlsFailure.set(true) }

    private fun setupError(fallback: DownloadError) = if (tlsFailure.get()) DownloadError.TlsError else fallback

    abstract fun initDownload(): DownloadStatusInfo.InitInfo?
    abstract fun fileExt(): String
    abstract fun downloadType(): DownloadType
    abstract fun saveContext()
    abstract fun isIndependentSegment(): Boolean
    abstract fun postProcessChunks()

    /**
     * True when the manifest signals a discontinuity (e.g. HLS EXT-X-DISCONTINUITY): segment
     * timestamps reset mid-stream, so the muxer must repair the timeline to stay monotonic.
     * Defaults to false for streaming types that don't carry the concept.
     */
    protected open fun hasDiscontinuity(): Boolean = false

    override fun start() {
        if (startRequested.get()) {
            return
        }
        startRequested.set(true)
        Thread {
            File(context.tempFolder).mkdirs()
            download()
        }.start()
    }

    override fun stop() {
        if (stopRequested.get()) {
            return
        }
        stopRequested.set(true)
        Thread {
            context.stopFlag.set(true)
            muxer.stop()
            executorService.shutdownNow()
            // Closing the client also cancels in-flight segment calls stuck in a read.
            context.httpClient.close()
            synchronized(this) {
                context.chunks.filter { it.status.get() != ChunkStatus.Finished }.forEach {
                    try {
                        it.fileHandle.get()?.close()
                    } catch (e: Exception) {
                        //
                    }
                }
            }
            saveContext()
            throttle.disable()
            context.downloadHost.onDownloadPaused(context.id, PauseEvent.PausedByUser)
        }.start()
    }

    override fun resume() {
        start()
    }

    override fun deleteTemp() {
        cleanup()
        // Delete the recorded output only. Recomputing it here would miss the real file
        // whenever the destination changed after muxing, leaving it orphaned.
        context.muxOutputPath?.let {
            val out = File(it)
            if (out.delete()) Logger.info("XDM", "Deleted partial output $out")
        }
    }

    /**
     * The assembled output: in the destination folder when the host provides a path (so the commit
     * is a same-folder rename), otherwise in the temp folder.
     *
     * Resolved once and recorded in [context]; every later call returns the recorded path, so a
     * destination changed after muxing cannot orphan the partial. Callers that must not create a
     * recording (delete, cleanup) read [StreamingTaskContext.muxOutputPath] directly.
     */
    private fun outputFile(): File {
        context.muxOutputPath?.let { return File(it) }
        val resolved = context.downloadHost.outputFilePath(context.id, downloadType(), fileExt())
            ?.let { File(it) }
            ?: File(context.tempFolder, context.tempFileName + fileExt())
        context.muxOutputPath = resolved.absolutePath
        saveContext()
        return resolved
    }

    private fun download() {
        try {
            context.downloadHost.onDownloadActivated(context.id)
            if (!context.init.get()) {
                val initInfo = initDownload()
                if (initInfo == null) {
                    if (context.stopFlag.get()) return
                    Logger.error("XDM", "Failed to download manifest")
                    context.downloadHost.onDownloadFailed(context.id, setupError(DownloadError.InvalidResponse))
                    return
                }
                context.init.set(true)
                saveContext()
                context.downloadHost.onDownloadInit(initInfo, downloadType())
            }
            downloadChunks()
        } catch (ex: Exception) {
            Logger.error("XDM", "Download failed due to error", ex)
            context.downloadHost.onDownloadFailed(context.id, setupError(DownloadError.InternalError))
        } finally {
            // Success, failure or pause: the task is done, so release its segment threads and its
            // HTTP client (dispatcher + connection pool). stop() may already have done both.
            executorService.shutdownNow()
            context.httpClient.close()
        }
    }

    /**
     * Waits for [latch] but gives up once the download is stopped: stop() shuts the pool down with
     * shutdownNow(), so queued tasks never run and never count the latch down.
     * Returns false if the download was stopped before the latch reached zero.
     */
    protected fun awaitUnlessStopped(latch: CountDownLatch): Boolean {
        while (!latch.await(200, TimeUnit.MILLISECONDS)) {
            if (context.stopFlag.get()) return false
        }
        return true
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
            //executorService.shutdown()
            //executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
            if (!awaitUnlessStopped(latch)) return
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
        val tmpFile = outputFile()
        // A previous run muxed successfully but the commit failed: only retry the commit.
        val alreadyMuxed = context.completed.get() && tmpFile.isFile
        if (!alreadyMuxed) {
            try {
                postProcessChunks()
            } catch (e: DecryptionException) {
                Logger.error("XDM", "Decryption failed", e)
                if (context.stopFlag.get()) return
                context.downloadHost.onDownloadFailed(context.id, DownloadError.DecryptionError)
                return
            }
            val outFolder = tmpFile.absoluteFile.parentFile
            if (!outFolder.isDirectory && !outFolder.mkdirs()) {
                Logger.error("XDM", "Unable to create output folder $outFolder")
                context.downloadHost.onDownloadFailed(context.id, DownloadError.OutputWriteError)
                return
            }
            FileUtils.hideFile(tmpFile)
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
        } else {
            Logger.info("XDM", "Output already muxed, retrying commit: $tmpFile")
        }
        throttle.disable()
        Logger.info("Committing to final file")
        when (val res = context.downloadHost.commitOutputFile(context.id, tmpFile.absolutePath, downloadType())) {
            is CommitResult.Failed -> {
                if (context.stopFlag.get()) return
                Logger.info("Committing to final file - Failed!!")
                context.diskError.set(true)
                saveContext()
                context.downloadHost.onDownloadFailed(context.id, res.error)
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

    /** Reports muxing/assembly progress to the host, throttled to whole-percent changes. */
    private fun onAssembleProgress(progress: Int) {
        if (context.stopFlag.get()) return
        if (progress == lastAssembleProgress) return
        lastAssembleProgress = progress
        context.downloadHost.onAssembleProgress(DownloadStatusInfo.AssembleInfo(context.id, progress))
    }

    private fun assembleStreams(outFile: String): Boolean {
        try {
            Logger.info("Mux multiple streams to $outFile")
            val audioChunks = context.chunks.filter { it.tag == "AUDIO" }.map { getChunkTempFileName(it) }
            val videoChunks = context.chunks.filter { it.tag == "VIDEO" }.map { getChunkTempFileName(it) }
            if (muxer.mux(
                    audioChunks, videoChunks, outFile, this::onAssembleProgress, context.tempFolder,
                    isIndependentSegment(), isMp4(), hasDiscontinuity()
                )
            ) {
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
            if (muxer.mux(tempFiles, outFile, this::onAssembleProgress, context.tempFolder, isIndependentSegment(), isMp4(), hasDiscontinuity())) {
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
            FileUtils.deleteFolder(folderPath)
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
                    context.httpClient, url, context.headers, context.cookie, context.stopFlag, recordFetchError
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
            throttle.throttleIfNeeded(context.downloaded.get())
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
//                val pc2 = context.chunks.find { it.status.get() == ChunkStatus.Failed }
//                if (pc2 != null) {
//                    val pg = createPieceGrabber(pc2, latch)
//                    executorService.submit(pg)
//                } else {
//                    latch.countDown()
//                }
            } else {
                Logger.info("Chunk failed- $pc")
//                latch.countDown()
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
        maxRetries = config.maxRetries,
    ) { p ->
        onChunkComplete(p, latch)
    }
}