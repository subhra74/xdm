package xdm.core.downloaders.web.streaming.downloader

import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.Range
import xdm.core.util.Logger
import xdm.core.util.getFileExtFromUrl
import xdm.core.util.getRetryDelay
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class StreamingChunkRetriever(
    private val piece: StreamingChunk,
    private val httpClient: PoolingHttpClient,
    private val stopFlag: AtomicBoolean,
    private val headers: HeaderMap?,
    private val cookie: String?,
    private val tempDir: String,
    private val progressCallback: (StreamingChunk, Long) -> Unit,
    private val fileNameCallback: (StreamingChunk) -> String,
    private val completionCallback: (StreamingChunk) -> Unit,
) : Runnable {
    private var fileHandle = AtomicReference<RandomAccessFile?>()

    override fun run() {
        val buffer = ByteArray(256 * 1024)
        try {
            while (!stopFlag.get()) {
                var retryAfter = 3L
                if (piece.status.get() == ChunkStatus.Finished) return
                val (start, end) = piece.byteRange ?: Pair(0L, null)
                val realRange = Range(start + piece.downloaded.get(), end)
                Logger.info("XDM", "${piece.sequence}: Connecting to: ${piece.url}, range: $realRange")
                try {
                    httpClient.getResponse(
                        piece.url,
                        headers,
                        cookie,
                        realRange
                    ).getOrThrow().use { response ->
                        if (stopFlag.get()) return

                        val code: Int = response.statusCode
                        if (realRange.start > 0 && code != 206) {
                            Logger.error(
                                "XDM",
                                "Chunk download failed - serve does not support resume: $code"
                            )
                            piece.status.set(ChunkStatus.Failed)
                            piece.error.set(DownloadError.ResumeNotSupported)
                            return
                        }
                        if (code == 429) {
                            val retryHeader = response.getHeader("retry-after")
                            val delay = getRetryDelay(retryHeader)
                            if (delay != null) {
                                retryAfter = delay
                            } else {
                                Logger.info("Unable to get retry delay: using default $retryAfter sec")
                            }
                            Logger.info("Rate limit hit: Will wait for $retryAfter sec")
                            throw IOException("Rate limit hit")
                        }
                        if (code != 206 && code != 200) {
                            Logger.error("XDM", "Chunk download failed - invalid response: $code")
                            piece.status.set(ChunkStatus.Failed)
                            piece.error.set(DownloadError.InvalidResponse)
                            return
                        }

                        response.contentLength?.let {
                            piece.length.set(it)
                        }
                        response.contentType?.let {
                            piece.contentType = it
                        }

                        Logger.info("Connected to: ${piece.url}")
                        val tempFile = File(fileNameCallback(piece))
                        Logger.info("XDM", "Opening tem file: ${tempFile.absolutePath}")
                        RandomAccessFile(tempFile, "rw").use { raf ->
                            this.piece.fileHandle.set(raf)
                            this.fileHandle.set(raf)
                            response.inputStream.use { res ->
                                raf.seek(piece.downloaded.get())
                                while (!stopFlag.get()) {
                                    val x: Int = res.read(buffer)
                                    if (x == -1) {
                                        piece.status.set(ChunkStatus.Finished)
                                        piece.error.set(null)
                                        Logger.info("XDM", "Piece download complete ${piece.url}")
                                        return
                                    }
                                    try {
                                        raf.write(buffer, 0, x)
                                    } catch (io: IOException) {
                                        Logger.error("XDM", "Unable to write to file", io)
                                        piece.status.set(ChunkStatus.Failed)
                                        piece.error.set(DownloadError.DiskSpaceError)
                                        return
                                    }
                                    piece.downloaded.addAndGet(x.toLong())
                                    progressCallback(this.piece, x.toLong())
                                }
                            }
                        }
                    }
                } catch (ex: IOException) {
                    Logger.error("XDM", "Error downloading chunk", ex)
                    if (stopFlag.get()) return
                    Thread.sleep(retryAfter * 1000)
                }
            }
        } catch (ex: InterruptedException) {
            Logger.error("XDM", "Thread interrupted", ex)
            Thread.currentThread().interrupt()
        } catch (ex: Exception) {
            Logger.error("XDM", "Error downloading manifest", ex)
        } finally {
            this.fileHandle.set(null)
            completionCallback(this.piece)
        }
    }

    fun stop() {
        try {
            this.fileHandle.get()?.close()
        } catch (ex: Exception) {
            // Do nothing
        }
        this.fileHandle.set(null)
    }
}