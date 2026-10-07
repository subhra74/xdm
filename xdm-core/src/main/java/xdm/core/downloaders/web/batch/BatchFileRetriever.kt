package xdm.core.downloaders.web.batch

import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.Range
import xdm.core.network.http.isTlsVerificationError
import xdm.core.util.Logger
import xdm.core.util.getRetryDelay
import xdm.core.util.isLinkExpiredStatus
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/** What a [BatchFileRetriever] reports back to its task. */
interface BatchFileListener {
    /** First response for [file] (every attempt): lets the task pick the file's final name. */
    fun onConnected(file: BatchFile, response: HttpResponse)

    fun onBytes(file: BatchFile, count: Long)

    /**
     * [file] is complete in its part file: the task moves it to its final name. Returns null on
     * success, or the error that makes the file (and the whole batch) fail.
     */
    fun onComplete(file: BatchFile): DownloadError?

    /** Always called last, whatever the outcome. */
    fun onDone(file: BatchFile)
}

/**
 * Downloads one item of a batch into its part file in the batch folder, resuming with a Range
 * from what is on disk. Unlike a streaming segment, a server that ignores the Range restarts this one
 * file from zero instead of failing it: a single file is cheap to fetch again.
 *
 * Leaves [BatchFile.status] Finished or Failed (with [BatchFile.error]), or unchanged when stopped.
 */
class BatchFileRetriever(
    private val file: BatchFile,
    private val url: String,
    private val partFile: File,
    private val httpClient: PoolingHttpClient,
    private val headers: HeaderMap?,
    private val cookie: String?,
    private val stopFlag: AtomicBoolean,
    /** Attempts in a row that receive no data (stalls, timeouts, 429s) before the file fails. */
    private val maxRetries: Int,
    private val listener: BatchFileListener,
) : Runnable {

    override fun run() {
        try {
            if (file.status.get() == ChunkStatus.Finished) return
            file.status.set(ChunkStatus.Downloading)
            file.error.set(null)
            download()
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ex: Exception) {
            Logger.error("XDM", "Batch item ${file.index} failed", ex)
            if (!stopFlag.get()) fail(DownloadError.InternalError)
        } finally {
            // A stop leaves the file resumable: it is picked up again on the next start.
            if (stopFlag.get() && file.status.get() == ChunkStatus.Downloading) {
                file.status.set(ChunkStatus.Ready)
            }
            listener.onDone(file)
        }
    }

    private fun fail(error: DownloadError) {
        file.error.set(error)
        file.status.set(ChunkStatus.Failed)
    }

    private fun download() {
        var attemptsWithoutData = 0
        while (!stopFlag.get()) {
            // Only bytes that are both recorded and on disk count: the state is saved every few
            // seconds, the file is written continuously, and either can be ahead after a crash.
            val onDisk = if (partFile.isFile) partFile.length() else 0L
            if (file.downloaded.get() != onDisk) file.downloaded.set(minOf(file.downloaded.get(), onDisk))
            val downloadedBefore = file.downloaded.get()
            var retryAfter = 3L
            try {
                val range = if (downloadedBefore > 0) Range(downloadedBefore, null) else null
                httpClient.getResponse(url, headers, cookie, range).getOrThrow().use { response ->
                    if (stopFlag.get()) return
                    val code = response.statusCode
                    if (isLinkExpiredStatus(code, response.getHeader("WWW-Authenticate"))) {
                        Logger.error("XDM", "Batch item ${file.index} refused: $code")
                        fail(if (downloadedBefore > 0) DownloadError.LinkExpired else DownloadError.InvalidResponse)
                        return
                    }
                    if (code == 429) {
                        retryAfter = getRetryDelay(response.getHeader("retry-after"), 5)
                        throw IOException("Rate limit hit")
                    }
                    if (code != 200 && code != 206) {
                        Logger.error("XDM", "Batch item ${file.index} failed - invalid response: $code")
                        fail(DownloadError.InvalidResponse)
                        return
                    }
                    // A 200 to a Range request is the whole file again: start it over.
                    val offset = if (code == 206 && range != null) downloadedBefore else 0L
                    file.downloaded.set(offset)
                    file.length.set(response.contentLength?.let { offset + it } ?: -1L)
                    listener.onConnected(file, response)
                    if (!copy(response, offset)) return
                }
                if (file.status.get() == ChunkStatus.Downloading) {
                    val error = listener.onComplete(file)
                    if (error != null) fail(error) else file.status.set(ChunkStatus.Finished)
                }
                return
            } catch (ex: IOException) {
                if (stopFlag.get()) return
                Logger.error("XDM", "Error downloading batch item ${file.index}: ${ex.message}")
                if (isTlsVerificationError(ex)) {
                    fail(DownloadError.TlsError)
                    return
                }
                if (file.downloaded.get() > downloadedBefore) {
                    attemptsWithoutData = 0
                } else if (++attemptsWithoutData > maxRetries) {
                    fail(DownloadError.NetworkError)
                    return
                }
                Thread.sleep(retryAfter * 1000)
            }
        }
    }

    /**
     * Writes the body to the part file from [offset]. Returns true when the whole body arrived, false
     * when stopped or the write failed (the file is then failed). A body that ends short of its
     * Content-Length throws, so the caller retries from where it stopped.
     */
    private fun copy(response: HttpResponse, offset: Long): Boolean {
        val buffer = BUFFER.get()
        RandomAccessFile(partFile, "rw").use { raf ->
            try {
                raf.setLength(offset)
                raf.seek(offset)
            } catch (io: IOException) {
                Logger.error("XDM", "Unable to open part file $partFile", io)
                fail(DownloadError.OutputWriteError)
                return false
            }
            response.inputStream.use { input ->
                while (!stopFlag.get()) {
                    val n = input.read(buffer)
                    if (n == -1) {
                        val length = file.length.get()
                        if (length >= 0 && file.downloaded.get() < length) {
                            throw IOException("Body ended at ${file.downloaded.get()} of $length")
                        }
                        if (length < 0) file.length.set(file.downloaded.get())
                        return true
                    }
                    try {
                        raf.write(buffer, 0, n)
                    } catch (io: IOException) {
                        Logger.error("XDM", "Unable to write to $partFile", io)
                        fail(DownloadError.DiskSpaceError)
                        return false
                    }
                    file.downloaded.addAndGet(n.toLong())
                    listener.onBytes(file, n.toLong())
                }
            }
        }
        return false
    }

    private companion object {
        /** One buffer per worker thread, reused for every file it downloads. */
        val BUFFER: ThreadLocal<ByteArray> = ThreadLocal.withInitial { ByteArray(256 * 1024) }
    }
}
