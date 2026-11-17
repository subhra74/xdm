// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

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
import xdm.core.storage.prepareForWriting
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.DownloadClock
import xdm.core.util.ManifestUtils
import xdm.core.util.ManifestUtils.downloadManifestAsFile
import xdm.core.util.PartRetry
import xdm.core.util.RetryDecision
import xdm.core.util.RetryPolicy
import xdm.core.util.Trouble
import xdm.core.network.http.HttpResponse
import xdm.core.util.getFileExtFromUrl
import xdm.core.util.isOutOfSpace
import java.io.File
import xdm.core.downloaders.AssemblyNote
import xdm.core.downloaders.CommitResult
import xdm.core.media.muxer.OutputKind
import xdm.core.media.muxer.SegmentInfo
import xdm.core.media.muxer.impl.SegmentProbe
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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
    /** Keep TS as `.ts` instead of remuxing ([Muxer.setKeepOriginalFormat]). */
    private val keepOriginalFormat: Boolean = false,
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

    /** The thread running [download]; [stop] waits for it so a pause is reported only once it is done. */
    @Volatile
    private var worker: Thread? = null

    /** What made the last assemble fail, to tell a full disk from a muxing problem. */
    @Volatile
    private var assembleError: Throwable? = null

    /** Set when a manifest or key fetch failed TLS verification, so setup failures report [DownloadError.TlsError]. */
    private val tlsFailure = AtomicBoolean(false)

    /** Pass to manifest/key downloads so a TLS verification failure is remembered. */
    protected val recordFetchError: (Throwable) -> Unit = { if (isTlsVerificationError(it)) tlsFailure.set(true) }

    /** Set when a manifest or key fetch gave up on a busy server, so the setup failure says so. */
    private val busyFailure = AtomicBoolean(false)

    private fun setupError(fallback: DownloadError) = when {
        tlsFailure.get() -> DownloadError.TlsError
        busyFailure.get() -> DownloadError.ServerBusy
        else -> fallback
    }

    /** When the download last received data: segments keep asking while it does (see [RetryPolicy]). */
    protected val clock = DownloadClock()

    /** Segments waiting to ask again, by id, with the wall-clock time their wait ends. */
    private val waits = ConcurrentHashMap<Long, Long>()

    /** The error of the segment that failed last; reported when the failed segments disagree. */
    @Volatile
    private var lastFailure: DownloadError? = null

    /**
     * Fetches a playlist or key, a lone request with nothing else running: a busy server and network errors are
     * asked again by [RetryPolicy]'s rules (the run start counts as data, so a busy server gets the full budget);
     * a refusal or a missing file fails at once unless it says when to come back (Retry-After).
     */
    protected fun <T : Any> fetchWithRetry(
        what: String,
        fetch: (onErrorResponse: (HttpResponse) -> Unit, onError: (Throwable) -> Unit) -> T?,
    ): T? {
        val retry = PartRetry(clock, config.maxRetries)
        while (!context.stopFlag.get()) {
            var code = 0
            var retryAfter: Long? = null
            var authenticate: String? = null
            var failure: Throwable? = null
            val result = fetch({ r ->
                code = r.statusCode
                retryAfter = RetryPolicy.retryAfterMs(r.getHeader("Retry-After"))
                authenticate = r.getHeader("WWW-Authenticate")
            }, { failure = it; recordFetchError(it) })
            if (result != null || context.stopFlag.get()) return result
            val f = failure
            val trouble = when {
                code != 0 -> RetryPolicy.classify(code, authenticate, retryAfter != null)
                f == null || f is ManifestUtils.ManifestTooLargeException || isTlsVerificationError(f) -> null
                else -> Trouble.Network
            }
            if (trouble != Trouble.Server && trouble != Trouble.Network) return null
            when (val d = retry.next(trouble, retryAfter)) {
                is RetryDecision.GiveUp -> {
                    if (d.error == DownloadError.ServerBusy) busyFailure.set(true)
                    Logger.error("XDM", "$what: gave up, ${d.error}")
                    return null
                }
                is RetryDecision.Wait -> {
                    Logger.info("XDM", "$what: ${if (code != 0) code else f?.message}, again in ${d.ms} ms")
                    if (!RetryPolicy.sleep(d.ms) { context.stopFlag.get() }) return null
                }
            }
        }
        return null
    }

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
        worker = Thread {
            segmentsGone()?.let { problem ->
                // Nothing is touched, so the state is still there if the segments come back.
                Logger.error("XDM", "Download ${context.id} cannot resume: segments missing in ${context.tempFolder}")
                context.httpClient.close()
                context.downloadHost.onDownloadFailed(context.id, problem)
                return@Thread
            }
            File(context.tempFolder).mkdirs()
            download()
        }.also { it.start() }
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
            awaitWorker()
            saveContext()
            throttle.disable()
            context.downloadHost.onDownloadPaused(context.id, PauseEvent.PausedByUser)
        }.start()
    }

    /**
     * Waits for the download thread to see the stop. While assembling it is muxing or committing, and it
     * deletes the partial output on its way out: reporting the pause before that would let a quick resume
     * open the output while the old thread is still deleting it. The muxer checks the stop flag between
     * reads, so this is short; the cap only guards against an I/O call that never returns.
     */
    private fun awaitWorker() {
        val thread = worker ?: return
        if (thread === Thread.currentThread()) return
        try {
            thread.join(STOP_WAIT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) Logger.error("XDM", "Download ${context.id} still busy ${STOP_WAIT_MS}ms after stop")
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
        // The segments were moved into a playlist folder that wasn't published yet: the next assembly publishes it.
        if (PlaylistFolder(context.tempFolder).isBuilt) return null
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
            clock.startRun()
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
            // The output is assembled in the target folder: check it takes writes now, before any
            // segment is fetched, not after the whole video has downloaded.
            if (!outputFile().folder.prepareForWriting()) {
                context.downloadHost.onDownloadFailed(context.id, DownloadError.OutputWriteError)
                return
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
            val unfinished = context.chunks.filter { it.tag != SUBS_TAG && it.status.get() != ChunkStatus.Finished }
            if (unfinished.isNotEmpty()) {
                Logger.error("XDM", "${unfinished.size} segments not downloaded")
                // All failed the same way: that is the reason; otherwise the one that failed last.
                val errors = unfinished.mapNotNull { it.error.get() }.distinct()
                var error = errors.singleOrNull() ?: lastFailure ?: errors.firstOrNull() ?: DownloadError.InternalError
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

    /**
     * All chunks are downloaded: sends their final state at once (the once-a-second updates may have missed
     * the last ones), so the window's table shows every slot finished, and keeps it, while assembling.
     */
    private fun reportFinished() {
        synchronized(this) {
            progressTracker.finish(context.chunks)
            prgInfo.apply {
                this.progress = 100
                this.downloaded = context.chunks.sumOf { it.downloaded.get() }
                this.speed = 0f
                this.eta = 0
                this.segments = progressTracker.segmentData
                this.slots = progressTracker.slotData
            }
        }
        context.downloadHost.onDownloadProgress(prgInfo)
    }

    private fun assemble() {
        if (context.stopFlag.get()) return
        reportFinished()
        context.assembling.set(true)
        context.downloadHost.onAssembleStart(context.id)
        val tmpFile = outputFile()
        // A previous run built the playlist folder (nothing else could keep the download) but didn't publish it.
        if (PlaylistFolder(context.tempFolder).isBuilt) { publishPlaylistFolder(); return }
        // A previous run muxed successfully but the commit failed: only retry the commit.
        val alreadyMuxed = context.completed.get() && tmpFile.exists
        if (!alreadyMuxed) {
            try {
                postProcessChunks()
            } catch (e: IOException) {
                Logger.error("XDM", "Decryption failed", e)
                if (context.stopFlag.get()) return
                val error = when {
                    isOutOfSpace(e) -> DownloadError.DiskSpaceError
                    e is DecryptionException -> DownloadError.DecryptionError
                    else -> throw e
                }
                context.downloadHost.onDownloadFailed(context.id, error)
                return
            }
            val outFolder = tmpFile.folder
            if (!outFolder.create()) {
                Logger.error("XDM", "Unable to create output folder $outFolder")
                context.downloadHost.onDownloadFailed(context.id, DownloadError.OutputWriteError)
                return
            }
            muxer.setSegmentInfo(segmentInfo())
            muxer.setKeepOriginalFormat(keepOriginalFormat)
            val ret = if (context.hasSeparateStreams) {
                assembleStreams(tmpFile)
            } else {
                assembleSingle(tmpFile)
            }
            if (context.stopFlag.get()) return
            if (!ret) {
                // A full disk pauses (the next start tries again); anything else keeps the download as files.
                if (isOutOfSpace(assembleError ?: muxer.lastError)) {
                    context.downloadHost.onDownloadFailed(context.id, DownloadError.DiskSpaceError)
                } else {
                    keepAsFiles(tmpFile)
                }
                return
            }
            // The container written may not be the part file's (MuxContainer): the published name follows it.
            context.muxedContainer = muxer.outputContainer
            note = when (muxer.outputKind) {
                OutputKind.TS_FALLBACK -> AssemblyNote.TS_FALLBACK
                OutputKind.AS_IS -> AssemblyNote.AS_IS
                OutputKind.REMUXED -> null
            }
        } else {
            Logger.info("XDM", "Output already muxed, retrying commit: $tmpFile")
        }
        throttle.disable()
        Logger.info("Committing to final file")
        when (val res = context.downloadHost.commitOutputFile(context.id, tmpFile, downloadType(), context.muxedContainer)) {
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
                        res.outputDir,
                        note = note,
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
            assembleError = e
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
            assembleError = e
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

    /** How this assembly kept the download when it wasn't the usual remux (for the completion note). */
    private var note: AssemblyNote? = null

    /** What the muxer's fallbacks need to know about each segment file. */
    private fun segmentInfo(): Map<String, SegmentInfo> =
        context.chunks.filter { it.tag != SUBS_TAG }.associate { getChunkTempFileName(it) to SegmentInfo(it.duration, it.discontinuity, it.init) }

    private fun segments(tag: String?): List<PlaylistFolder.Segment> =
        context.chunks.filter { it.tag != SUBS_TAG && (tag == null || it.tag == tag) }
            .map { PlaylistFolder.Segment(File(getChunkTempFileName(it)), it.duration, it.discontinuity, it.init) }

    /**
     * The remux and its one-file fallbacks failed: the download is kept rather than lost. HLS-playable
     * segments (TS, fMP4, packed audio) become a playlist folder; anything else is published as files side
     * by side. Only when even that fails does the download end in [DownloadError.MuxError], segments kept.
     */
    private fun keepAsFiles(tmpFile: StorageFile) {
        Logger.info("XDM", "Remux failed (${assembleError ?: muxer.lastError}); keeping the download as files")
        val video = segments(if (context.hasSeparateStreams) "VIDEO" else null)
        val audio = if (context.hasSeparateStreams) segments("AUDIO") else emptyList()
        val folder = PlaylistFolder(context.tempFolder)
        if (folder.canCarry(video + audio) && folder.build(video, audio)) {
            publishPlaylistFolder()
            return
        }
        if (context.stopFlag.get()) return
        if (publishSideBySide(tmpFile, video, audio)) return
        context.downloadHost.onDownloadFailed(context.id, DownloadError.MuxError)
    }

    private fun publishPlaylistFolder() {
        val folder = PlaylistFolder(context.tempFolder)
        val size = folder.dir.listFiles()?.sumOf { it.length() } ?: 0L
        when (val res = context.downloadHost.commitOutputFolder(context.id, folder.dir.path, PlaylistFolder.ENTRY, downloadType())) {
            is CommitResult.Failed -> {
                if (context.stopFlag.get() || res.error == DownloadError.Cancelled) return
                Logger.error("XDM", "Publishing the playlist folder failed: ${res.error}; the segments stay in ${folder.dir}")
                context.downloadHost.onDownloadFailed(context.id, res.error)
            }
            is CommitResult.Success -> {
                saveContext()
                saveSubtitle(res.outputDir, res.fileName)
                cleanup()
                Logger.info("XDM", "Saved as a playlist folder: ${res.outputDir} ${res.fileName}")
                context.downloadHost.onDownloadSuccess(
                    DownloadStatusInfo.FinalInfo(context.id, size, res.fileName, res.outputDir, note = AssemblyNote.PLAYLIST_FOLDER)
                )
            }
        }
    }

    /**
     * Files no playlist can carry: each track (or, for one list that can't be joined, each part) as its
     * own file — the first is the download's output, the others are published beside it.
     */
    private fun publishSideBySide(tmpFile: StorageFile, video: List<PlaylistFolder.Segment>, audio: List<PlaylistFolder.Segment>): Boolean {
        val groups: List<Pair<List<File>, String>> = if (audio.isNotEmpty()) {
            listOf(video.map { it.file } to "", audio.map { it.file } to "audio")
        } else video.mapIndexed { i, s -> listOf(s.file) to if (i == 0) "" else "part${i + 1}" }
        if (groups.isEmpty() || groups.any { it.first.isEmpty() }) return false
        // Only media: bytes nothing recognises (an error page, a damaged download) aren't published as files.
        if (groups.any { (files, _) -> SegmentProbe.foreign(files[0].path) == null && SegmentProbe.locate(files[0].path) == null }) return false
        return try {
            joinInto(groups[0].first, tmpFile)
            val res = context.downloadHost.commitOutputFile(context.id, tmpFile, downloadType(), extensionOf(groups[0].first[0]))
            if (res !is CommitResult.Success) return false
            val others = ArrayList<String>()
            var allPublished = true
            for ((files, suffix) in groups.drop(1)) {
                val joined = File(context.tempFolder, "side-$suffix.${extensionOf(files[0])}")
                joined.outputStream().use { out -> for (f in files) f.inputStream().use { it.copyTo(out) } }
                context.downloadHost.commitSidecar(context.id, joined.path, res.outputDir, res.fileName, suffix)?.let { others.add(it) }
                    ?: run { allPublished = false; Logger.error("XDM", "Couldn't publish $joined beside ${res.fileName}; it stays in ${context.tempFolder}") }
            }
            saveContext()
            // What couldn't be published beside the output stays in the temp folder rather than being deleted.
            if (allPublished) cleanup()
            Logger.info("XDM", "Saved as separate files: ${res.fileName} + $others")
            context.downloadHost.onDownloadSuccess(
                DownloadStatusInfo.FinalInfo(
                    context.id, context.downloadHost.storage.folder(res.outputDir).length(res.fileName), res.fileName, res.outputDir,
                    note = AssemblyNote.SEPARATE_FILES, noteDetail = others.joinToString(", "),
                )
            )
            true
        } catch (e: Exception) {
            Logger.error("XDM", "Publishing the files side by side failed", e)
            false
        }
    }

    private fun joinInto(files: List<File>, out: StorageFile) {
        out.open(create = true).use { handle ->
            handle.setLength(0)
            val buf = ByteArray(256 * 1024)
            for (f in files) f.inputStream().use { input ->
                while (true) { val n = input.read(buf); if (n <= 0) break; handle.write(buf, 0, n) }
            }
            handle.force()
        }
    }

    private fun extensionOf(f: File): String = SegmentProbe.extensionOf(f.path)

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
                fetchWithRetry("Playlist") { onErrorResponse, onError ->
                    downloadManifestAsFile(
                        context.httpClient, url, context.headers, context.cookie, context.stopFlag, onError,
                        onErrorResponse = onErrorResponse,
                    )
                }?.let { path ->
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
                this.slots = progressTracker.slotData
            }
            context.downloaded.addAndGet(downloaded)
            // Data came in: the download is not waiting on the server any more.
            if (prgInfo.retryAt != 0L) {
                prgInfo.retryAt = 0
                context.downloadHost.onDownloadProgress(prgInfo)
            }
            if (update) {
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
        latch.countDown()
        synchronized(this) {
            if (context.stopFlag.get()) return
            if (pc.status.get() == ChunkStatus.Finished) {
                context.pieceCompletedCount.addAndGet(1)
            } else if (pc.status.get() == ChunkStatus.Failed) {
                lastFailure = pc.error.get()
            }
            saveContext()
        }
    }

    /**
     * A segment starts ([until] > 0) or stops waiting to ask again. While the whole download is stalled, the
     * progress says when the next attempt is due ([DownloadStatusInfo.ProgressInfo.retryAt]).
     */
    private fun onChunkWait(pc: StreamingChunk, until: Long) {
        if (until > 0) waits[pc.id] = until else waits.remove(pc.id)
        synchronized(this) {
            if (context.stopFlag.get()) return
            val at = if (clock.stalled()) waits.values.minOrNull() ?: 0L else 0L
            if (at != prgInfo.retryAt && (at != 0L || until > 0)) {
                prgInfo.retryAt = at
                context.downloadHost.onDownloadProgress(prgInfo)
            }
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
        clock = clock,
        waitCallback = this::onChunkWait,
    ) { p ->
        onChunkComplete(p, latch)
    }
}

/** Marks a manifest slot whose file was discarded; see [StreamingDownloaderTask.discardManifest]. */
private val DISCARDED_MANIFEST = File("")

/** How long a stop waits for the download thread before reporting the pause anyway. */
private const val STOP_WAIT_MS = 30_000L
