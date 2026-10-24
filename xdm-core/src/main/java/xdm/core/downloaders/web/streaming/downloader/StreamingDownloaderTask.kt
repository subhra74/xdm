package xdm.core.downloaders.web.streaming.downloader

import xdm.core.CoreConfig
import xdm.core.downloaders.*
import xdm.core.downloaders.web.ProgressTracker
import xdm.core.downloaders.web.SpeedLimiter
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.streaming.downloader.hls.DecryptionException
import xdm.core.media.muxer.Muxer
import xdm.core.media.subtitle.WebVtt
import xdm.core.network.http.isTlsVerificationError
import xdm.core.storage.LocalStorage
import xdm.core.storage.StorageFile
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.ManifestUtils.downloadManifestAsFile
import xdm.core.util.getFileExtFromUrl
import java.io.File
import java.io.IOException
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
    private val config: CoreConfig,
    /** Media segments fetched at once: the download's own choice, or the global setting when unset. */
    maxPiece: Int = 0,
    /** The subtitle picked for this download, saved beside the video as SRT; its segments are [SUBS_TAG] chunks. */
    private val subtitle: SubtitleTrack? = null,
) : DownloaderTask {
    protected val executorService: ExecutorService =
        Executors.newFixedThreadPool(maxPiece.takeIf { it > 0 } ?: config.maxSegments)
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
            segmentsGone()?.let { problem ->
                // Nothing is touched, so the state is still there if the segments come back.
                Logger.error("XDM", "Download ${context.id} cannot resume: segments missing in ${context.tempFolder}")
                context.httpClient.close()
                context.downloadHost.onDownloadFailed(context.id, problem)
                return@Thread
            }
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

    /**
     * A disk problem that stops a saved download from resuming, or null: a downloaded segment's file (or
     * the whole temp folder) is gone, so continuing would mux a file with holes, and re-downloading
     * silently would start over. A download already muxed only needs its output, not the segments.
     */
    private fun segmentsGone(): DownloadError? {
        if (!context.init.get()) return null
        val finished = context.chunks.filter { it.status.get() == ChunkStatus.Finished }
        if (finished.isEmpty()) return null
        if (context.completed.get() && outputFile().exists) return null
        return if (finished.any { !File(getChunkTempFileName(it)).isFile }) DownloadError.DiskSpaceError else null
    }

    override fun deleteTemp() {
        cleanup()
        val out = outputFile()
        if (out.exists && out.delete()) Logger.info("XDM", "Deleted partial output $out")
    }

    /**
     * The assembled output: in the target folder when the host provides a path (so the commit is a
     * same-folder rename), otherwise in the temp folder. Derived every time, never taken from the saved
     * state: the host derives it from the task's target folder and the id, and that folder only changes
     * through Change folder, which deletes a partial output in the old folder. [StreamingTaskContext.muxOutputPath]
     * just mirrors the latest value.
     */
    private fun outputFile(): StorageFile {
        val resolved = context.downloadHost.outputFile(context.id, downloadType(), fileExt())
            ?: LocalStorage.folder(context.tempFolder).file(context.tempFileName + fileExt())
        context.muxOutputPath = resolved.toString()
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
            // A subtitle is extra: a failed one is left out, it never fails the video.
            context.chunks.find { it.tag != SUBS_TAG && it.status.get() != ChunkStatus.Finished }?.let {
                Logger.error("XDM", "Not all chunks downloaded successfully")
                var error = it.error.get() ?: DownloadError.InternalError
                // A refusal is only an expiry if the link worked before; otherwise it never did.
                val anyProgress = context.chunks.any { c -> c.status.get() == ChunkStatus.Finished || c.downloaded.get() > 0 }
                if (error == DownloadError.LinkExpired && !anyProgress) error = DownloadError.InvalidResponse
                context.downloadHost.onDownloadFailed(context.id, error)
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
        val alreadyMuxed = context.completed.get() && tmpFile.exists
        if (!alreadyMuxed) {
            try {
                postProcessChunks()
            } catch (e: DecryptionException) {
                Logger.error("XDM", "Decryption failed", e)
                if (context.stopFlag.get()) return
                context.downloadHost.onDownloadFailed(context.id, DownloadError.DecryptionError)
                return
            }
            val outFolder = tmpFile.folder
            if (!outFolder.create()) {
                Logger.error("XDM", "Unable to create output folder $outFolder")
                context.downloadHost.onDownloadFailed(context.id, DownloadError.OutputWriteError)
                return
            }
            val ret = if (context.hasSeparateStreams) {
                assembleStreams(tmpFile)
            } else {
                assembleSingle(tmpFile)
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
        when (val res = context.downloadHost.commitOutputFile(context.id, tmpFile, downloadType())) {
            is CommitResult.Failed -> {
                // Cancelled only by a stop, which reports the pause itself.
                if (context.stopFlag.get() || res.error == DownloadError.Cancelled) return
                Logger.info("Committing to final file - Failed!!")
                context.diskError.set(true)
                saveContext()
                context.downloadHost.onDownloadFailed(context.id, res.error)
            }

            is CommitResult.Success -> {
                saveContext()
                saveSubtitle(res.outputDir, res.fileName)
                cleanup()
                Logger.info("Committing to final file - Success --> ${res.outputDir} ${res.fileName}")
                context.downloadHost.onDownloadSuccess(
                    DownloadStatusInfo.FinalInfo(
                        context.id,
                        context.downloadHost.storage.folder(res.outputDir).length(res.fileName),
                        res.fileName,
                        res.outputDir
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

    private fun assembleStreams(outFile: StorageFile): Boolean {
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
        return File(context.tempFolder, "temp-${chunk.id}.tmp.$ext$suffix").absolutePath
    }

    private fun assembleSingle(outFile: StorageFile): Boolean {
        try {
            Logger.info("Mux single stream to $outFile")
            val tempFiles = context.chunks.filter { it.tag != SUBS_TAG }.map {
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

    /**
     * Writes the subtitle segments as `<video name>.<language>.srt` next to the published video,
     * replacing a file of that name. Any failure only loses the subtitle.
     */
    private fun saveSubtitle(outputDir: String, videoFileName: String) {
        val track = subtitle ?: return
        val chunks = context.chunks.filter { it.tag == SUBS_TAG }.sortedBy { it.sequence }
        if (chunks.isEmpty()) return
        if (chunks.any { it.status.get() != ChunkStatus.Finished }) {
            Logger.info("XDM", "Subtitle segments incomplete, no subtitle saved for ${context.id}")
            return
        }
        try {
            val srt = WebVtt.toSrt(
                chunks.map { File(getChunkTempFileName(it)).readText(Charsets.UTF_8) }, muxer.timelineStartSec,
                hls = downloadType() == DownloadType.Hls,
            )
            if (srt.isEmpty()) {
                Logger.info("XDM", "Subtitle has no cues, nothing saved for ${context.id}")
                return
            }
            val name = subtitleFileName(videoFileName, track.language)
            // A BOM, so Windows players don't read a non-Latin subtitle as the ANSI code page.
            val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srt.toByteArray(Charsets.UTF_8)
            context.downloadHost.storage.folder(outputDir).file(name).open(create = true).use { out ->
                out.setLength(0)
                out.write(bytes, 0, bytes.size)
            }
            Logger.info("XDM", "Saved subtitle $name")
        } catch (e: Exception) {
            Logger.error("XDM", "Unable to save subtitle for ${context.id}", e)
        }
    }

    private fun cleanup() {
        Logger.info("XDM", "Cleaning up temp files")
        try {
            val folderPath = File(context.tempFolder)
            if (!folderPath.exists()) {
                return
            }
            FileUtils.deleteFolder(folderPath)
        } catch (ex: Exception) {
            Logger.error("XDM", "Error cleaning up temp files", ex)
        }
    }

    /**
     * Downloads the playlist at [url] to a temp file and hands it over through [manifestFile]. The file
     * is not read here: a media playlist can list a very large number of segments, so the caller streams
     * it (see [readManifestLines]) and must call [discardManifest] when done. A file that arrives after
     * the caller has discarded [manifestFile] (it stopped waiting) is deleted here instead.
     */
    protected fun loadManifest(
        url: String, manifestFile: AtomicReference<File?>, latch: CountDownLatch, error: AtomicBoolean
    ) {
        executorService.submit {
            try {
                downloadManifestAsFile(
                    context.httpClient, url, context.headers, context.cookie, context.stopFlag, recordFetchError,
                    credentialsFor = context.url,
                )?.let { path ->
                    val file = File(path)
                    if (!manifestFile.compareAndSet(null, file)) deleteManifestFile(file)
                }
            } catch (ex: Exception) {
                Logger.error("XDM", "Error downloading manifest", ex)
                error.set(true)
            } finally {
                latch.countDown()
            }
        }
    }

    /** Runs [block] over the lines of the manifest in [manifestFile], streaming and closing the file. */
    protected fun <T> readManifestLines(manifestFile: AtomicReference<File?>, block: (Iterator<String>) -> T): T {
        val file = manifestFile.get()?.takeIf { it !== DISCARDED_MANIFEST }
            ?: throw IOException("Manifest was not downloaded")
        return file.bufferedReader(Charsets.UTF_8).useLines { block(it.iterator()) }
    }

    /** Deletes the manifest temp file, if any, and makes a late [loadManifest] delete its own. */
    protected fun discardManifest(manifestFile: AtomicReference<File?>) {
        manifestFile.getAndSet(DISCARDED_MANIFEST)?.takeIf { it !== DISCARDED_MANIFEST }?.let(::deleteManifestFile)
    }

    private fun deleteManifestFile(file: File) {
        if (!file.delete() && file.exists()) Logger.error("XDM", "Could not delete manifest temp file $file")
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
                this.downloaded = progressTracker.streamDownloadedBytes
                this.eta = progressTracker.eta
                this.speed = progressTracker.downloadSpeed
                this.segments = progressTracker.segmentData
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
        credentialsFor = context.url,
        tempDir = context.tempFolder,
        progressCallback = this::onChunkProgress,
        fileNameCallback = this::getChunkTempFileName,
        maxRetries = config.maxRetries,
    ) { p ->
        onChunkComplete(p, latch)
    }
}

/** Marks a manifest slot whose file was discarded; see [StreamingDownloaderTask.discardManifest]. */
private val DISCARDED_MANIFEST = File("")
