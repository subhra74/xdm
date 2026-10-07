package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.RecordStatus
import xdm.core.downloaders.BatchDownloadTaskInfo
import xdm.core.downloaders.BatchItem
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.web.batch.BatchFile
import xdm.core.downloaders.web.batch.BatchTaskContext
import xdm.core.downloaders.web.batch.batchPartFile
import xdm.core.downloaders.web.batch.saveBatchState
import xdm.core.downloaders.web.http.ChunkStatus
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Adding, deleting and retrying batch downloads through the real [xdm.app.DownloadManager]. */
class DownloadManagerBatchTest : DownloadManagerTestBase() {

    private fun batchTask(id: Long = nextId++, name: String = "Batch$id") = BatchDownloadTaskInfo(
        id = id, name = name, folder = dir.absolutePath, headers = null, origin = "https://example.com/",
        maxPiece = 2, cookies = emptyList(),
        items = listOf(
            BatchItem("$hangingBase/a", "a.bin", -1, null, false),
            BatchItem("$hangingBase/b", "b.bin", -1, null, false),
        ),
    )

    /** Adds [t], then pauses it so it can be deleted or inspected. */
    private fun startAndPause(t: BatchDownloadTaskInfo) {
        assertTrue(dm.startBatchDownload(t), "batch should be added")
        remember()
        dm.stopDownload(t.id)
        assertTrue(waitFor { appDB.getById(t.id)?.status == RecordStatus.PAUSED }, "batch never paused")
    }

    /** State for [t]: file 0 finished as a.bin, file 1 half-downloaded in its part file. */
    private fun fakeProgress(t: BatchDownloadTaskInfo) {
        val folder = File(t.batchFolder)
        File(folder, "a.bin").writeText("a")
        batchPartFile(folder.absolutePath, 1).writeText("b-part")
        saveBatchState(
            BatchTaskContext(
                t.id, folder.absolutePath, listOf(
                    BatchFile(0, status = AtomicReference(ChunkStatus.Finished), finalName = "a.bin"),
                    BatchFile(1),
                )
            ), dir.absolutePath
        )
    }

    @Test
    fun start_createsFolderAndOneRecord() {
        val t = batchTask()
        assertTrue(dm.startBatchDownload(t), "batch should be added")
        remember()

        assertTrue(File(t.batchFolder).isDirectory, "batch folder created")
        val rec = appDB.getById(t.id)
        assertEquals(DownloadType.Batch, rec?.downloadType, "one record of type Batch")
        assertEquals(t.name, rec?.fileName, "named after the batch")
        assertEquals(t, taskDB.getBatchTask(t.id), "task info saved")
    }

    @Test
    fun start_refusesAFolderThatIsNotEmpty() {
        val t = batchTask()
        File(t.batchFolder).mkdirs()
        File(t.batchFolder, "someone-elses.txt").writeText("x")

        assertFalse(dm.startBatchDownload(t), "a non-empty folder is refused")
        assertNull(appDB.getById(t.id), "nothing added")
    }

    @Test
    fun start_reusesAnEmptyFolder() {
        val t = batchTask()
        File(t.batchFolder).mkdirs()

        assertTrue(dm.startBatchDownload(t), "an empty folder is reused")
        remember()
    }

    @Test
    fun deleteFromDisk_removesTheBatchsFilesOnly() {
        val t = batchTask()
        startAndPause(t)
        fakeProgress(t)
        val foreign = File(t.batchFolder, "mine.txt").apply { writeText("keep") }

        dm.deleteDownload(t.id, fromDisk = true)

        assertFalse(File(t.batchFolder, "a.bin").exists(), "finished file deleted")
        assertFalse(batchPartFile(t.batchFolder, 1).exists(), "part file deleted")
        assertTrue(foreign.isFile, "a file the batch did not create stays")
        assertFalse(File(dir, "task-${t.id}.info").exists(), "task info deleted")
        assertFalse(File(dir, "${t.id}.state").exists(), "state deleted")
    }

    @Test
    fun deleteKeepingFiles_removesOnlyPartFiles() {
        val t = batchTask()
        startAndPause(t)
        fakeProgress(t)

        dm.deleteDownload(t.id, fromDisk = false)

        assertTrue(File(t.batchFolder, "a.bin").isFile, "finished file kept")
        assertFalse(batchPartFile(t.batchFolder, 1).exists(), "part file deleted")
        assertNull(appDB.getById(t.id), "record removed")
    }

    @Test
    fun retryFailedFiles_restartsAFinishedBatchWithFailures() {
        val t = batchTask()
        startAndPause(t)
        appDB.getById(t.id)!!.apply {
            status = RecordStatus.FINISHED
            failedFiles = 1
        }

        dm.retryFailedFiles(t.id)
        remember()

        assertTrue(activeSessions().containsKey(t.id), "batch running again")
        assertEquals(0, appDB.getById(t.id)?.failedFiles, "failure count cleared until it finishes again")
    }

    @Test
    fun retryFailedFiles_ignoresABatchWithoutFailures() {
        val t = batchTask()
        startAndPause(t)
        appDB.getById(t.id)!!.status = RecordStatus.FINISHED

        dm.retryFailedFiles(t.id)

        assertFalse(activeSessions().containsKey(t.id), "nothing to retry")
        assertEquals(RecordStatus.FINISHED, appDB.getById(t.id)?.status, "still finished")
    }
}
