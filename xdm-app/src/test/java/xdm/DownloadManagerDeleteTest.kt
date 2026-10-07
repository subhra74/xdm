package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.DbRecord
import xdm.app.RecordStatus
import xdm.app.ui.screens.ClearAge
import xdm.app.ui.screens.ClearCriteria
import xdm.core.downloaders.DownloadType
import java.util.concurrent.TimeUnit
import java.io.File

/** Regression for CODE_REVIEW B12 (files left behind on delete) and B13 (Clear while downloading). */
class DownloadManagerDeleteTest : DownloadManagerTestBase() {

    private fun metadataFiles(id: Long) =
        listOf("task-$id.info", "task-$id.info.bak2", "$id.state", "$id.state.bak2").map { File(dir, it) }

    /** Starts [id], waits until its state file exists, then pauses it. */
    private fun startAndPause(id: Long) {
        dm.startHttpDownload(httpTask(id))
        remember()
        dm.stopDownload(id)
        assertTrue(waitFor { appDB.getById(id)?.status == RecordStatus.PAUSED }, "download never paused")
    }

    @Test
    fun deletePaused_removesTaskInfoAndState() {
        val id = nextId++
        startAndPause(id)
        // Make sure the backups exist too, as they would after a few saves.
        metadataFiles(id).filter { !it.exists() }.forEach { it.writeText("x") }

        dm.deleteDownload(id, fromDisk = false)

        assertNull(appDB.getById(id))
        metadataFiles(id).forEach { assertFalse(it.exists(), "$it left behind") }
    }

    @Test
    fun deleteActive_purgesAfterStop() {
        val t = httpTask()
        dm.startHttpDownload(t)
        remember()
        assertTrue(activeSessions().containsKey(t.id))

        dm.deleteDownload(t.id, fromDisk = false)

        assertTrue(waitFor { appDB.getById(t.id) == null }, "record not removed after stop")
        assertTrue(waitFor { metadataFiles(t.id).none { it.exists() } })
    }

    @Test
    fun clear_keepsActiveAndQueuedDownloads() {
        config.maxParallelDownloads = 1
        val paused = nextId++
        startAndPause(paused)

        val running = httpTask(); val queued = httpTask()
        dm.startHttpDownload(running)
        dm.startHttpDownload(queued)
        remember()
        assertEquals(setOf(running.id), activeSessions().keys)
        assertEquals(listOf(queued.id), queuedIds())

        val removed = dm.clearInactive()

        assertEquals(1, removed)
        assertNull(appDB.getById(paused), "paused download must be cleared")
        metadataFiles(paused).forEach { assertFalse(it.exists(), "$it left behind") }
        assertNotNull(appDB.getById(running.id), "running download must be kept")
        assertNotNull(appDB.getById(queued.id), "queued download must be kept")
        assertTrue(File(dir, "task-${running.id}.info").exists())
        assertTrue(File(dir, "task-${queued.id}.info").exists())
        assertEquals(setOf(running.id), activeSessions().keys)
        assertEquals(listOf(queued.id), queuedIds())

        // The index still resolves the surviving rows after removal.
        assertEquals(running.id, appDB.getByIndex(appDB.indexById(running.id)!!).id)
        assertEquals(queued.id, appDB.getByIndex(appDB.indexById(queued.id)!!).id)
    }

    /** A completed download whose file exists in [dir], added [ageDays] ago. */
    private fun finished(ageDays: Long = 0): Pair<Long, File> {
        val t = httpTask()
        taskDB.saveHttpTask(t)
        val file = File(dir, t.fileName).apply { writeText("data") }
        appDB.addActive(
            DbRecord(
                id = t.id, size = 4, downloaded = 4, progress = 100,
                date = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(ageDays), fileName = t.fileName,
                eta = 0, speed = 0f, selected = false, status = RecordStatus.FINISHED,
                downloadType = DownloadType.Http
            )
        )
        appDB.saveFinishedRecords()
        return t.id to file
    }

    @Test
    fun deleteFinishedFromDisk_deletesTheFile() {
        val (id, file) = finished()

        dm.deleteDownload(id, fromDisk = true)

        assertNull(appDB.getById(id))
        assertFalse(file.exists(), "file must be deleted from disk")
        metadataFiles(id).forEach { assertFalse(it.exists(), "$it left behind") }
    }

    @Test
    fun deleteFinishedKeepsFileByDefault() {
        val (id, file) = finished()
        dm.deleteDownload(id, fromDisk = false)
        assertTrue(file.exists(), "file must stay")
    }

    @Test
    fun deleteDownloads_removesIdleAndStopsRunningInOnePass() {
        val paused1 = nextId++; startAndPause(paused1)
        val paused2 = nextId++; startAndPause(paused2)
        val (done, file) = finished()
        val running = httpTask()
        dm.startHttpDownload(running)
        remember()

        dm.deleteDownloads(listOf(paused1, paused2, done, running.id), fromDisk = true)

        listOf(paused1, paused2, done).forEach { assertNull(appDB.getById(it), "$it not removed") }
        assertFalse(file.exists(), "finished file must be deleted from disk")
        assertTrue(waitFor { appDB.getById(running.id) == null }, "running download not removed after stop")
        listOf(paused1, paused2, done, running.id).forEach { id ->
            assertTrue(waitFor { metadataFiles(id).none { it.exists() } }, "metadata of $id left behind")
        }
    }

    @Test
    fun clearWithCriteria_filtersByStatusAndAge() {
        val (old, oldFile) = finished(ageDays = 40)
        val (recent, _) = finished(ageDays = 2)
        val paused = nextId++; startAndPause(paused)
        val c = ClearCriteria(setOf(RecordStatus.FINISHED), ClearAge.MONTH)
        val now = System.currentTimeMillis()

        val removed = dm.clearInactive(fromDisk = true) { c.matches(it, now) }

        assertEquals(1, removed, "removed count")
        assertNull(appDB.getById(old), "old completed download must be cleared")
        assertFalse(oldFile.exists(), "old file must be deleted from disk")
        assertNotNull(appDB.getById(recent), "recent completed download must be kept")
        assertNotNull(appDB.getById(paused), "paused download must be kept")
    }
}
