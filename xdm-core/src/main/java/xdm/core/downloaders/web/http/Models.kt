package xdm.core.downloaders.web.http

import xdm.core.downloaders.DownloadHost
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.PoolingHttpClient
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock


data class ChunkConfirmedData(
    val resumeSupport: Boolean,
    val contentLength: Long?,
    val finalUrl: String?,
    val contentDisposition: String?,
    val contentType: String?,
    val lastModified: LocalDateTime?,
    val isRedirect: Boolean,
    /** The response's Last-Modified, as sent (see [HttpTaskContext.validator]). */
    val validator: String?,
)

enum class ChunkStatus {
    Finished, Downloading, Failed, Cancelled, Ready
}

sealed interface ConnectResult {
    data class Connected(val response: HttpResponse) : ConnectResult
    data class Retry(val delay: Long) : ConnectResult
    data object InvalidResponse : ConnectResult
    data object NoResume : ConnectResult
    data object TlsError : ConnectResult
    data object LinkExpired : ConnectResult
}

enum class CopyResult {
    Done, Retry, Cancel, DiskError, Eof
}

data class Chunk(
    var id: Long,
    val offset: Long,
    var length: AtomicLong,
    var downloaded: AtomicLong,
    var status: AtomicReference<ChunkStatus>,
    var fileHandle: AtomicReference<RandomAccessFile?>,
    val lastTakeOver: AtomicLong,
    /**
     * How much of [downloaded] is known to be on disk: the progress as of the last time the temp file
     * was forced to disk. It is what `.state` records, so a resume after a crash continues from data
     * that survived it. Progress loaded from `.state` is synced by definition.
     */
    val synced: AtomicLong = AtomicLong(downloaded.get()),
)

data class HttpTaskContext(
    val id: Long,
    val chunks: MutableMap<Long, Chunk>,
    val init: AtomicBoolean,
    var totalSize: Long?,
    var downloaded: AtomicLong,
    var url: String,
    var contentType: String?,
    var headers: HeaderMap?,
    var cookie: String?,
    val stopFlag: AtomicBoolean,
    val completed: AtomicBoolean,
    val tempFileName: String,
    val tempFileCreated: AtomicBoolean,
    val lock: ReadWriteLock = ReentrantReadWriteLock(),
    val diskError: AtomicBoolean,
    val downloadHost: DownloadHost,
    var tempFolder: String,
){
    lateinit var httpClient: PoolingHttpClient

    /**
     * Identifies the version of the file the download started with: the first response's Last-Modified.
     * Sent as If-Range on range requests, so if the file has changed the server answers with the whole
     * new file (200), which is refused, instead of a range of it. Not the ETag: mirrors of the same file
     * each have their own, and a download that moves to another mirror must still resume.
     */
    @Volatile
    var validator: String? = null

    /**
     * Set once a range request got the whole file (200) or a different length: the server does not do
     * ranges. Nothing is split or restarted from then on, and resume starts the download over. Saved.
     */
    @Volatile
    var noRangeSupport: Boolean = false

    /**
     * Where [url] last redirected to, for this session only: requests go there directly, and back through
     * [url] once the target is refused (a short-lived signed URL that expired). Not saved, so a resume
     * follows the link again.
     */
    @Volatile
    var finalUrl: String? = null
}

inline fun HttpTaskContext.read(r: () -> Unit) {
    try {
        lock.readLock().lock()
        r()
    } finally {
        lock.readLock().unlock()
    }
}

inline fun HttpTaskContext.write(r: () -> Unit) {
    try {
        lock.writeLock().lock()
        r()
    } finally {
        lock.writeLock().unlock()
    }
}

data class RequestData(
    val url: String,
    val offset: Long,
    val downloaded: Long,
    val length: Long,
    val firstRequest: Boolean
)
