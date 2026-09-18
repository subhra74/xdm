package xdm.core.downloaders.web.http

import xdm.core.CoreConfig
import xdm.core.downloaders.DownloadError
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.Range
import xdm.core.network.http.isTlsVerificationError
import xdm.core.util.Logger
import xdm.core.util.getRetryDelay
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min


class HttpChunkRetriever(
    private val id: Long,
    private val context: HttpTaskContext,
    private val controller: ChunkController,
    private val config: CoreConfig
) {
    val buf: ByteArray

    init {
        Logger.info("$id Allocating buffer")
        buf = ByteArray(256 * 1024)
    }

    fun retrieveChunk() {
        try {
            retrieveChunkInternal()
        } catch (e: Exception) {
            // Never let an unexpected exception kill the thread with the chunk still Downloading,
            // or the download hangs with no error.
            if (isCancelled()) return
            Logger.error("XDM", "Chunk $id failed with unexpected error", e)
            chunkFailed(DownloadError.InternalError)
        }
    }

    private fun retrieveChunkInternal() {
        var retryCount = 0
        var maxByteRange: Long? = null
        var retryAfter = 5L

        while (!isCancelled()) {
            // Check if already completed in case of resume
            if (isAlreadyDone()) return

            val data = getRequestData() ?: return
            Logger.info(data)
            val (startOffset, endOffset) = makeRange(data)
            Logger.info("start: $startOffset, end: $endOffset")
            val shouldRetry = when (val connectResult = connect(startOffset, endOffset)) {
                is ConnectResult.Connected -> {
                    if (isCancelled()) return
                    connectResult.response.use { res ->
                        val len = res.contentLength ?: -1L
                        if (len > 0) maxByteRange = data.offset + len
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
                                isRedirect = res.isRedirected
                            ) else null
                        )
                        if (isCancelled()) return
                        if (!copyDataOrRetry(res, maxByteRange)) {
                            false
                        } else if ((context.chunks[id]?.downloaded?.get() ?: 0L) > data.downloaded) {
                            // The connection delivered data before failing (e.g. a read timeout on a
                            // slow link): keep going without using up retries.
                            retryCount = 0
                            true
                        } else {
                            // Nothing received on this attempt, e.g. a server that stalls: count it.
                            retryCount += 1
                            onRetry(retryCount)
                        }
                    }
                }

                ConnectResult.InvalidResponse -> {
                    Logger.info("XDM", "Chunk $id failed during connect")
                    chunkFailed(DownloadError.InvalidResponse)
                    false
                }

                ConnectResult.TlsError -> {
                    Logger.info("XDM", "Chunk $id failed during connect - TLS certificate verification failed")
                    chunkFailed(DownloadError.TlsError)
                    false
                }

                ConnectResult.NoResume -> {
                    Logger.info("XDM", "Chunk $id failed during connect due to no resume")
                    chunkFailed(DownloadError.ResumeNotSupported)
                    false
                }

                is ConnectResult.Retry -> {
                    if (isCancelled()) {
                        Logger.info("XDM", "Chunk $id cancelled during retry")
                        false
                    } else {
                        retryCount += 1
                        retryAfter = connectResult.delay
                        onRetry(retryCount)
                    }
                }
            }
            if (isCancelled() || !shouldRetry) return
            sleepUnlessCancelled(retryAfter * 1000)
        }
    }

    /** Sleeps in short steps so Pause is noticed without waiting out the whole retry delay. */
    private fun sleepUnlessCancelled(millis: Long) {
        val deadline = System.currentTimeMillis() + millis
        while (!isCancelled()) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return
            Thread.sleep(min(left, RETRY_SLEEP_STEP_MS))
        }
    }

    private fun connect(startRange: Long, endRange: Long?): ConnectResult {
        Logger.info("XDM", "Chunk: $id - Connecting to url ${context.url}")
        val range = if (endRange != null) Range(startRange, endRange - 1) else Range(startRange)
        Logger.info(range)
        val response = context.httpClient.getResponse(context.url, context.headers, context.cookie, range)
        response.onSuccess { r ->
            print(r)
            if (isFatalStatus(r.statusCode, startRange, !context.init.get())) {
                r.close()
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
                            r.close()
                            return ConnectResult.NoResume
                        }
                    }
                }
                return ConnectResult.Connected(r)
            } else {
                r.close()
                var retryAfter = 5L
                if (r.statusCode == 429) {
                    retryAfter = min(getRetryDelay(r.getHeader("retry-after"), 5), MAX_RETRY_AFTER_SECS)
                    Logger.info("Rate limit hit: Will wait for $retryAfter sec")
                }
                return ConnectResult.Retry(retryAfter)
            }
        }
        response.onFailure { t ->
            Logger.error("XDM", "Connect error", t)
            if (isTlsVerificationError(t)) return ConnectResult.TlsError
        }
        return ConnectResult.Retry(5)
    }

    private fun copyDataOrRetry(res: HttpResponse, maxByteRange: Long?): Boolean {
        //Return true if retry is needed else false
        val copyResult =
            if (res.contentLength != null && res.contentLength!! > 0) {
                copyDataWithLength(res, maxByteRange!!)
            } else {
                copyDataWithoutLength(res)
            }
        return when (copyResult) {
            CopyResult.Done -> {
                Logger.info("XDM", "Chunk $id copy_data done")
                context.write {
                    context.chunks[id]?.apply {
                        status.set(ChunkStatus.Finished)
                        controller.onChunkFinished(id)
                    }
                }
                false
            }

            CopyResult.Retry -> {
                if (res.contentLength == null) {
                    // No point in retry if content length is absent as download is most likely resume is not supported
                    chunkFailed(DownloadError.ResumeNotSupported)
                    false
                } else {
                    Logger.info("XDM", "Retrying download for chunk $id from copy data")
                    true
                }
            }

            CopyResult.Cancel -> {
                Logger.info("XDM", "Chunk $id cancelled during copy_data")
                false
            }

            CopyResult.DiskError -> {
                Logger.info("XDM", "Chunk $id failed during copy_data - disk error")
                chunkFailed(DownloadError.DiskSpaceError)
                false
            }

            CopyResult.Eof -> {
                Logger.info("XDM", "Chunk $id failed during copy_data - eof error")
                chunkFailed(DownloadError.InvalidResponse)
                false
            }
        }
    }

    private fun copyDataWithLength(response: HttpResponse, maxByteRange: Long): CopyResult {
        Logger.info("XDM", "copyDataWithLength")
        if (isCancelled()) {
            Logger.info("Chunk cancelled: $id")
            return CopyResult.Cancel
        }
        var rem: Long = 0
        val fileHandle: RandomAccessFile?

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

    private fun onRetry(retryCount: Int): Boolean {
        if (retryCount > config.maxRetries) {
            Logger.info("XDM", "Max retries reached for chunk $id")
            chunkFailed(DownloadError.NetworkError)
            return false
        }
        Logger.info("XDM", "Retry after sleep $id")
        return true
    }

    private fun copyDataWithoutLength(response: HttpResponse): CopyResult {
        if (isCancelled()) return CopyResult.Cancel
        val buf = ByteArray(256 * 1024)

        var fileHandle: RandomAccessFile

        try {
            fileHandle = openFileHandle() ?: run { return CopyResult.Cancel }
        } catch (ioError: IOException) {
            return CopyResult.DiskError
        }

        try {
            while (true) {
                if (isCancelled()) {
                    Logger.info("XDM", "Download cancelled $id")
                    return CopyResult.Cancel
                }
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
                    fileHandle.write(buf, 0, read)
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

    private fun isFatalStatus(code: Int, startRange: Long, firstRequest: Boolean): Boolean {
        if (code != 200
            && code != 206
            && code != 416
            && code != 429
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
            return false //Allow
        }
        return false
    }

    private fun makeRange(data: RequestData): Pair<Long, Long?> {
        if (data.length <= 0) {
            Logger.info("XDM", "No length for chunk, using offset only")
            return Pair(data.offset, null)
        }
        val startOffset = data.offset + data.downloaded
        val endOffset = data.offset + data.length // - data.downloaded
        return Pair(startOffset, endOffset)
    }

    private fun getRequestData(): RequestData? {
        context.read {
            context.chunks[id]?.let {
                return RequestData(
                    context.url,
                    it.offset,
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
                fs = RandomAccessFile(File(context.tempFolder, context.tempFileName), "rw")
                context.totalSize?.let { len ->
                    if (!context.tempFileCreated.get()) {
                        fs.setLength(len)
                        Logger.info("XDM", "Temp file created with size $len")
                    } else {
                        Logger.info("XDM", "Temp file created already")
                    }
                }
                context.tempFileCreated.set(true)
                fs.seek(chunk.offset + chunk.downloaded.get())
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
            context.chunks[id]?.fileHandle?.set(null)
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

    private fun isAlreadyDone(): Boolean {
        // Write lock, not read: onChunkFinished takes the write lock (reentrant for the holder, but
        // a read lock cannot be upgraded). Marking Finished and notifying under the same lock
        // mirrors the CopyResult.Done path, so only one thread can see every chunk finished.
        context.write {
            val chunk = context.chunks[id] ?: return true
            if (chunk.status.get() == ChunkStatus.Finished) return true
            val len = chunk.length.get()
            val downloaded = chunk.downloaded.get()
            if (context.init.get() && len > 0 && len - downloaded <= 0) {
                chunk.status.set(ChunkStatus.Finished)
                controller.onChunkFinished(id)
                return true
            }
        }
        return false
    }

    private companion object {
        const val MAX_RETRY_AFTER_SECS = 60L
        const val RETRY_SLEEP_STEP_MS = 200L
    }
}
