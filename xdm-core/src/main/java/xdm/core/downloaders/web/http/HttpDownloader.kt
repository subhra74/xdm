package xdm.core.downloaders.web.http

import xdm.core.CoreConfig
import xdm.core.downloaders.*
import xdm.core.downloaders.web.ProgressTracker
import xdm.core.downloaders.web.SpeedLimiter
import xdm.core.downloaders.web.loadState
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.Logger
import xdm.core.util.CoreUtils
import xdm.core.util.MoveOps
import java.io.File
import java.net.Proxy
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface ChunkController : DownloaderTask {
    fun onChunkConnected(id: Long, data: ChunkConfirmedData?)
    fun updateBytesDownloaded(id: Long, downloaded: Long)
    fun onChunkFailed(id: Long, error: DownloadError)
    fun onChunkFinished(id: Long)
    fun takeOverChunk(chunkId: Long, maxByteRange: Long): Boolean
}

fun makeContext(
    task: HttpDownloadTaskInfo,
    host: DownloadHost,
    configDir: String,
    httpClient: PoolingHttpClient,
): Pair<HttpTaskContext, Boolean> {
    loadState(
        id = task.id,
        configDir = configDir,
        http = httpClient,
        host = host
    ).onSuccess {
        it.httpClient = httpClient
        normalizeRestoredChunks(it)
        if (!mustStartOver(it)) return Pair(it, false)
        Logger.info("XDM", "Download ${task.id} cannot resume from its saved state, starting over")
        File(it.tempFolder, it.tempFileName).delete()
    }.onFailure {
        Logger.info("Unable to load saved download state, starting new download: ${task.id}")
    }
    return Pair(
        HttpTaskContext(
            id = task.id,
            chunks = ConcurrentHashMap<Long, Chunk>(),
            init = AtomicBoolean(false),
            totalSize = null,
            downloaded = AtomicLong(0),
            url = task.url,
            contentType = null,
            headers = task.headers,
            cookie = task.cookie,
            stopFlag = AtomicBoolean(false),
            completed = AtomicBoolean(false),
            tempFileCreated = AtomicBoolean(false),
            tempFileName = "${CoreUtils.uniqueId()}.tmp",
            diskError = AtomicBoolean(false),
            downloadHost = host,
            tempFolder = task.defaultDownloadFolder,
        ).apply { this.httpClient = httpClient }, true
    )
}

/**
 * A pause or crash between a chunk's last byte and its Finished status update persists a complete
 * chunk as Downloading. Mark such chunks Finished on restore so resume never starts a thread for
 * them (HttpChunkRetriever.isAlreadyDone also handles this case, under the write lock).
 */
private fun normalizeRestoredChunks(ctx: HttpTaskContext) {
    if (!ctx.init.get()) return
    ctx.chunks.values.forEach { c ->
        val len = c.length.get()
        if (c.status.get() != ChunkStatus.Finished && len > 0 && c.downloaded.get() >= len) {
            Logger.info("XDM", "Restored chunk ${c.id} is complete; marking Finished")
            c.status.set(ChunkStatus.Finished)
        }
    }
}

/**
 * Whether a saved download has to start over as a new download instead of resuming:
 * - Its temp file was deleted or cut short, so the bytes it held are gone.
 * - Its first response had no Content-Length, so it can only ever request `bytes=0-` and the bytes
 *   already on disk cannot be continued. Starting over also lets a server that now sends a length run
 *   it as a normal sized download.
 * - The server does not do ranges ([HttpTaskContext.noRangeSupport]), so nothing can be continued
 *   either. (For both, a completed download only failed to publish, and resuming publishes it.)
 * - It was saved as finished, but its pieces do not make up the file ([piecesMakeUpFile]), so
 *   publishing it would hand over a corrupt file.
 */
private fun mustStartOver(ctx: HttpTaskContext): Boolean {
    if (!ctx.init.get()) return false
    if (ctx.tempFileCreated.get() && !ctx.tempFileIntact()) return true
    if (!ctx.completed.get() && (ctx.totalSize == null || ctx.noRangeSupport)) return true
    if (ctx.totalSize == null) return false
    val finished = ctx.completed.get() || ctx.chunks.values.all { it.status.get() == ChunkStatus.Finished }
    return finished && !ctx.piecesMakeUpFile()
}

/** The temp file is still there and, with a known size, still that big (it is created at full size). */
private fun HttpTaskContext.tempFileIntact(): Boolean {
    val file = File(tempFolder, tempFileName)
    return file.isFile && totalSize.let { it == null || file.length() == it }
}

/**
 * Whether the segments really make up the file: with a known size they must cover 0 until that size
 * exactly, each fully downloaded, and the temp file must be exactly that big. A file of unknown size
 * came from one stream read to its end, so there is nothing to compare it with.
 */
private fun HttpTaskContext.piecesMakeUpFile(): Boolean {
    val size = totalSize ?: return true
    if (File(tempFolder, tempFileName).length() != size) return false
    var next = 0L
    for (chunk in chunks.values.sortedBy { it.offset }) {
        if (chunk.offset != next || chunk.downloaded.get() < chunk.length.get()) return false
        next += chunk.length.get()
    }
    return next == size
}

class HttpDownloaderTask : ChunkController {
    private val context: HttpTaskContext
    private val configDir: String
    private val prgInfo: DownloadStatusInfo.ProgressInfo
    private val throttle: SpeedLimiter
    private val stopRequested = AtomicBoolean(false)
    private val startRequested = AtomicBoolean(false)
    private val config: CoreConfig
    private val maxChunk: Int
    private val newDownload: Boolean

    constructor(
        task: HttpDownloadTaskInfo,
        host: DownloadHost,
        httpClient: PoolingHttpClient,
        configDir: String,
        config: CoreConfig
    ) {
        val (ctx, nd) = makeContext(task, host, configDir, httpClient)
        this.context = ctx
        this.newDownload = nd
        this.configDir = configDir
        this.prgInfo = DownloadStatusInfo.ProgressInfo(id = context.id)
        this.throttle = SpeedLimiter(config)
        this.config = config
        // The new-download dialog can override the configured segment count per download.
        this.maxChunk = task.maxPiece.takeIf { it > 0 } ?: config.maxSegments
    }

//    constructor(
//        id: Long,
//        configDir: String,
//        httpClient: PoolingHttpClient,
//        host: DownloadHost,
//        config: CoreConfig
//    ) {
//        this.context = loadState(id = id, configDir = configDir, http = httpClient, host = host).getOrThrow()
//        this.configDir = configDir
//        this.prgInfo = DownloadStatusInfo.ProgressInfo(id = context.id)
//        this.throttle = SpeedLimiter(config)
//        this.config = config
//        this.maxChunk = config.maxSegments
//    }

    /** Bytes written since the temp file was last forced to disk, when that was, and whether one is running. */
    private val unsyncedBytes = AtomicLong(0)
    private val lastSync = AtomicLong(System.currentTimeMillis())
    private val syncing = AtomicBoolean(false)

    /** When [updateBytesDownloaded] last looked for a free slot to split into. */
    private val lastSplitCheck = AtomicLong(0)
    private val progressTracker = ProgressTracker(singleFile = true)
    private val time = System.currentTimeMillis()

    /** The error of the most recent failed chunk, reported if the download ends up failing. */
    private val lastChunkError = AtomicReference<DownloadError?>(null)

    /** Set once the failure has been reported, so chunks failing together report it once. */
    private val failureReported = AtomicBoolean(false)

    override fun start() {
        if (!newDownload) {
            resume()
            return
        }
        if (startRequested.get()) {
            return
        }
        startRequested.set(true)
        Thread {
            context.downloadHost.onDownloadActivated(context.id)
            val id = CoreUtils.uniqueId()
            val chunk1 = Chunk(
                id = id,
                offset = 0,
                length = AtomicLong(0),
                downloaded = AtomicLong(0),
                status = AtomicReference(ChunkStatus.Ready),
                fileHandle = AtomicReference(null),
                lastTakeOver = AtomicLong(0)
            )
            context.chunks[id] = chunk1
            saveState()
            startChunk(id)
        }.start()
    }

    override fun resume() {
        if (newDownload) {
            start()
            return
        }
        if (startRequested.get()) {
            return
        }
        startRequested.set(true)
        Thread {
            context.downloadHost.onDownloadActivated(context.id)
            progressTracker.totalDownloadedBytes = context.downloaded.get()
            if (context.completed.get() || context.chunks.values.all { it.status.get() == ChunkStatus.Finished }) {
                finishDownload()
            } else {
                startChunks()
            }
        }.start()
    }

    override fun deleteTemp() {
        val tmpFile = File(context.tempFolder, context.tempFileName)
        Logger.info("XDM", "Deleting temp file ${tmpFile.absolutePath}")
        val ret = tmpFile.delete()
        Logger.info("XDM", "Deleted temp file $ret")
    }

    override fun stop() {
        if (stopRequested.get()) {
            return
        }
        stopRequested.set(true)
        Thread {
            context.write {
                context.stopFlag.set(true)
                context.chunks.values.forEach {
                    try {
                        it.fileHandle.get()?.close()
                    } catch (error: IOException) {
                        Logger.error("XDM", "Unable to close file", error)
                    }
                }
                syncAll()
                saveState()
                throttle.disable()
                // Closing the client also cancels in-flight calls, unblocking chunk threads stuck in a read.
                context.httpClient.close()
                context.downloadHost.onDownloadPaused(context.id, PauseEvent.PausedByUser)
            }
        }.start()
    }

    override fun onChunkFinished(id: Long) {
        if (!context.completed.get()) {
            context.write {
                if (context.chunks.values.any { it.status.get() != ChunkStatus.Finished }) {
                    // Hand the freed connection to a failed chunk first, else to the largest range still
                    // downloading.
                    splitChuck(context.chunks)
                    // Nothing could be restarted or split, and some chunks had failed: the download is stuck.
                    lastChunkError.get()?.let { if (isAllError()) reportFailure(it) }
                    return
                }
                if (!context.piecesMakeUpFile()) {
                    Logger.error("XDM", "Segments do not make up the file, not publishing it")
                    reportFailure(DownloadError.InternalError)
                    return
                }
                // Saved as completed only once the whole file is on disk.
                syncAll()
                context.completed.set(true)
                try {
                    Logger.info("XDM", "All chunks downloaded")
                    saveState()
                    val tmpFile = File(context.tempFolder, context.tempFileName)
                    val totalFileSize = context.totalSize ?: tmpFile.length()
                    val res =
                        context.downloadHost.commitOutputFile(context.id, tmpFile.absolutePath, DownloadType.Http)
                    Logger.info("XDM", "Move file success: - $res")
                    val now = System.currentTimeMillis()
                    when (res) {
                        is CommitResult.Failed -> {
                            // A cancelled publish means a stop is on its way (waiting for this lock),
                            // and it reports the pause; this is not a disk error.
                            if (context.stopFlag.get() || res.error == DownloadError.Cancelled) return
                            context.diskError.set(true)
                            onChunkFailed(id, res.error)
                        }

                        is CommitResult.Success -> {
                            Logger.info("Time taken ${(now - time) / 1000.0f} sec")
                            context.downloadHost.onDownloadSuccess(
                                DownloadStatusInfo.FinalInfo(
                                    context.id,
                                    totalFileSize,
                                    res.fileName,
                                    res.outputDir
                                )
                            )
                        }
                    }
                } finally {
                    context.httpClient.close()
                    throttle.disable()
                }
            }
        }
        return
    }

    override fun onChunkFailed(id: Long, error: DownloadError) {
        lastChunkError.set(error)
        if (isAllError()) reportFailure(error)
    }

    private fun reportFailure(error: DownloadError) {
        if (!failureReported.compareAndSet(false, true)) return
        Logger.error("XDM", "No chunk left downloading, stopping download - error: $error")
        val finalError =
            if (error == DownloadError.InvalidResponse && context.downloaded.get() > 0 && context.chunks.size > 2) {
                DownloadError.SessionExpired
            } else {
                error
            }
        // Saved before it is reported, like a pause: a resume started right away must load this state.
        context.write {
            syncAll()
            saveState()
        }
        context.downloadHost.onDownloadFailed(context.id, finalError)
        context.httpClient.close()
    }

    override fun onChunkConnected(id: Long, data: ChunkConfirmedData?) {
        if (!context.init.get() && data != null) {
            context.write {
                context.totalSize = data.contentLength
                data.contentLength?.let {
                    context.chunks[id]?.length?.set(it)
                }
                context.validator = data.validator
                context.contentType = data.contentType
                context.tempFolder = context.downloadHost.getTempDir(
                    context.id,
                    url = context.url,
                    contentType = data.contentType,
                    contentDisposition = data.contentDisposition
                )
                context.init.set(true)
                context.downloadHost.onDownloadInit(
                    DownloadStatusInfo.InitInfo(
                        id = context.id,
                        url = data.finalUrl ?: context.url,
                        isRedirect = data.isRedirect,
                        fileSize = data.contentLength,
                        contentDisposition = data.contentDisposition,
                        contentType = data.contentType
                    ),
                    DownloadType.Http
                )
                splitChuck(context.chunks)
                saveState()
            }
        } else {
            context.write {
                splitChuck(context.chunks)
            }
        }
    }

    private fun retryFailedChunk(len: Int): Int {
        var count = 0
        val failedChunks = context.chunks.values.filter { it.status.get() == ChunkStatus.Failed }.map { it.id }
        for (chunk in failedChunks) {
            if (count >= len) {
                break
            }
            context.chunks[chunk]?.let {
                it.status.set(ChunkStatus.Downloading)
                startChunk(chunk)
                count += 1
            }
        }
        return count
    }

    private fun startChunks() {
        // Under the lock: a chunk thread started here could otherwise reach splitChuck before the loop
        // ends, restart chunks still marked Failed, and the loop would then start them a second time.
        context.write {
            val unfinished = context.chunks.values.filter { it.status.get() != ChunkStatus.Finished }.sortedBy { it.offset }
            // At most the download's segment count at once, as when it started; the rest wait as Failed,
            // which splitChuck restarts as slots free up.
            unfinished.forEachIndexed { i, chunk ->
                if (i < maxChunk) {
                    chunk.status.set(ChunkStatus.Downloading)
                    startChunk(chunk.id)
                } else {
                    chunk.status.set(ChunkStatus.Failed)
                }
            }
        }
    }

    private fun findMaxChunk(): Long? {
        var max = -1L
        var maxId = -1L
        for (chunk in context.chunks.values) {
            val rem = chunk.length.get() - chunk.downloaded.get()
            val time = chunk.lastTakeOver.get()
            var valid = false
            if (time <= 0) {
                valid = true
            } else if (System.currentTimeMillis() - time > 5000) {
                valid = true
            }
            if (chunk.status.get() != ChunkStatus.Finished && rem > max && valid) {
                max = rem
                maxId = chunk.id
            }
        }
        if (max > 256 * 1024 && maxId != -1L) {
            Logger.info("XDM", "Max chunk found: $maxId with length $max")
            return maxId
        }
        return null
    }

    private fun splitChuck(chunks: MutableMap<Long, Chunk>) {
        // A chunk connecting or finishing after Pause must not add chunks to the state Pause just saved.
        if (context.stopFlag.get()) return
        // Once the failure is reported nothing may start again behind the user's back. Without range
        // support every restart or split is a range request the server refuses; the first connection's
        // stream takes over the rest instead.
        if (failureReported.get() || context.noRangeSupport) return
        // No Content-Length on the first response: the size is unknown and the one stream runs to EOF.
        if (context.totalSize == null) return
        val activeChunks = getActiveCount(chunks)
        if (activeChunks >= maxChunk) return
        var rc = maxChunk - activeChunks
        if (rc < 1) return
        rc -= retryFailedChunk(rc)
        if (rc < 1) return
        findMaxChunk()?.let {
            val max = it
            chunks[max]?.let { c ->
                if (c.length.get() < 256 * 1024) {
                    Logger.info("XDM", "Chunk ${c.id} is to small to split")
                    return
                }
                val rem = c.length.get() - c.downloaded.get()
                if (rem < 256 * 1024) {
                    Logger.info("XDM", "Chunk ${c.id} is to small to split")
                    return
                }
                val offset = c.offset + c.length.get() - rem / 2
                val len = rem / 2
                Logger.info("XDM", "Splitting chunk ${c.id} into $offset and $len")
                c.length.addAndGet(-len)
                val id = CoreUtils.uniqueId()
                val chunk = Chunk(
                    id = id,
                    offset = offset,
                    length = AtomicLong(len),
                    downloaded = AtomicLong(0),
                    status = AtomicReference(ChunkStatus.Ready),
                    fileHandle = AtomicReference(null),
                    lastTakeOver = AtomicLong(0)
                )
                chunks[id] = chunk
                saveState()
                startChunk(id)
            }
        }
    }

    override fun takeOverChunk(chunkId: Long, maxByteRange: Long): Boolean {
        var nextChunkId: Long = -1L
        context.write {
            if (context.stopFlag.get()) {
                Logger.info("XDM", "Download stopped")
                return false
            }
            val chunk = context.chunks[chunkId] ?: return false
            val position = chunk.offset + chunk.length.get()

            for (nextChunk in context.chunks.values) {
                if (nextChunk.downloaded.get() == 0L
                    && nextChunk.offset == position
                    && nextChunk.length.get() + nextChunk.offset <= maxByteRange
                ) {
                    nextChunkId = nextChunk.id
                    Logger.info("XDM", "Chunk found for takeover: $nextChunkId")
                    break
                }
            }
            var len = -1L
            if (nextChunkId != -1L) {
                Logger.info("XDM", "Takeover chunk $nextChunkId with $chunk")
                context.chunks[nextChunkId]?.let { nc ->
                    nc.status.set(ChunkStatus.Cancelled)
                    closeFileHandle(nc)
                    len = nc.length.get()
                    context.chunks.remove(nextChunkId)
                    Logger.info("XDM", "Chunk $nextChunkId removed with len $len")
                }
                context.chunks[chunkId]?.let { c ->
                    c.lastTakeOver.set(System.currentTimeMillis())
                    c.length.addAndGet(len)
                    splitChuck(context.chunks)
                    saveState()
                    return true
                }
            }
            Logger.info("XDM", "No Chunk found for takeover")
        }
        return false
    }

    /**
     * True once the download cannot make progress: no chunk is still downloading (or about to) and at
     * least one failed. Connecting and finishing chunks restart failed ones through [splitChuck], so
     * once nothing is left running, a mix of Finished and Failed is as final as all Failed.
     */
    private fun isAllError(): Boolean {
        if (context.stopFlag.get()) return false
        if (context.diskError.get()) return true
        context.read {
            val statuses = context.chunks.values.map { it.status.get() }
            if (statuses.any { it != ChunkStatus.Finished && it != ChunkStatus.Failed }) return false
            return statuses.any { it == ChunkStatus.Failed }
        }
        return false
    }

    private fun closeFileHandle(chunk: Chunk) {
        try {
            chunk.fileHandle.get()?.close()
        } catch (e: IOException) {/* do nothing*/
        }
    }

    private fun getActiveCount(chunks: Map<Long, Chunk>): Int =
        chunks.values.count { it.status.get() != ChunkStatus.Finished && it.status.get() != ChunkStatus.Failed }

    private fun startChunk(id: Long) {
        try {
            Thread {
                val retriever = HttpChunkRetriever(id, context, this, config)
                retriever.retrieveChunk()
            }.start()
        } catch (e: Throwable) {
            // E.g. no memory for another thread. A chunk left Ready or Downloading with no thread would hold
            // the download forever; as Failed it is retried later, or the download reports the failure.
            Logger.error("XDM", "Unable to start a thread for chunk $id", e)
            context.write { context.chunks[id]?.status?.set(ChunkStatus.Failed) }
            onChunkFailed(id, DownloadError.InternalError)
        }
    }

    override fun updateBytesDownloaded(id: Long, downloaded: Long) {
        var update = false
        context.read {
            update = progressTracker.update(
                downloadedBytes = downloaded,
                totalSize = context.totalSize,
                chunks = context.chunks,
            )
            prgInfo.apply {
                this.progress = progressTracker.progress
                this.downloaded = progressTracker.totalDownloadedBytes
                this.eta = progressTracker.eta
                this.speed = progressTracker.downloadSpeed
                this.segments = progressTracker.segmentData
            }
        }
        context.downloaded.addAndGet(downloaded)
        if (update) {
            context.downloadHost.onDownloadProgress(prgInfo)
        }
        // Splits otherwise only happen when a chunk connects, finishes or takes over: about once a second,
        // use a free slot, e.g. once a chunk's takeover cooldown is over or after a chunk failed.
        val now = System.currentTimeMillis()
        val lastCheck = lastSplitCheck.get()
        if (now - lastCheck >= SPLIT_CHECK_MS && lastSplitCheck.compareAndSet(lastCheck, now)
            && getActiveCount(context.chunks) < maxChunk
        ) {
            context.write { splitChuck(context.chunks) }
        }
        // Only synced progress is saved, so how often the data is forced to disk is how far a resume
        // after a crash can fall behind.
        val unsynced = unsyncedBytes.addAndGet(downloaded)
        if (!context.stopFlag.get()
            && (unsynced >= SYNC_BYTES || now - lastSync.get() >= SYNC_INTERVAL_MS)
            && syncing.compareAndSet(false, true)
        ) {
            try {
                syncData()
            } finally {
                syncing.set(false)
            }
        }
        if (!context.stopFlag.get()) {
            throttle.throttleIfNeeded(context.downloaded.get())
        }
    }

    private fun finishDownload() {
        context.completed.set(true)
        Logger.info("XDM", "Resume: All chunks downloaded")
        try {
            val tmpFile = File(context.tempFolder, context.tempFileName)
            val totalFileSize = context.totalSize ?: tmpFile.length()
            val res = context.downloadHost.commitOutputFile(context.id, tmpFile.absolutePath, DownloadType.Http)
            Logger.info("XDM", "Move file success: - $res")
            when (res) {
                is CommitResult.Failed -> {
                    if (context.stopFlag.get() || res.error == DownloadError.Cancelled) return
                    context.diskError.set(true)
                    saveState()
                    context.downloadHost.onDownloadFailed(context.id, res.error)
                }

                is CommitResult.Success -> {
                    saveState()
                    context.downloadHost.onDownloadSuccess(
                        DownloadStatusInfo.FinalInfo(
                            context.id,
                            totalFileSize,
                            res.fileName,
                            res.outputDir
                        )
                    )
                }
            }
        } finally {
            context.httpClient.close()
        }
    }

    @Synchronized
    private fun saveState() {
        xdm.core.downloaders.web.saveState(context, configDir)
    }

    /**
     * Forces the temp file's data to disk, then records the progress it covers as synced and saves the
     * state. The flush runs outside the lock, so the other chunks keep downloading meanwhile.
     */
    private fun syncData() {
        unsyncedBytes.set(0)
        lastSync.set(System.currentTimeMillis())
        val progress = HashMap<Long, Long>()
        // A counter only goes up after its write returned, so every byte counted here is already written.
        context.read { context.chunks.values.forEach { progress[it.id] = it.downloaded.get() } }
        if (!forceTempFile()) return
        context.write {
            // Pause, completion and a reported failure save a fully synced state of their own.
            if (context.stopFlag.get() || context.completed.get() || failureReported.get()) return
            progress.forEach { (id, n) -> context.chunks[id]?.synced?.updateAndGet { maxOf(it, n) } }
            saveState()
        }
    }

    /**
     * For pause, failure and completion, when no chunk is writing: forces the data to disk and marks all
     * of it synced, so the state saved next records exactly what was downloaded.
     */
    private fun syncAll() {
        if (forceTempFile()) context.chunks.values.forEach { it.synced.set(it.downloaded.get()) }
    }

    /** Forces the temp file's data to disk: false (logged) if that failed, true if there is no file yet. */
    private fun forceTempFile(): Boolean {
        val file = File(context.tempFolder, context.tempFileName)
        if (!file.exists()) return true
        return try {
            MoveOps.force(file.toPath())
            true
        } catch (e: IOException) {
            Logger.error("XDM", "Unable to flush ${file.absolutePath}", e)
            false
        }
    }

    private companion object {
        /** Force the downloaded data to disk after this much has been written... */
        const val SYNC_BYTES = 64L * 1024 * 1024

        /** ...or this long after the last time, whichever comes first. */
        const val SYNC_INTERVAL_MS = 30_000L

        /** How often a running download looks for a free slot to split into. */
        const val SPLIT_CHECK_MS = 1000L
    }
}