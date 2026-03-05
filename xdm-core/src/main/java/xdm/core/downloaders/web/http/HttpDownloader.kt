package xdm.core.downloaders.web.http

import xdm.core.downloaders.*
import xdm.core.downloaders.web.ProgressTracker
import xdm.core.downloaders.web.loadState
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.Logger
import xdm.core.util.CoreUtils
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

const val MAX_CHUNK = 8

interface ChunkController : DownloaderTask {
    fun onChunkConnected(id: Long, data: ChunkConfirmedData?)
    fun updateBytesDownloaded(id: Long, downloaded: Long)
    fun onChunkFailed(id: Long, error: DownloadError)
    fun onChunkFinished(id: Long)
    fun throttleIfNeeded(id: Long)
    fun takeOverChunk(chunkId: Long, maxByteRange: Long): Boolean
}

fun makeContext(task: HttpDownloadTaskInfo, host: DownloadHost): HttpTaskContext = HttpTaskContext(
    id = task.id,
    chunks = ConcurrentHashMap<Long, Chunk>(),
    init = AtomicBoolean(false),
    totalSize = null,
    downloaded = AtomicLong(0),
    url = task.url,
    contentType = null,
    headers = task.headers,
    cookie = task.cookie,
    httpClient = HttpClientImpl(100),
    stopFlag = AtomicBoolean(false),
    completed = AtomicBoolean(false),
    tempFileCreated = AtomicBoolean(false),
    tempFileName = "${CoreUtils.uniqueId()}.tmp",
    diskError = AtomicBoolean(false),
    downloadHost = host,
    tempFolder = task.defaultDownloadFolder
)

class HttpDownloaderTask : ChunkController {
    private val context: HttpTaskContext
    private val configDir: String
    private val prgInfo: DownloadStatusInfo.ProgressInfo

    constructor(task: HttpDownloadTaskInfo, host: DownloadHost, configDir: String) {
        this.context = makeContext(task, host)
        this.configDir = configDir
        this.prgInfo = DownloadStatusInfo.ProgressInfo(id = context.id)
    }

    constructor(
        id: Long,
        configDir: String,
        httpClient: PoolingHttpClient,
        host: DownloadHost
    ) {
        this.context = loadState(id = id, configDir = configDir, http = httpClient, host = host).getOrThrow()
        this.configDir = configDir
        this.prgInfo = DownloadStatusInfo.ProgressInfo(id = context.id)
    }

    private var lastUpdate: Long = 0
    private val progressTracker = ProgressTracker()
    private val time = System.currentTimeMillis()

    override fun start() {
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

    override fun stop() {
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
                saveState()
                context.downloadHost.onDownloadPaused(context.id, PauseEvent.PausedByUser)
            }
        }.start()
    }

    override fun onChunkFinished(id: Long) {
        if (!context.completed.get()) {
            context.write {
                if (context.chunks.values.any { it.status.get() != ChunkStatus.Finished }) return
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
                            if (context.stopFlag.get()) return
                            context.diskError.set(true)
                            onChunkFailed(id, DownloadError.DiskSpaceError)
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
                }
            }
        }
        return
    }

    override fun throttleIfNeeded(id: Long) {
        TODO("Not yet implemented")
    }

    override fun onChunkFailed(id: Long, error: DownloadError) {
        if (isAllError()) {
            Logger.error("XDM", "All chunks failed, stopping download - error: $error")
            context.downloadHost.onDownloadFailed(context.id, error)
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
        val activeChunks = getActiveCount(chunks)
        if (activeChunks >= MAX_CHUNK) return
        var rc = MAX_CHUNK - activeChunks
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
            val retriever = HttpChunkRetriever(id, context, this)
            retriever.retrieveChunk()
        }.start()
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
            }
        }
        context.downloaded.addAndGet(downloaded)
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
}