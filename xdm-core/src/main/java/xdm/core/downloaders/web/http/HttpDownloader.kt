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
    fun throttleIfNeeded(id: Long)
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
    ).onSuccess { return Pair(it.apply { this.httpClient = httpClient }, false) }
    Logger.info("Unable to load saved download state, starting new download: ${task.id}")
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

class HttpDownloaderTask : ChunkController {
    private val context: HttpTaskContext
    private val configDir: String
    private val prgInfo: DownloadStatusInfo.ProgressInfo
    private val throttle: SpeedLimiter
    private val stopRequested = AtomicBoolean(false)
    private val startRequested = AtomicBoolean(false)
    private val monitorStarted = AtomicBoolean(false)
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
        this.maxChunk = config.maxSegments
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

    private var lastUpdate: Long = 0
    private val progressTracker = ProgressTracker(singleFile = true)
    private val time = System.currentTimeMillis()

    // All bytes actually pulled from the network this session, including redundant race
    // connections. Drives the speed limiter so the aggregate rate is capped even while a race
    // is in flight (race bytes are excluded from `context.downloaded`, which is progress-only).
    private val netBytes = AtomicLong(0)

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
            startStallMonitor()
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
                startStallMonitor()
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
                    // Close the live connection too, otherwise a chunk blocked in a stalled
                    // socket read would linger until the socket times out.
                    closeResponse(it)
                }
                saveState()
                throttle.disable()
                context.downloadHost.onDownloadPaused(context.id, PauseEvent.PausedByUser)
            }
        }.start()
    }

    override fun onChunkFinished(id: Long) {
        if (context.completed.get()) return
        context.write {
            if (context.completed.get()) return
            val chunk = context.chunks[id]
            if (chunk == null) {
                // Already removed (e.g. it was the losing side of a race). Still re-check, in
                // case its partner's completion was waiting on this.
                maybeFinalize()
                return
            }
            chunk.status.set(ChunkStatus.Finished)
            // If this is a redundant race connection finishing first, it wins: promote it to a
            // normal chunk and drop the primary it was racing.
            if (chunk.isRace) promoteRaceWinner(chunk)
            maybeFinalize()
        }
    }

    override fun throttleIfNeeded(id: Long) {
        TODO("Not yet implemented")
    }

    override fun onChunkFailed(id: Long, error: DownloadError) {
        context.write {
            val c = context.chunks[id]
            if (c != null && c.isRace) {
                // A redundant race connection failed (e.g. server ignored the range request):
                // discard it and unlink the primary so the monitor can race again later. The
                // primary keeps downloading, so this is not a download failure - but re-check
                // completion in case the primary already finished and was only blocked by this
                // in-flight race.
                closeResponse(c)
                closeFileHandle(c)
                context.chunks.remove(id)
                context.chunks[c.raceOf]?.racedBy?.set(-1L)
                Logger.info("XDM", "Race connection $id failed ($error), discarded")
                maybeFinalize()
                return
            }
        }
        if (isAllError()) {
            Logger.error("XDM", "All chunks failed, stopping download - error: $error")
            val finalError =
                if (error == DownloadError.InvalidResponse && context.downloaded.get() > 0 && context.chunks.size > 2) {
                    DownloadError.SessionExpired
                } else {
                    error
                }
            context.downloadHost.onDownloadFailed(context.id, finalError)
            context.write {
                saveState()
            }
        }
    }

    override fun onChunkConnected(id: Long, data: ChunkConfirmedData?) {
        if (!context.init.get() && data != null) {
            context.write {
                context.totalSize = data.contentLength
                data.contentLength?.let {
                    context.chunks[id]?.length?.set(it)
                }
                data.finalUrl?.let { context.url = it }
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
        val chunks = context.chunks.values.filter { it.status.get() != ChunkStatus.Finished }.map { it.id }
        for (chunk in chunks) {
            context.chunks[chunk]?.let {
                it.status.set(ChunkStatus.Downloading)
                startChunk(chunk)
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
        // While a redundant race for the tail is in flight, don't spawn range-splits on top
        // of it - keep the pair (primary + race) isolated so completion stays unambiguous.
        if (chunks.values.any { it.isRace }) return
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

    private fun isAllError(): Boolean {
        if (context.stopFlag.get()) return false
        if (context.diskError.get()) return true
        context.read {
            if (context.chunks.isEmpty()) return false
            if (context.chunks.values.any { it.status.get() != ChunkStatus.Failed }) return false
            return true
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
        Thread {
            val retriever = HttpChunkRetriever(id, context, this, config)
            retriever.retrieveChunk()
        }.start()
    }

    override fun updateBytesDownloaded(id: Long, downloaded: Long) {
        var update = false
        // A race chunk downloads bytes that overlap its primary, so they must not be
        // counted a second time toward global progress. We still refresh the tracker (with a
        // zero delta) so the race segment's own bar advances; the shortfall is reconciled
        // when the race wins (see promoteRaceWinner).
        val isRace = context.chunks[id]?.isRace == true
        val countedBytes = if (isRace) 0L else downloaded
        context.read {
            update = progressTracker.update(
                downloadedBytes = countedBytes,
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
        context.downloaded.addAndGet(countedBytes)
        if (update) {
            context.downloadHost.onDownloadProgress(prgInfo)
        }
        val now = System.currentTimeMillis()
        if (now - lastUpdate > 5000) {
            context.read {
                saveState()
            }
            lastUpdate = now
        }
        // Throttle on the raw bytes read (including this race's overlap), so a speed limit is
        // enforced on the aggregate even when a race is running against a stalled primary.
        val net = netBytes.addAndGet(downloaded)
        if (!context.stopFlag.get()) {
            throttle.throttleIfNeeded(net)
        }
    }

    private fun finishDownload() {
        context.completed.set(true)
        Logger.info("XDM", "Resume: All chunks downloaded")
        val tmpFile = File(context.tempFolder, context.tempFileName)
        val totalFileSize = context.totalSize ?: tmpFile.length()
        val res = context.downloadHost.commitOutputFile(context.id, tmpFile.absolutePath, DownloadType.Http)
        Logger.info("XDM", "Move file success: - $res")
        when (res) {
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
                        totalFileSize,
                        res.fileName,
                        res.outputDir
                    )
                )
            }
        }
    }

    @Synchronized
    private fun saveState() {
        xdm.core.downloaders.web.saveState(context, configDir)
    }

    /**
     * Watches for the pathological case where the download is stuck on a single slow/stalled
     * connection - typically the last remaining segment lagging on a poor network while the
     * rest have already finished. When detected, a redundant connection is spawned over the
     * same remaining byte range; whichever finishes first wins (see [promoteRaceWinner]).
     */
    private fun startStallMonitor() {
        if (!monitorStarted.compareAndSet(false, true)) return
        Thread {
            var lastBytes = -1L
            var slowSince = 0L
            try {
                while (!context.stopFlag.get() && !context.completed.get()) {
                    Thread.sleep(RACE_MONITOR_INTERVAL_MS)
                    if (context.stopFlag.get() || context.completed.get()) break
                    context.write {
                        val candidate = raceCandidate()
                        if (candidate == null) {
                            lastBytes = -1L
                            slowSince = 0L
                            return@write
                        }
                        val now = System.currentTimeMillis()
                        val cur = candidate.downloaded.get()
                        if (lastBytes < 0) {
                            lastBytes = cur
                            slowSince = now
                            return@write
                        }
                        val speed = (cur - lastBytes) * 1000.0 / RACE_MONITOR_INTERVAL_MS
                        lastBytes = cur
                        if (speed >= RACE_SLOW_SPEED_BYTES) {
                            // Making healthy progress on its own - no need to race.
                            slowSince = now
                        } else if (now - slowSince >= RACE_TRIGGER_MS) {
                            spawnRaceChunk(candidate)
                            lastBytes = -1L
                            slowSince = 0L
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // exit
            } catch (e: Exception) {
                Logger.error("XDM", "Stall monitor error", e)
            }
        }.apply {
            isDaemon = true
            name = "stall-monitor-${context.id}"
        }.start()
    }

    /**
     * Returns the sole remaining chunk that is a candidate for a redundant race connection,
     * or null if racing does not apply (more than one chunk left, a race is already in
     * flight, unknown size / no range support, or too little data remaining to be worth it).
     * Must be called while holding the context write lock.
     */
    private fun raceCandidate(): Chunk? {
        if (!context.init.get() || context.totalSize == null) return null
        // Only one race in flight at a time.
        if (context.chunks.values.any { it.isRace }) return null
        val unfinished = context.chunks.values.filter { it.status.get() != ChunkStatus.Finished }
        if (unfinished.size != 1) return null
        val c = unfinished.first()
        // Never race a chunk that is itself a race connection (keep racing one level deep).
        if (c.isRace) return null
        // A live chunk is either Ready (freshly started - the engine doesn't always flip it to
        // Downloading) or Downloading; skip Failed/Cancelled tails.
        val st = c.status.get()
        if (st != ChunkStatus.Downloading && st != ChunkStatus.Ready) return null
        if (c.racedBy.get() >= 0) return null
        // Needs a known length (i.e. range/resume support) to safely fetch the same tail again.
        if (c.length.get() <= 0) return null
        if (c.length.get() - c.downloaded.get() <= MIN_RACE_REMAINING) return null
        return c
    }

    /**
     * Spawns a second connection over the primary chunk's remaining byte range. Must be
     * called while holding the context write lock.
     */
    private fun spawnRaceChunk(primary: Chunk) {
        if (context.stopFlag.get() || context.completed.get()) return
        val rem = primary.length.get() - primary.downloaded.get()
        if (rem <= 0) return
        val raceOffset = primary.offset + primary.downloaded.get()
        val id = CoreUtils.uniqueId()
        val race = Chunk(
            id = id,
            offset = raceOffset,
            length = AtomicLong(rem),
            downloaded = AtomicLong(0),
            status = AtomicReference(ChunkStatus.Downloading),
            fileHandle = AtomicReference(null),
            lastTakeOver = AtomicLong(0),
            isRace = true,
            raceOf = primary.id,
        )
        primary.racedBy.set(id)
        context.chunks[id] = race
        Logger.info("XDM", "Spawned race connection $id for slow chunk ${primary.id}, remaining=$rem")
        saveState()
        startChunk(id)
    }

    /**
     * Handles a redundant race connection ([winner]) that finished before its primary: drops the
     * slower primary it was racing, reconciles byte accounting, and promotes the winner to a
     * normal chunk. Must be called while holding the context write lock.
     */
    private fun promoteRaceWinner(winner: Chunk) {
        val primaryId = winner.raceOf
        val loser = context.chunks[primaryId]
        if (loser != null) {
            loser.status.set(ChunkStatus.Cancelled)
            closeResponse(loser)
            closeFileHandle(loser)
            context.chunks.remove(primaryId)
            // The race's bytes were never added to the global total (they overlap the primary),
            // so add the primary's remaining shortfall now that the range is fully on disk.
            val shortfall = loser.length.get() - loser.downloaded.get()
            if (shortfall > 0) {
                context.downloaded.addAndGet(shortfall)
                progressTracker.totalDownloadedBytes += shortfall
            }
            Logger.info("XDM", "Race resolved: chunk ${winner.id} won, cancelled $primaryId")
        }
        // The race is now a normal chunk (so it counts toward completion in maybeFinalize).
        winner.isRace = false
        winner.raceOf = -1L
        winner.racedBy.set(-1L)
    }

    /**
     * Finalizes the download once every real (non-race) chunk is Finished. Idempotent and safe
     * to call from both the finished and failed paths. Redundant race connections never block
     * completion - a finished race has already replaced its primary via [promoteRaceWinner], and
     * any still-running/failed race is cancelled here. Must hold the context write lock.
     */
    private fun maybeFinalize() {
        if (context.completed.get() || context.stopFlag.get()) return
        if (context.chunks.isEmpty()) return
        val primaries = context.chunks.values.filter { !it.isRace }
        if (primaries.isEmpty()) return
        if (primaries.any { it.status.get() != ChunkStatus.Finished }) return
        cancelAllRaces()
        finalizeDownload()
    }

    /** Cancels and drops every in-flight/leftover race connection. Must hold the write lock. */
    private fun cancelAllRaces() {
        val raceIds = context.chunks.values.filter { it.isRace }.map { it.id }
        for (rid in raceIds) {
            context.chunks[rid]?.let {
                it.status.set(ChunkStatus.Cancelled)
                closeResponse(it)
                closeFileHandle(it)
            }
            context.chunks.remove(rid)
        }
        context.chunks.values.forEach { it.racedBy.set(-1L) }
    }

    /** Commits the temp file and reports success/failure. Must hold the context write lock. */
    private fun finalizeDownload() {
        context.completed.set(true)
        try {
            Logger.info("XDM", "All chunks downloaded")
            saveState()
            val tmpFile = File(context.tempFolder, context.tempFileName)
            val totalFileSize = context.totalSize ?: tmpFile.length()
            val res = context.downloadHost.commitOutputFile(context.id, tmpFile.absolutePath, DownloadType.Http)
            Logger.info("XDM", "Move file success: - $res")
            when (res) {
                is CommitResult.Failed -> {
                    if (context.stopFlag.get()) return
                    context.diskError.set(true)
                    context.downloadHost.onDownloadFailed(context.id, DownloadError.DiskSpaceError)
                }

                is CommitResult.Success -> {
                    Logger.info("Time taken ${(System.currentTimeMillis() - time) / 1000.0f} sec")
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

    private fun closeResponse(chunk: Chunk) {
        try {
            chunk.response.getAndSet(null)?.close()
        } catch (e: Exception) {/* no-op */
        }
    }

    companion object {
        // How often the stall monitor samples the last segment's progress.
        internal var RACE_MONITOR_INTERVAL_MS = 3000L
        // How long the last segment must stay slow before a redundant connection is spawned.
        internal var RACE_TRIGGER_MS = 9000L
        // Bytes/sec below which the tail is considered "slow" and eligible to be raced.
        internal var RACE_SLOW_SPEED_BYTES = 24 * 1024
        // Don't bother racing a tiny tail.
        internal var MIN_RACE_REMAINING = 8 * 1024L
        // (Values above are mutable only so tests can dial the timings down; production
        //  code never reassigns them.)
    }
}