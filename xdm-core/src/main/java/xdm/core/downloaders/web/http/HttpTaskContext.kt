package xdm.core.downloaders.web.http

import xdm.core.downloaders.web.DownloadError
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.PoolingHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock

class HttpTaskContext(
    val chunks: MutableMap<Long, Chunk>,
    val init: AtomicBoolean,
    var totalSize: Long?,
    var url: String,
    var contentType: String?,
    var headers: HeaderMap?,
    var cookie: String?,
    //var fileName: String,
    val httpClient: PoolingHttpClient,
    val stopFlag: AtomicBoolean,
    val completed: AtomicBoolean,
    val errorCallback: (error: DownloadError) -> Unit,
    val outputFolder: AtomicReference<() -> String>,
    val finalFileName: AtomicReference<() -> String>,
    val tempFileName: String,
    val tempFileCreated: AtomicBoolean,
    val lock: ReadWriteLock = ReentrantReadWriteLock(),
) {
    inline fun read(r: () -> Unit) {
        try {
            lock.readLock().lock()
            r()
        } finally {
            lock.readLock().unlock()
        }
    }

    inline fun write(r: () -> Unit) {
        try {
            lock.writeLock().lock()
            r()
        } finally {
            lock.writeLock().unlock()
        }
    }
}