package xdm.core.downloaders.web.streaming.downloader

import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.PoolingHttpClient
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class StreamingChunk(
    val id: Long,
    val sequence: Long,
    var downloaded: AtomicLong = AtomicLong(0),
    var length: AtomicLong = AtomicLong(0),
    var status: AtomicReference<ChunkStatus>,
    var error: AtomicReference<DownloadError?>,
    var contentType: String? = null,
    var url: String,
    var byteRange: Pair<Long, Long>?,
    var keyUrl: String?,
    var iv: String? = null,
    val tag: String,
    val fileHandle: AtomicReference<RandomAccessFile?>,
)

interface StreamingTaskContext {
    val id: Long
    val chunks: ArrayList<StreamingChunk>
    val init: AtomicBoolean
    var totalSize: Long?
    var downloaded: AtomicLong
    var contentType: String?
    var headers: HeaderMap?
    var cookie: String?
    val httpClient: PoolingHttpClient
    val stopFlag: AtomicBoolean
    val completed: AtomicBoolean
    val diskError: AtomicBoolean
    val downloadHost: DownloadHost
    val tempFileName: String
    var tempFolder: String
    var hasSeparateStreams: Boolean
    val pieceCompletedCount: AtomicInteger
    val assembling: AtomicBoolean
}

data class HlsTaskContext(
    override val id: Long,
    override val chunks: ArrayList<StreamingChunk>,
    override val init: AtomicBoolean = AtomicBoolean(false),
    override var totalSize: Long? = null,
    override var downloaded: AtomicLong = AtomicLong(0),
    override var contentType: String? = null,
    override var headers: HeaderMap? = null,
    override var cookie: String? = null,
    override val httpClient: PoolingHttpClient,
    override val stopFlag: AtomicBoolean = AtomicBoolean(false),
    override val completed: AtomicBoolean = AtomicBoolean(false),
    override val diskError: AtomicBoolean = AtomicBoolean(false),
    override val downloadHost: DownloadHost,
    override val tempFileName: String,
    override var tempFolder: String,
    override var hasSeparateStreams: Boolean = false,
    override val pieceCompletedCount: AtomicInteger = AtomicInteger(0),
    override val assembling: AtomicBoolean = AtomicBoolean(false),
) : StreamingTaskContext