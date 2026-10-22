package xdm.core.downloaders.web.batch

import xdm.core.CoreConfig
import xdm.core.downloaders.BatchDownloadTaskInfo
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.DownloaderTask
import xdm.core.downloaders.PauseEvent
import xdm.core.downloaders.web.ProgressTracker
import xdm.core.downloaders.web.SegmentProgress
import xdm.core.downloaders.web.SpeedLimiter
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.media.muxer.Muxer
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.PoolingHttpClient
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.fixExtension
import xdm.core.util.getExtension
import xdm.core.util.getFileNameWithoutExtension
import xdm.core.util.getNameFromContentDisposition
import xdm.core.storage.LocalStorage
import xdm.core.storage.Storage
import xdm.core.storage.StorageFolder
import xdm.core.storage.TargetExistsException
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Downloads every item of a batch as its own file in the batch folder, [maxPiece] at a time through
 * one HTTP client (one connection pool), like a streaming download fetches its segments.
 *
 * Each file is written to a part file in the batch folder and renamed to its real name as soon
 * as it is complete, so finished files are usable while the rest download and nothing is copied at
 * the end. A file that fails does not stop the others: the batch finishes with
 * [DownloadStatusInfo.FinalInfo.failedFiles] set. Only a write failure (disk full, folder gone) stops
 * the whole batch. Resuming retries every file that is not finished.
 *
 * A merged item (a video played as separate video and audio tracks) downloads its two tracks into
 * [BatchDownloadTaskInfo.trackFolder], each like a file of its own, then merges them with a muxer from
 * [muxerFactory] into its part file in the batch folder and renames that like any other file.
 */
class BatchDownloaderTask(
    private val task: BatchDownloadTaskInfo,
    private val http: PoolingHttpClient,
    private val host: DownloadHost,
    private val configDir: String,
    private val config: CoreConfig,
    /** One muxer per merge, so merged items can merge side by side. Needed only when an item is merged. */
    private val muxerFactory: (() -> Muxer)? = null,
) : DownloaderTask, BatchFileListener {
    private val context: BatchTaskContext = (loadBatchState(task.id, configDir).getOrNull()
        ?.takeIf { it.files.size == task.items.size }
        ?: BatchTaskContext(
            task.id,
            host.storage.folder(task.folder).child(task.name).location,
            List(task.items.size) { BatchFile(it) }
        )).also { ctx ->
        // A merged item always has its two tracks, also in a state saved without them.
        for (f in ctx.files) {
            if (task.items[f.index].merged && !f.merged) {
                f.video = BatchFile(f.index)
                f.audio = BatchFile(f.index)
            }
        }
    }

    private val folder: StorageFolder = host.storage.folder(context.batchFolder)

    /** Merged items' tracks, as local files: the muxer reads them with random access. */
    private val trackFolder: File? = task.trackFolder?.let(::File)

    /** Merges running now, so a stop can cut them short. */
    private val activeMuxers = CopyOnWriteArrayList<Muxer>()

    /** Counts items down as they settle (finished, failed or stopped); set by [download]. */
    @Volatile
    private var latch: CountDownLatch? = null

    private val executor: ExecutorService =
        Executors.newFixedThreadPool(task.maxPiece.takeIf { it > 0 } ?: config.maxSegments)
    private val stopFlag = AtomicBoolean(false)
    private val startRequested = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    /** A write failure that stops the whole batch; null while files fail (or succeed) one by one. */
    private val fatalError = AtomicReference<DownloadError?>(null)

    private val progressTracker = ProgressTracker(false)
    private val throttle = SpeedLimiter(config)
    private val totalDownloaded = AtomicLong(0)
    private val finished = AtomicInteger(0)
    private val failed = AtomicInteger(0)

    private val saveLock = Any()
    private val dirty = AtomicBoolean(false)
    @Volatile
    private var lastSave = 0L

    /**
     * Lower-cased names the batch has claimed in its folder (each file's final name, or the name it was
     * given in the dialog), so a name from the server never lands on another file's name.
     */
    private val takenNames = HashSet<String>()

    init {
        for (f in context.files) takenNames.add(nameOf(f).lowercase())
    }

    override fun start() {
        if (!startRequested.compareAndSet(false, true)) return
        Thread { download() }.start()
    }

    override fun resume() = start()

    override fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        Thread {
            stopFlag.set(true)
            activeMuxers.forEach { runCatching { it.stop() } }
            executor.shutdownNow()
            // Closing the client also cancels in-flight reads.
            http.close()
            throttle.disable()
            saveState()
            host.onDownloadPaused(task.id, PauseEvent.PausedByUser)
        }.start()
    }

    /** Removes the unfinished part files and merged items' tracks; the finished files stay. */
    override fun deleteTemp() {
        deleteBatchPartFiles(folder)
        deleteBatchTracks(task)
    }

    private fun download() {
        try {
            host.onDownloadActivated(task.id)
            if (!folder.create()) {
                Logger.error("XDM", "Unable to create batch folder $folder")
                host.onDownloadFailed(task.id, DownloadError.OutputWriteError)
                return
            }
            if (context.files.any { it.merged && it.status.get() != ChunkStatus.Finished } &&
                trackFolder?.let { it.isDirectory || it.mkdirs() } != true
            ) {
                Logger.error("XDM", "Unable to create track folder $trackFolder")
                host.onDownloadFailed(task.id, DownloadError.DiskSpaceError)
                return
            }
            // A start retries every file that is not finished, including those that failed last time.
            for (f in context.files) {
                if (f.status.get() != ChunkStatus.Finished) {
                    (listOf(f) + f.tracks).filter { it.status.get() != ChunkStatus.Finished }.forEach {
                        it.status.set(ChunkStatus.Ready)
                        it.error.set(null)
                    }
                }
            }
            finished.set(context.finishedCount)
            totalDownloaded.set(context.files.sumOf { it.bytes })
            saveState()

            val pending = context.files.filter { it.status.get() != ChunkStatus.Finished }
            val latch = CountDownLatch(pending.size)
            this.latch = latch
            for (f in pending) {
                if (f.merged) startMerged(f) else executor.submit(retriever(f, latch))
            }
            while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                if (stopFlag.get()) break
            }
            if (stopRequested.get()) return // stop() saves and reports the pause
            fatalError.get()?.let {
                saveState()
                throttle.disable()
                host.onDownloadFailed(task.id, it)
                return
            }
            if (stopFlag.get()) return
            complete()
        } catch (ex: Exception) {
            Logger.error("XDM", "Batch download failed", ex)
            if (!stopRequested.get()) host.onDownloadFailed(task.id, DownloadError.InternalError)
        } finally {
            executor.shutdownNow()
            http.close()
        }
    }

    private fun complete() {
        throttle.disable()
        trackFolder?.delete() // only if every merge cleaned up after itself
        saveState()
        val size = context.files.filter { it.status.get() == ChunkStatus.Finished }.sumOf { it.downloaded.get() }
        val failedFiles = context.failedCount
        Logger.info("XDM", "Batch ${task.id} done: ${context.finishedCount} finished, $failedFiles failed")
        host.onDownloadSuccess(
            DownloadStatusInfo.FinalInfo(
                id = task.id,
                fileSize = size,
                finalFileName = task.name,
                finalOutputFolder = task.folder,
                failedFiles = failedFiles,
            )
        )
    }

    private fun retriever(f: BatchFile, latch: CountDownLatch): Runnable {
        val item = task.items[f.index]
        val retriever = BatchFileRetriever(
            file = f,
            url = item.url,
            partFile = folder.file(batchPartName(f.index)),
            httpClient = http,
            headers = task.headers,
            cookie = task.cookies.getOrNull(item.cookieGroup),
            stopFlag = stopFlag,
            maxRetries = config.maxRetries,
            listener = this,
        )
        return Runnable {
            try {
                retriever.run()
            } finally {
                latch.countDown()
            }
        }
    }

    private fun nameOf(f: BatchFile) = f.finalName ?: task.items[f.index].fileName

    // ---- merged items ----

    private fun trackFile(f: BatchFile, track: BatchFile): File =
        File(trackFolder, "${f.index}.${if (track === f.audio) "audio" else "video"}")

    /** Downloads the tracks that are not done yet, or goes straight to the merge when both are. */
    private fun startMerged(f: BatchFile) {
        f.status.set(ChunkStatus.Downloading)
        val waiting = f.tracks.filter { it.status.get() != ChunkStatus.Finished }
        if (waiting.isEmpty()) {
            executor.submit { merge(f) }
            return
        }
        val left = AtomicInteger(waiting.size)
        val item = task.items[f.index]
        for (track in waiting) {
            val retriever = BatchFileRetriever(
                file = track,
                url = if (track === f.audio) item.audioUrl!! else item.url,
                partFile = LocalStorage.file(trackFile(f, track).path),
                httpClient = http,
                headers = task.headers,
                cookie = task.cookies.getOrNull(item.cookieGroup),
                stopFlag = stopFlag,
                maxRetries = config.maxRetries,
                listener = TrackListener(f, left),
            )
            executor.submit(retriever)
        }
    }

    /** A merged item's track: no name to pick or rename, it stays in the track folder until merged. */
    private inner class TrackListener(private val item: BatchFile, private val left: AtomicInteger) : BatchFileListener {
        override fun onConnected(file: BatchFile, response: HttpResponse) {
            dirty.set(true)
        }

        override fun onBytes(file: BatchFile, count: Long) = this@BatchDownloaderTask.onBytes(file, count)

        override fun onComplete(file: BatchFile): DownloadError? = null

        override fun onDone(file: BatchFile) {
            dirty.set(true)
            if (stopFlag.get()) return
            when (file.status.get()) {
                ChunkStatus.Failed -> settle(item, ChunkStatus.Failed, file.error.get() ?: DownloadError.InternalError)
                ChunkStatus.Finished -> if (left.decrementAndGet() == 0) merge(item)
                else -> {}
            }
        }
    }

    /** Merges a merged item's two tracks into its part file, then gives it its final name. */
    private fun merge(f: BatchFile) {
        if (stopFlag.get() || f.status.get() != ChunkStatus.Downloading) return
        val muxer = muxerFactory?.invoke()
        if (muxer == null) {
            Logger.error("XDM", "Batch ${task.id}: no muxer for merged item ${f.index}")
            settle(f, ChunkStatus.Failed, DownloadError.InternalError)
            return
        }
        val part = folder.file(batchPartName(f.index))
        f.merging = true
        reportProgress(0)
        val ok = try {
            activeMuxers.add(muxer)
            muxer.mux(trackFile(f, f.video!!).path, trackFile(f, f.audio!!).path, part, {}, trackFolder!!.path)
        } catch (e: Exception) {
            Logger.error("XDM", "Batch ${task.id}: merging item ${f.index} failed", e)
            false
        } finally {
            activeMuxers.remove(muxer)
            f.merging = false
        }
        if (stopFlag.get()) {
            // Both tracks are kept: the next start merges again.
            part.delete()
            return
        }
        if (!ok) {
            part.delete()
            settle(f, ChunkStatus.Failed, DownloadError.InternalError)
            return
        }
        val size = part.length()
        val error = onComplete(f)
        if (error != null) {
            settle(f, ChunkStatus.Failed, error)
            return
        }
        f.length.set(size)
        f.downloaded.set(size)
        f.tracks.forEach { trackFile(f, it).delete() }
        settle(f, ChunkStatus.Finished, null)
    }

    /** A merged item is done, one way or the other: counted, and its slot in [latch] released, once. */
    private fun settle(f: BatchFile, status: ChunkStatus, error: DownloadError?) {
        synchronized(f) {
            if (f.status.get() != ChunkStatus.Downloading) return
            f.error.set(error)
            f.status.set(status)
        }
        onDone(f)
        latch?.countDown()
    }

    override fun onConnected(file: BatchFile, response: HttpResponse) {
        val part = folder.file(batchPartName(file.index))
        if (!part.exists) runCatching { part.open(create = true).close() }
        if (file.finalName != null) return
        val item = task.items[file.index]
        var name = item.fileName
        val fromServer = if (item.nameFromPage) null
        else FileUtils.sanitizeFileName(getNameFromContentDisposition(response.contentDisposition))
        // A page's, user's or server's name only gets a missing extension; one taken from the URL may lose a wrong one.
        val wanted = fixExtension(fromServer ?: name, response.contentType, replace = fromServer == null && !item.nameFromPage)
        if (!wanted.equals(name, ignoreCase = true)) {
            synchronized(takenNames) {
                takenNames.remove(name.lowercase())
                name = uniqueName(wanted)
                takenNames.add(name.lowercase())
            }
        }
        file.finalName = name
        dirty.set(true)
    }

    /** [name], or `name_1.ext`, `name_2.ext`… — the first neither claimed nor on disk. Holds [takenNames]. */
    private fun uniqueName(name: String): String {
        val base = getFileNameWithoutExtension(name)
        val ext = getExtension(name) ?: ""
        var candidate = name
        var n = 0
        while (candidate.lowercase() in takenNames || folder.exists(candidate)) {
            candidate = "${base}_${++n}$ext"
        }
        return candidate
    }

    override fun onComplete(file: BatchFile): DownloadError? {
        val part = batchPartName(file.index)
        synchronized(takenNames) {
            repeat(3) {
                var name = nameOf(file)
                // Something else (not this batch) took the name: never overwrite it.
                if (folder.exists(name)) {
                    takenNames.remove(name.lowercase())
                    name = uniqueName(name)
                    takenNames.add(name.lowercase())
                }
                try {
                    folder.rename(part, name, replace = false)
                    file.finalName = name
                    dirty.set(true)
                    return null
                } catch (e: TargetExistsException) {
                    file.finalName = name
                    Logger.info("XDM", "$name appeared while renaming, trying another name")
                } catch (e: IOException) {
                    Logger.error("XDM", "Unable to rename $part to $name in $folder", e)
                    return DownloadError.OutputWriteError
                }
            }
        }
        return DownloadError.OutputWriteError
    }

    override fun onBytes(file: BatchFile, count: Long) {
        val total = totalDownloaded.addAndGet(count)
        reportProgress(count)
        throttle.throttleIfNeeded(total)
    }

    override fun onDone(file: BatchFile) {
        when (file.status.get()) {
            ChunkStatus.Finished -> finished.incrementAndGet()
            ChunkStatus.Failed -> {
                failed.incrementAndGet()
                val error = file.error.get()
                if (error == DownloadError.DiskSpaceError || error == DownloadError.OutputWriteError) {
                    // Every other file would fail the same way: stop the batch.
                    if (fatalError.compareAndSet(null, error)) {
                        stopFlag.set(true)
                        executor.shutdownNow()
                    }
                }
            }

            else -> {}
        }
        dirty.set(true)
        reportProgress(0)
    }

    private fun reportProgress(count: Long) {
        val total = context.files.size
        val done = finished.get()
        val failedNow = failed.get()
        val percent = if (total == 0) 100f else (done + failedNow) * 100f / total
        if (!progressTracker.update(count, null, percent)) return
        val segments = context.files.mapIndexed { i, f ->
            val len = f.size
            val fraction = when {
                f.status.get() == ChunkStatus.Finished || f.merging -> 1.0
                len > 0 -> (f.bytes.toDouble() / len).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            SegmentProgress(start = i * SLOT, length = SLOT, downloaded = (fraction * SLOT).toLong())
        }
        host.onDownloadProgress(
            DownloadStatusInfo.ProgressInfo(
                id = task.id,
                progress = progressTracker.progress,
                speed = progressTracker.downloadSpeed,
                eta = progressTracker.eta,
                downloaded = totalDownloaded.get(),
                segments = segments,
                filesDone = done,
                failedFiles = failedNow,
                filesTotal = total,
            )
        )
        if (dirty.get() && System.currentTimeMillis() - lastSave > SAVE_INTERVAL_MS) saveState()
    }

    /** Writes the resume state. Saves are coalesced by the callers: at most every few seconds while running. */
    private fun saveState() {
        synchronized(saveLock) {
            dirty.set(false)
            lastSave = System.currentTimeMillis()
            saveBatchState(context, configDir)
        }
    }

    private companion object {
        const val SLOT = 1000L
        const val SAVE_INTERVAL_MS = 2000L
    }
}

private val PART_FILE = Regex("""\.\d+\.xdm-part""")

/** Deletes the tracks of a batch's merged items, which live outside the batch folder. */
fun deleteBatchTracks(task: BatchDownloadTaskInfo) {
    task.trackFolder?.let { File(it).deleteRecursively() }
}

/** Deletes a batch's unfinished part files, and the folder if that leaves it empty. */
fun deleteBatchPartFiles(batchFolder: String, storage: Storage = LocalStorage) =
    deleteBatchPartFiles(storage.folder(batchFolder))

private fun deleteBatchPartFiles(folder: StorageFolder) {
    folder.listFiles().filter { PART_FILE.matches(it) }.forEach { folder.delete(it) }
    folder.deleteIfEmpty()
}

/**
 * Deletes what a batch put in its folder — the finished files named in [context] and the part files —
 * then the folder only if nothing else is left in it. Never deletes files the batch did not create.
 */
fun deleteBatchFiles(context: BatchTaskContext, storage: Storage = LocalStorage) {
    val folder = storage.folder(context.batchFolder)
    for (f in context.files) {
        if (f.status.get() == ChunkStatus.Finished) {
            f.finalName?.let { folder.delete(it) }
        }
    }
    deleteBatchPartFiles(folder)
}
