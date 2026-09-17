package xdm.core.downloaders.web.streaming.downloader

import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.Range
import xdm.core.network.http.isTlsVerificationError
import xdm.core.util.Logger
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
                // byteRange is (offset, length) per the HLS/DASH parsers; an HTTP Range needs the
                // absolute inclusive end (offset + length - 1), not the raw length. Passing the
                // length straight through requested "bytes=offset-length", corrupting every
                // single-file byte-range segment. A null byteRange means "whole resource".
                val (offset, absEnd) = piece.byteRange?.let { (off, len) -> off to (off + len - 1) }
                    ?: (0L to null)
                val realRange = Range(offset + piece.downloaded.get(), absEnd)
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
                            retryAfter = getRetryDelay(response.getHeader("retry-after"), 5)
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
                    if (isTlsVerificationError(ex)) {
                        piece.status.set(ChunkStatus.Failed)
                        piece.error.set(DownloadError.TlsError)
                        return
                    }
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