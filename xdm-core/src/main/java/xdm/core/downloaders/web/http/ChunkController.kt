package xdm.core.downloaders.web.http

import xdm.core.downloaders.web.DownloadError
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.CollectionUtils
import xdm.core.util.Logger
import xdm.core.util.CoreUtils
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min


interface ChunkController {
    fun onChunkConnected(id: Long, data: ChunkConfirmedData?)
    fun updateBytesDownloaded(id: Long, downloaded: Long)
    fun onChunkFailed(id: Long, error: DownloadError)
    fun onChunkFinished(id: Long)
    fun throttleIfNeeded(id: Long)
    fun takeOverChunk(chunkId: Long, maxByteRange: Long): Boolean
    val tempDir: String
}

class HttpChunkController(
    override val tempDir: String,
    private val context: HttpTaskContext,
    private val onComplete: () -> Unit,
    private var time: Long,
) : ChunkController {
    override fun onChunkFinished(id: Long) {
        if (!context.completed.get()) {
            if (assembleIfDone()) {
                val now = System.currentTimeMillis();
                Logger.info("Time taken ${(now - time) / 1000.0f} sec")
                context.completed.set(true)
                onComplete()
            }
        }
    }

    override fun throttleIfNeeded(id: Long) {
        TODO("Not yet implemented")
    }

    private fun assembleIfDone(): Boolean {
        context.write {
            if (context.chunks.values.any { it.status.get() != ChunkStatus.Finished }) return false
            Logger.info("XDM", "All chunks downloaded")
            return true
//            val outputFileName = context.finalFileName.get().invoke()
//            val outFolder = context.outputFolder.get().invoke()
//            val outPath = Paths.get(outFolder, outputFileName)
//            if (context.stopFlag.get()) return false
//            try {
//                val chunks = context.chunks.values.sortedBy { it.offset.get() }
//                FileChannel.open(
//                    outPath,
//                    CollectionUtils.setOf(
//                        StandardOpenOption.CREATE,
//                        StandardOpenOption.WRITE,
//                        StandardOpenOption.TRUNCATE_EXISTING
//                    )
//                ).use { outChannel ->
//                    for (chunk in chunks) {
//                        FileChannel.open(
//                            File(tempDir, "${chunk.id}.part").toPath(), StandardOpenOption.READ
//                        ).use { inChannel ->
//                            copyBytes(inChannel, outChannel, chunk.length.get())
//                        }
//                        if (context.stopFlag.get()) {
//                            return false
//                        }
//                    }
//                }
//                for (chunk in chunks) {
//                    val file = File(tempDir, "${chunk.id}.part")
//                    Logger.info("XDM", "Delete file: ${file.absolutePath} - ${file.delete()}")
//                }
//            } catch (error: Exception) {
//                Logger.error("XDM", "Assembling error", error)
//            }
        }
        return true
    }

    override fun onChunkFailed(id: Long, error: DownloadError) {
        if (isAllError()) {
            Logger.error("XDM", "All chunks failed, stopping download - error: $error")
            context.errorCallback(error)
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
                //context.fileName = CoreUtils.deriveFileName(context.url, data.contentType, data.contentDisposition)
                context.init.set(true)
                //TODO: notify watcher
            }
        }
        context.write {
            splitChuck(context.chunks)
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
        var max = 0L
        val activeChunks = getActiveCount(chunks)
        if (activeChunks >= 8) return
        var rem = 8 - activeChunks
        if (rem < 1) return
        rem -= retryFailedChunk(rem)
        if (rem < 1) return
        findMaxChunk()?.let {
            max = it
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
                val offset = c.offset.get() + c.length.get() - rem / 2
                val len = rem / 2
                Logger.info("XDM", "Splitting chunk ${c.id} into $offset and $len")
                c.length.addAndGet(-len)
                val id = CoreUtils.uniqueId()
                val chunk = Chunk(
                    id = id,
                    offset = AtomicLong(offset),
                    length = AtomicLong(len),
                    downloaded = AtomicLong(0),
                    status = AtomicReference(ChunkStatus.Ready),
                    fileHandle = AtomicReference(null),
                    lastTakeOver = AtomicLong(0)
                )
                chunks[id] = chunk
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
            val position = chunk.offset.get() + chunk.length.get()

            for (nextChunk in context.chunks.values) {
                if (nextChunk.downloaded.get() == 0L
                    && nextChunk.offset.get() == position
                    && nextChunk.length.get() + nextChunk.offset.get() <= maxByteRange
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
                context.chunks[chunkId]?.let { chunk ->
                    chunk.lastTakeOver.set(System.currentTimeMillis())
                    chunk.length.addAndGet(len)
                    splitChuck(context.chunks)
                    return true
                }
            }
            Logger.info("XDM", "No Chunk found for takeover")
        }
        return false
    }

    private fun isAllError(): Boolean {
        if (context.stopFlag.get()) return false
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

    private fun copyBytes(inChannel: FileChannel, outChannel: FileChannel, limit: Long) {
        val block1M: Long = 1048576
        var pos: Long = 0
        var rem = if (limit > 0) limit else inChannel.size()
        while (!context.stopFlag.get() && rem > 0) {
            val x = inChannel.transferTo(pos, min(block1M.toDouble(), rem.toDouble()).toLong(), outChannel)
            rem -= x
            pos += x
            //totalAssembled += x
//            val now = System.currentTimeMillis()
//            if (now - lastUpdated > 3000) {
//                updateStatus(0)
//                lastUpdated = now
//            }
        }
        if (!context.stopFlag.get() && limit > 0 && pos != limit) {
            Logger.error("Chunk file is shorter than chunk size, possible file corruption")
            throw IllegalArgumentException("Assemble EOF")
        }
    }

    fun start() {
        val id = CoreUtils.uniqueId()
        val chunk1 = Chunk(
            id = id,
            offset = AtomicLong(0),
            length = AtomicLong(0),
            downloaded = AtomicLong(0),
            status = AtomicReference(ChunkStatus.Ready),
            fileHandle = AtomicReference(null),
            lastTakeOver = AtomicLong(0)
        )
        context.chunks[id] = chunk1
        startChunk(id)
    }

    private fun startChunk(id: Long) {
        Thread({
            val retriever = ChunkRetriever(id, context, this)
            retriever.retrieveChunk()
        }).start()
    }

    override fun updateBytesDownloaded(id: Long, downloaded: Long) {

    }
}