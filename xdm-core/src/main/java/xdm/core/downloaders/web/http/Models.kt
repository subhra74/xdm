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
)

enum class ChunkStatus {
    Finished, Downloading, Failed, Cancelled, Ready
}

sealed interface ConnectResult {
    data class Connected(val response: HttpResponse) : ConnectResult
    data class Retry(val delay: Long) : ConnectResult
    data object InvalidResponse : ConnectResult
    data object NoResume : ConnectResult
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
    // Redundant "race" connection support: when the last remaining chunk stalls, a second
    // connection is spawned over the same byte range. `isRace` marks such a connection (never
    // infer this from `raceOf`'s sign - chunk ids are signed and often negative). `raceOf` is
    // the id of the primary chunk this race is covering; `racedBy` is the id of the race chunk
    // spawned for this (primary) chunk. These are transient and never persisted. `response`
    // holds the in-flight response so a losing/paused connection can be force-closed to unblock
    // a stalled read.
    var isRace: Boolean = false,
    var raceOf: Long = -1L,
    val racedBy: AtomicLong = AtomicLong(-1L),
    val response: AtomicReference<HttpResponse?> = AtomicReference(null),
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
