package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.AppDB
import xdm.app.RecordStatus
import xdm.core.downloaders.DownloadError

/** A download stopped by an error is FAILED (RecordStatus.ERROR): kept apart from paused, resumable like it. */
class DownloadManagerFailedTest : DownloadManagerTestBase() {

    @Test
    fun failedDownload_isMarkedFailedAndSurvivesReload() {
        val failed = httpTask()
        val paused = httpTask()
        dm.startHttpDownload(failed)
        dm.startHttpDownload(paused)
        remember()

        host().onDownloadFailed(failed.id, DownloadError.NetworkError)
        dm.stopDownload(paused.id)

        assertEquals(RecordStatus.ERROR, appDB.getById(failed.id)!!.status)
        assertTrue(waitFor { appDB.getById(paused.id)?.status == RecordStatus.PAUSED }, "download never paused")
        assertTrue(!activeSessions().containsKey(failed.id), "failed download must leave the active sessions")

        val reloaded = AppDB(dir.absolutePath).apply { loadRecords() }
        assertEquals(RecordStatus.ERROR, reloaded.getById(failed.id)!!.status, "failed status is persisted")
        assertEquals(RecordStatus.PAUSED, reloaded.getById(paused.id)!!.status, "paused status is persisted")
    }

    @Test
    fun failedDownload_canBeResumed() {
        val t = httpTask()
        dm.startHttpDownload(t)
        remember()
        host().onDownloadFailed(t.id, DownloadError.NetworkError)
        assertEquals(RecordStatus.ERROR, appDB.getById(t.id)!!.status)

        dm.resumeDownload(t.id)
        remember()
        assertTrue(activeSessions().containsKey(t.id), "resume should start the failed download again")
        assertEquals(RecordStatus.DOWNLOADING, appDB.getById(t.id)!!.status)

        val reloaded = AppDB(dir.absolutePath).apply { loadRecords() }
        assertEquals(RecordStatus.PAUSED, reloaded.getById(t.id)!!.status, "a resumed download no longer reloads as failed")
    }

    @Test
    fun deletingFailedDownload_removesItFromThePausedList() {
        val t = httpTask()
        dm.startHttpDownload(t)
        remember()
        host().onDownloadFailed(t.id, DownloadError.NetworkError)

        dm.deleteDownload(t.id, fromDisk = false)

        assertEquals(null, appDB.getById(t.id))
        val reloaded = AppDB(dir.absolutePath).apply { loadRecords() }
        assertEquals(null, reloaded.getById(t.id), "deleted failed download must not come back")
    }
}
