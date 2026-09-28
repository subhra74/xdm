package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.DbRecord
import xdm.app.RecordStatus
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadType
import java.util.concurrent.CountDownLatch

/** Regression for CODE_REVIEW B5: the parallel-download limit and the queue. */
class DownloadManagerQueueTest : DownloadManagerTestBase() {

    // ---- tests ----------------------------------------------------------------------------

    @Test
    fun hlsDownloads_respectParallelLimit() {
        config.maxParallelDownloads = 1
        val a = hlsTask(); val b = hlsTask()
        dm.startHlsDownload(a)
        dm.startHlsDownload(b)
        remember()

        assertEquals(1, activeSessions().size, "active downloads")
        assertEquals(listOf(b.id), queuedIds(), "queued downloads")
    }

    @Test
    fun queuedHlsDownload_startsWhenSlotFrees() {
        config.maxParallelDownloads = 1
        val http = httpTask(); val hls = hlsTask()
        dm.startHttpDownload(http)
        dm.startHlsDownload(hls)
        remember()
        assertEquals(setOf(http.id), activeSessions().keys, "HLS must wait for the HTTP download")

        dm.stopDownload(http.id)
        assertTrue(waitFor { activeSessions().keys == setOf(hls.id) }, "queued HLS download never started")
        remember()
    }

    @Test
    fun duplicateFailureCallback_startsOnlyOneQueuedDownload() {
        config.maxParallelDownloads = 1
        val a = httpTask(); val b = httpTask(); val c = httpTask()
        dm.startHttpDownload(a)
        dm.startHttpDownload(b)
        dm.startHttpDownload(c)
        remember()
        assertEquals(setOf(a.id), activeSessions().keys)

        host().onDownloadFailed(a.id, DownloadError.NetworkError)
        host().onDownloadFailed(a.id, DownloadError.NetworkError) // duplicate
        remember()

        assertEquals(setOf(b.id), activeSessions().keys, "only one queued download may take the freed slot")
        assertEquals(listOf(c.id), queuedIds())
    }

    @Test
    fun queuedResumeWithMissingTask_isMarkedPausedAndQueueContinues() {
        config.maxParallelDownloads = 1
        val a = httpTask()
        dm.startHttpDownload(a)
        remember()

        // A paused download whose task-<id>.info is gone.
        val missingId = nextId++
        appDB.addActive(
            DbRecord(
                id = missingId, size = 0, downloaded = 0, progress = 0, date = 0L, fileName = "gone.bin",
                eta = 0, speed = 0f, status = RecordStatus.PAUSED, selected = false,
                downloadType = DownloadType.Http,
            )
        )
        dm.resumeDownload(missingId)
        val b = httpTask()
        dm.startHttpDownload(b)

        dm.stopDownload(a.id)
        assertTrue(waitFor { activeSessions().keys == setOf(b.id) }, "queue stalled behind the missing task")
        remember()
        assertEquals(RecordStatus.PAUSED, appDB.getById(missingId)!!.status)
    }

    @Test
    fun concurrentAdds_neverExceedLimit() {
        config.maxParallelDownloads = 2
        val threads = 8
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        var maxSeen = 0
        val watcher = Thread {
            while (done.count > 0) {
                maxSeen = maxOf(maxSeen, activeSessions().size)
            }
        }.apply { start() }
        repeat(threads) {
            val task = httpTask()
            Thread {
                go.await()
                dm.startHttpDownload(task)
                done.countDown()
            }.start()
        }
        go.countDown()
        done.await()
        watcher.join()
        remember()

        maxSeen = maxOf(maxSeen, activeSessions().size)
        assertTrue(maxSeen <= 2, "more than 2 downloads active at once: $maxSeen")
        assertEquals(threads, activeSessions().size + queuedIds().size)
    }
}
