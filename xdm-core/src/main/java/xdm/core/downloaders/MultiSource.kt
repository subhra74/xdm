package xdm.core.downloaders

import xdm.core.network.http.HeaderCollection
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.Range
import xdm.core.util.Logger
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean


data class MultiSourcePiece(
    val id: Long,
    val seq: Long,
    var downloaded: Long,
    var length: Long,
    val tag: String?,
    var contentType: String?,
    val url: String,
    val byteRange: Pair<Long, Long>?,
    var status: PieceStatus,
)

class MultiSourcePieceGrabber(
    private val piece: MultiSourcePiece,
    private val http: PoolingHttpClient,
    private val stopFlag: AtomicBoolean,
    private val tempDir: String,
    private val downloadCallback: (Long) -> Unit,
    private val completionCallback: (MultiSourcePiece) -> Unit,
    private val headers: HeaderCollection,
    private val cookie: String,
) : Runnable {
    private val logTag = "MultiSourcePieceGrabber"
    private var fileHandle: RandomAccessFile? = null
    private val maxRetry = 10
    private var downloadStarted = false

    override fun run() {
        val buffer = ByteArray(256 * 1024)
        try {
            var retryCount = 0
            while (!stopFlag.get()) {
                downloadStarted = false
                try {
                    if (!shouldRetryWithBackoff(retryCount)) {
                        piece.status = PieceStatus.ConnectError
                        return
                    }
                    val status = download(buffer)
                    if (!stopFlag.get() && status != null) {
                        piece.status = status
                    }
                    return
                } catch (ex: IOException) {
                    if (stopFlag.get()) return
                    Logger.error(logTag, "Error downloading chunk ${piece.id}", ex)
                    if (downloadStarted) {
                        retryCount = 0
                    } else {
                        retryCount++
                    }
                }
            }
        } finally {
            fileHandle = null
            completionCallback(piece)
        }
    }

    private fun download(buffer: ByteArray): PieceStatus? {
        val range = calculateRange()
        Logger.info(logTag, "Connecting to ${piece.url}")
        http.getResponse(piece.url, headers, cookie, range).use { res ->
            if (stopFlag.get()) return null
            downloadStarted = true
            val status = processResponseStatus(res, range)
            if (status == PieceStatus.Continue) {
                return copyToFile(res, buffer)
            }
            return status
        }
    }

    private fun shouldRetryWithBackoff(retryCount: Int): Boolean {
        if (retryCount >= maxRetry) {
            Logger.error(logTag, "Error downloading chunk ${piece.id} as Max retry exceeded")
            return false
        }
        if (retryCount > 0) {
            Thread.sleep(3000)
        }
        return true
    }

    private fun calculateRange() =
        piece.byteRange?.let { Range(it.first + piece.downloaded, it.second) } ?: Range(piece.downloaded)

    private fun processResponseStatus(res: HttpResponse, range: Range): PieceStatus {
        val code = res.statusCode
        if (code != 200 && code != 206) {
            Logger.error(logTag, "Chunk download failed - invalid response: $code")
            return PieceStatus.ServerError
        }
        if (range.start != 0L && code != 206) {
            Logger.error(logTag, "Chunk download failed - serve does not support resume: $code")
            return PieceStatus.NoResume
        }
        piece.length = res.contentLength
        piece.contentType = res.contentType
        Logger.info(logTag, "Connected to: ${piece.url}")
        return PieceStatus.Continue
    }

    private fun copyToFile(res: HttpResponse, buffer: ByteArray): PieceStatus {
        res.inputStream.use { input ->
            RandomAccessFile(File(tempDir, "${piece.id}"), "rw").use { outFile ->
                fileHandle = outFile
                try {
                    outFile.seek(piece.downloaded)
                } catch (ex: IOException) {
                    Logger.error(logTag, "Piece download failed, ${piece.url}", ex)
                    return PieceStatus.DiskError
                }
                while (!stopFlag.get()) {
                    val x: Int = input.read(buffer)
                    if (x == -1) {
                        Logger.info(logTag, "Piece download complete, ${piece.url}")
                        break
                    }
                    try {
                        outFile.write(buffer, 0, x)
                    } catch (ex: IOException) {
                        Logger.error(logTag, "Piece download failed, ${piece.url}", ex)
                        return PieceStatus.DiskError
                    }
                    piece.downloaded += x
                    downloadCallback(x.toLong())
                }
            }
            return PieceStatus.Done
        }
    }

    fun stop() {
        try {
            fileHandle?.close()
        } catch (ex: Exception) {
            // Do nothing
        }
    }
}