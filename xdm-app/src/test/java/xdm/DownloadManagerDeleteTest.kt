package xdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.app.RecordStatus
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
        assertTrue("download never paused", waitFor { appDB.getById(id)?.status == RecordStatus.PAUSED })
    }

    @Test
    fun deletePaused_removesTaskInfoAndState() {
        val id = nextId++
        startAndPause(id)
        // Make sure the backups exist too, as they would after a few saves.
        metadataFiles(id).filter { !it.exists() }.forEach { it.writeText("x") }

        dm.deleteDownload(id, fromDisk = false)

        assertNull(appDB.getById(id))
        metadataFiles(id).forEach { assertFalse("$it left behind", it.exists()) }
    }

    @Test
    fun deleteActive_purgesAfterStop() {
        val t = httpTask()
        dm.startHttpDownload(t)
        remember()
        assertTrue(activeSessions().containsKey(t.id))

        dm.deleteDownload(t.id, fromDisk = false)

        assertTrue("record not removed after stop", waitFor { appDB.getById(t.id) == null })
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
        assertNull("paused download must be cleared", appDB.getById(paused))
        metadataFiles(paused).forEach { assertFalse("$it left behind", it.exists()) }
        assertNotNull("running download must be kept", appDB.getById(running.id))
        assertNotNull("queued download must be kept", appDB.getById(queued.id))
        assertTrue(File(dir, "task-${running.id}.info").exists())
        assertTrue(File(dir, "task-${queued.id}.info").exists())
        assertEquals(setOf(running.id), activeSessions().keys)
        assertEquals(listOf(queued.id), queuedIds())

        // The index still resolves the surviving rows after removal.
        assertEquals(running.id, appDB.getByIndex(appDB.indexById(running.id)!!).id)
        assertEquals(queued.id, appDB.getByIndex(appDB.indexById(queued.id)!!).id)
    }
}
