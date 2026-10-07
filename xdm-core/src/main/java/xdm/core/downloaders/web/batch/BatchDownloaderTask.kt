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
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.PoolingHttpClient
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.getExtension
import xdm.core.util.getFileNameWithoutExtension
import xdm.core.util.getNameFromContentDisposition
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 */
class BatchDownloaderTask(
    private val task: BatchDownloadTaskInfo,
    private val http: PoolingHttpClient,
    private val host: DownloadHost,
    private val configDir: String,
    private val config: CoreConfig,
) : DownloaderTask, BatchFileListener {
    private val context: BatchTaskContext = loadBatchState(task.id, configDir).getOrNull()
        ?.takeIf { it.files.size == task.items.size }
        ?: BatchTaskContext(task.id, task.batchFolder, List(task.items.size) { BatchFile(it) })

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
            executor.shutdownNow()
            // Closing the client also cancels in-flight reads.
            http.close()
            throttle.disable()
            saveState()
            host.onDownloadPaused(task.id, PauseEvent.PausedByUser)
        }.start()
    }

    /** Removes the unfinished part files; the finished files stay. */
    override fun deleteTemp() = deleteBatchPartFiles(context.batchFolder)

    private fun download() {
        try {
            host.onDownloadActivated(task.id)
            val folder = File(context.batchFolder)
            if (!folder.isDirectory && !folder.mkdirs()) {
                Logger.error("XDM", "Unable to create batch folder $folder")
                host.onDownloadFailed(task.id, DownloadError.OutputWriteError)
                return
            }
            // A start retries every file that is not finished, including those that failed last time.
            for (f in context.files) {
                if (f.status.get() != ChunkStatus.Finished) {
                    f.status.set(ChunkStatus.Ready)
                    f.error.set(null)
                }
            }
            finished.set(context.finishedCount)
            totalDownloaded.set(context.files.sumOf { it.downloaded.get() })
            saveState()

            val pending = context.files.filter { it.status.get() != ChunkStatus.Finished }
            val latch = CountDownLatch(pending.size)
            for (f in pending) {
                executor.submit(retriever(f, latch))
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
            partFile = batchPartFile(context.batchFolder, f.index),
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

    override fun onConnected(file: BatchFile, response: HttpResponse) {
        val part = batchPartFile(context.batchFolder, file.index)
        if (!part.exists()) runCatching { part.createNewFile() }
        if (file.finalName != null) return
        val item = task.items[file.index]
        var name = item.fileName
        if (!item.nameFromPage) {
            val fromServer = FileUtils.sanitizeFileName(getNameFromContentDisposition(response.contentDisposition))
            if (fromServer != null && !fromServer.equals(name, ignoreCase = true)) {
                synchronized(takenNames) {
                    takenNames.remove(name.lowercase())
                    name = uniqueName(fromServer)
                    takenNames.add(name.lowercase())
                }
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
        while (candidate.lowercase() in takenNames || File(context.batchFolder, candidate).exists()) {
            candidate = "${base}_${++n}$ext"
        }
        return candidate
    }

    override fun onComplete(file: BatchFile): DownloadError? {
        val part = batchPartFile(context.batchFolder, file.index).toPath()
        synchronized(takenNames) {
            repeat(3) {
                var name = nameOf(file)
                // Something else (not this batch) took the name: never overwrite it.
                if (File(context.batchFolder, name).exists()) {
                    takenNames.remove(name.lowercase())
                    name = uniqueName(name)
                    takenNames.add(name.lowercase())
                }
                val target = File(context.batchFolder, name).toPath()
                try {
                    try {
                        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: AtomicMoveNotSupportedException) {
                        Files.move(part, target)
                    }
                    file.finalName = name
                    dirty.set(true)
                    return null
                } catch (e: FileAlreadyExistsException) {
                    file.finalName = name
                    Logger.info("XDM", "$target appeared while renaming, trying another name")
                } catch (e: IOException) {
                    Logger.error("XDM", "Unable to rename $part to $target", e)
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
            val len = f.length.get()
            val fraction = when {
                f.status.get() == ChunkStatus.Finished -> 1.0
                len > 0 -> (f.downloaded.get().toDouble() / len).coerceIn(0.0, 1.0)
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

/** Deletes a batch's unfinished part files, and the folder if that leaves it empty. */
fun deleteBatchPartFiles(batchFolder: String) {
    val folder = File(batchFolder)
    folder.listFiles { f -> f.isFile && PART_FILE.matches(f.name) }?.forEach { it.delete() }
    deleteFolderIfEmpty(folder)
}

/**
 * Deletes what a batch put in its folder — the finished files named in [context] and the part files —
 * then the folder only if nothing else is left in it. Never deletes files the batch did not create.
 */
fun deleteBatchFiles(context: BatchTaskContext) {
    for (f in context.files) {
        if (f.status.get() == ChunkStatus.Finished) {
            f.finalName?.let { File(context.batchFolder, it).delete() }
        }
    }
    deleteBatchPartFiles(context.batchFolder)
}

private fun deleteFolderIfEmpty(folder: File) {
    if (folder.isDirectory && folder.list()?.isEmpty() == true) folder.delete()
}
