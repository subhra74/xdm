package xdm.core.downloaders.web.http

import xdm.core.downloaders.web.DownloadError
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.Range
import xdm.core.util.Logger
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

data class ChunkConfirmedData(
    val resumeSupport: Boolean,
    val contentLength: Long?,
    val finalUrl: String?,
    val contentDisposition: String?,
    val contentType: String?,
    val lastModified: LocalDateTime?
)

enum class ChunkStatus {
    NotStarted, Finished, Downloading, Failed, Cancelled, Ready
}

sealed interface ConnectResult {
    data class Connected(val response: HttpResponse) : ConnectResult
    data object Retry : ConnectResult
    data object InvalidResponse : ConnectResult
    data object NoResume : ConnectResult
}

enum class CopyResult {
    Done, Retry, Cancel, DiskError, Eof
}

data class Chunk(
    var id: Long,
    var offset: AtomicLong,
    var length: AtomicLong,
    var downloaded: AtomicLong,
    var status: AtomicReference<ChunkStatus>,
    var fileHandle: AtomicReference<RandomAccessFile?>,
    val lastTakeOver: AtomicLong,
)

class ChunkRetriever(
    private val id: Long,
    private val context: HttpTaskContext,
    private val controller: ChunkController
) {
    fun retrieveChunk() {
        val MAX_RETRY = 10
        var retryCount = 0
        var maxByteRange: Long? = null

        while (!isCancelled()) {
            run {
                val data = getRequestData() ?: return
                val (startOffset, endOffset) = makeRange(data)
                when (val connectResult = connect(startOffset, endOffset)) {
                    is ConnectResult.Connected -> {
                        if (isCancelled()) return
                        val res = connectResult.response
                        res.use {
                            val len = res.contentLength ?: -1L
                            if (len > 0) {
                                maxByteRange = data.offset + len
                            }
                            Logger.info("XDM", "Chunk connected $id")
                            controller.onChunkConnected(
                                id,
                                if (!context.init.get()) ChunkConfirmedData(
                                    resumeSupport = res.statusCode == 206,
                                    contentLength = res.contentLength,
                                    finalUrl = res.finalUrl,
                                    contentDisposition = res.contentDisposition,
                                    contentType = res.contentType,
                                    lastModified = res.lastModified,
                                ) else null
                            )
                            if (isCancelled()) return
                            val copyResult =
                                if (res.contentLength != null) {
                                    copyDataWithLength(res, maxByteRange!!)
                                } else {
                                    copyDataWithoutLength(
                                        res
                                    )
                                }
                            when (copyResult) {
                                CopyResult.Done -> {
                                    Logger.info("XDM", "Chunk $id copy_data done")
                                    context.write {
                                        context.chunks[id]?.apply {
                                            status.set(ChunkStatus.Finished)
                                            controller.onChunkFinished(id)
                                        }
                                    }
                                    return
                                }

                                CopyResult.Retry -> {
                                    if (res.contentLength == null) {
                                        chunkFailed(DownloadError.NetworkError)
                                    }
                                    Logger.info("XDM", "Retrying download for chunk $id from copy data")
                                    return@run
                                }

                                CopyResult.Cancel -> {
                                    Logger.info("XDM", "Chunk $id cancelled during copy_data")
                                    return
                                }

                                CopyResult.DiskError -> {
                                    Logger.info("XDM", "Chunk $id failed during copy_data - disk error")
                                    chunkFailed(DownloadError.DiskSpaceError)
                                    return
                                }

                                CopyResult.Eof -> {
                                    Logger.info("XDM", "Chunk $id failed during copy_data - eof error")
                                    chunkFailed(DownloadError.InvalidResponse)
                                    return
                                }
                            }
                        }
                    }

                    ConnectResult.InvalidResponse -> {
                        Logger.info("XDM", "Chunk $id failed during connect")
                        chunkFailed(DownloadError.InvalidResponse)
                        return
                    }

                    ConnectResult.NoResume -> {
                        Logger.info("XDM", "Chunk $id failed during connect due to no resume")
                        chunkFailed(DownloadError.ResumeNotSupported)
                        return
                    }

                    ConnectResult.Retry -> {
                        if (isCancelled()) {
                            Logger.info("XDM", "Chunk $id cancelled during retry")
                            return
                        }
                        retryCount += 1
                        if (retryCount > MAX_RETRY) {
                            Logger.info("XDM", "Max retries reached for chunk $id")
                            chunkFailed(DownloadError.NetworkError)
                            return
                        }
                        Logger.info("XDM", "Retry after sleep $id")
                        Thread.sleep(5000)
                        return@run
                    }
                }
            }
        }
    }

    private fun copyDataWithoutLength(response: HttpResponse): CopyResult {
        if (isCancelled()) return CopyResult.Cancel
        val buf = ByteArray(256 * 1024)

        var fileHandle: RandomAccessFile? = null

        try {
            fileHandle = openFileHandle() ?: run { return CopyResult.Cancel }
        } catch (ioError: IOException) {
            return CopyResult.DiskError
        } finally {
            closeFileHandle(fileHandle)
        }

        try {
            while (true) {
                if (!isCancelled()) return CopyResult.Cancel
                val read: Int
                try {
                    read = response.inputStream.read(buf, 0, buf.size)
                } catch (ioError: IOException) {
                    Logger.info("XDM", "Error reading data for chunk  $id")
                    return CopyResult.Retry
                }

                if (isCancelled()) {
                    return CopyResult.Cancel
                }
                if (read == -1) {
                    Logger.info("XDM", "EOF reached $id")
                    return CopyResult.Done
                }

                try {
                    fileHandle?.write(buf, 0, read)
                } catch (ioError: IOException) {
                    Logger.error("XDM", "Error writing to file for chunk $id", ioError)
                    return CopyResult.DiskError
                }

                context.write {
                    val d = context.chunks[id]?.downloaded?.get() ?: return CopyResult.Cancel
                    context.chunks[id]?.downloaded?.set(d + read)
                }

                controller.updateBytesDownloaded(id, read.toLong())
            }
        } finally {
            closeFileHandle(fileHandle)
        }
    }

    private fun copyDataWithLength(response: HttpResponse, maxByteRange: Long): CopyResult {
        Logger.info("XDM", "copyDataWithLength")
        if (isCancelled()) {
            Logger.info("Chunk cancelled: $id")
            return CopyResult.Cancel
        }
        val buf = ByteArray(256 * 1024)
        var rem: Long = 0

        var fileHandle: RandomAccessFile? = null

        try {
            fileHandle = openFileHandle() ?: run {
                Logger.info("Chunk cancelled fs: $id")
                return CopyResult.Cancel
            }
        } catch (ioError: IOException) {
            return CopyResult.DiskError
        }

        try {
            while (true) {
                if (isCancelled()) {
                    Logger.info("Chunk cancelled: $id")
                    return CopyResult.Cancel
                }
                context.read {
                    val chunk = context.chunks[id] ?: return CopyResult.Cancel
                    rem = chunk.length.get() - chunk.downloaded.get()
                }
                if (isCancelled()) {
                    Logger.info("Chunk cancelled: $id")
                    return CopyResult.Cancel
                }
                if (rem <= 0) {
                    if (!controller.takeOverChunk(id, maxByteRange)) {
                        Logger.info("XDM", "No takeover, downloading complete for chunk $id")
                        return CopyResult.Done
                    } else {
                        Logger.info("XDM", "Takeover done, length increased for chunk $id")
                        continue
                    }
                }
                val x = min(rem, buf.size.toLong()).toInt()
                if (x == 0) {
                    Logger.info("XDM", "Downloading complete for chunk $id")
                    return CopyResult.Done
                }
                val read: Int
                try {
                    read = response.inputStream.read(buf, 0, x)
                } catch (ioError: IOException) {
                    Logger.info("XDM", "Error reading data for chunk  $id")
                    return CopyResult.Retry
                }

                if (isCancelled()) {
                    Logger.info("Chunk cancelled: $id")
                    return CopyResult.Cancel
                }

                if (read == -1) {
                    Logger.info("XDM", "Unexpected EOF   $id")
                    return CopyResult.Eof
                }

                try {
                    fileHandle!!.write(buf, 0, read)
                } catch (ioError: IOException) {
                    Logger.error("XDM", "Error writing to file for chunk $id", ioError)
                    return CopyResult.DiskError
                }

                context.write {
                    val d = context.chunks[id]?.downloaded?.get() ?: return CopyResult.Cancel
                    context.chunks[id]?.downloaded?.set(d + read)
                }

                controller.updateBytesDownloaded(id, read.toLong())
            }
        } finally {
            closeFileHandle(fileHandle)
        }
    }

    private fun connect(startRange: Long, endRange: Long?): ConnectResult {
        Logger.info("XDM", "Chunk: $id - Connecting to url ${context.url}")
        val range = if (endRange != null) Range(startRange, endRange - 1) else Range(startRange)
        val response = context.httpClient.getResponse(context.url, context.headers, context.cookie, range)
        response.onSuccess { r ->
            if (isFatalStatus(r.statusCode, startRange, !context.init.get())) {
                return ConnectResult.InvalidResponse
            }
            if (r.statusCode == 200 || r.statusCode == 206) {
                r.contentLength?.let { contentLength ->
                    endRange?.let { end ->
                        if (contentLength != end - startRange) {
                            Logger.info(
                                "XDM",
                                "Chunk: $id, Content length mismatch - expected ${end - startRange} got $contentLength"
                            )
                            return ConnectResult.NoResume
                        }
                    }
                }
                return ConnectResult.Connected(r)
            } else {
                return ConnectResult.Retry
            }
        }
        response.onFailure { t -> Logger.error("XDM", "Connect error", t) }
        return ConnectResult.Retry
    }

    private fun isFatalStatus(code: Int, startRange: Long, firstRequest: Boolean): Boolean {
        if (code != 200
            && code != 206
            && code != 416
            && code != 413
            && code != 408
            && code != 502
            && code != 503
            && code != 504
        ) {
            Logger.error("XDM", "Invalid status code: $code")
            return true
        }
        if (!firstRequest && startRange > 0 && code == 200) {
            Logger.error("XDM", "Invalid status code for range request: $code")
            return true;
        }
        return false
    }

    private fun makeRange(data: RequestData): Pair<Long, Long?> {
        if (data.length <= 0) {
            Logger.info("XDM", "No length for chunk, using offset only")
            return Pair(data.offset, null)
        }
        val startOffset = data.offset + data.downloaded
        val endOffset = data.offset + data.length - data.downloaded
        return Pair(startOffset, endOffset)
    }

    private fun getRequestData(): RequestData? {
        context.read {
            context.chunks[id]?.let {
                return RequestData(
                    context.url,
                    it.offset.get(),
                    it.downloaded.get(),
                    it.length.get(),
                    !context.init.get()
                )
            }
        }
        return null
    }

    private fun isCancelled(): Boolean {
        if (context.stopFlag.get()) {
            return true
        }
        context.read {
            context.chunks[id]?.run { return status.get() == ChunkStatus.Cancelled }
        }
        return true
    }

    private fun openFileHandle(): RandomAccessFile? {
        context.write {
            val chunk = context.chunks[id] ?: return null
            var fs: RandomAccessFile? = null
            try {
                fs = RandomAccessFile(File(controller.tempDir, "${chunk.id}.part"), "rw")
                fs.seek(chunk.downloaded.get())
                chunk.fileHandle.set(fs)
                return fs
            } catch (ex: Exception) {
                chunk.fileHandle.set(null)
                closeFileHandle(fs)
                throw ex
            }
        }
        return null
    }

    private fun closeFileHandle(fileHandle: RandomAccessFile?) {
        try {
            fileHandle?.close()
        } catch (e: Exception) {//No op
        }
        context.write {
            context.chunks[id]?.let {
                it.fileHandle.set(null)
            }
        }
    }

    private fun chunkFailed(downloadError: DownloadError) {
        context.write {
            context.chunks[id]?.apply {
                status.set(ChunkStatus.Failed)
            }
        }
        controller.onChunkFailed(id, downloadError)
    }
}

data class RequestData(
    val url: String,
    val offset: Long,
    val downloaded: Long,
    val length: Long,
    val firstRequest: Boolean
)