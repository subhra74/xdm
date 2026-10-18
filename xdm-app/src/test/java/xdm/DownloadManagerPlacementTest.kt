package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.ChangeFolderPlan
import xdm.app.ChangeFolderResult
import xdm.app.DbRecord
import xdm.app.RecordStatus
import xdm.core.downloaders.CommitResult
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.partFileName
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Where a download's bytes live (FILE_PLACEMENT.md, docs/download-categories.md): HTTP writes its part
 * file straight into the target folder and publishes with a same-folder rename; streaming muxes there;
 * Change folder copies a stopped download's part file to another folder, then switches the record.
 */
class DownloadManagerPlacementTest : DownloadManagerTestBase() {

    private fun commit(id: Long, part: File, type: DownloadType): CommitResult =
        host().commitOutputFile(id, part.absolutePath, type)

    private fun stoppedRecord(id: Long, type: DownloadType, status: RecordStatus = RecordStatus.PAUSED) = appDB.addActive(
        DbRecord(
            id = id, size = 0, downloaded = 0, progress = 0, date = 0L, fileName = "file.bin",
            eta = 0, speed = 0f, status = status, selected = false, downloadType = type,
        )
    )

    /** An HTTP download in [folder] with a part file and saved progress, as a paused one leaves it. */
    private fun pausedHttpWithPart(folder: File, content: String = "partial"): Long {
        val task = httpTask().copy(targetFolder = folder.absolutePath, fileName = "file.bin")
        taskDB.saveHttpTask(task)
        folder.mkdirs()
        File(folder, partFileName(task.id)).writeText(content)
        File(dir, "${task.id}.state").writeText("progress")
        stoppedRecord(task.id, DownloadType.Http)
        return task.id
    }

    private fun changeFolder(id: Long, folder: File): ChangeFolderResult {
        val result = AtomicReference<ChangeFolderResult>()
        val done = CountDownLatch(1)
        dm.changeFolder(id, folder.absolutePath) { result.set(it); done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS), "change folder did not finish")
        return result.get()
    }

    // ---- publish ------------------------------------------------------------------------------

    @Test
    fun publish_renamesThePartFileInItsFolder() {
        val out = File(dir, "out").apply { mkdirs() }
        val task = httpTask().copy(targetFolder = out.absolutePath, fileName = "file.bin")
        taskDB.saveHttpTask(task)
        val part = File(out, partFileName(task.id)).apply { writeText("downloaded") }

        val result = commit(task.id, part, DownloadType.Http) as CommitResult.Success
        assertEquals(out.absolutePath, result.outputDir)
        assertEquals("downloaded", File(out, "file.bin").readText())
        assertFalse(part.exists(), "the part file is renamed, not copied")
    }

    @Test
    fun publish_nameTaken_autoRenamesByDefault() {
        val out = File(dir, "out").apply { mkdirs() }
        File(out, "file.bin").writeText("someone else's file")
        val task = httpTask().copy(targetFolder = out.absolutePath, fileName = "file.bin")
        taskDB.saveHttpTask(task)
        val part = File(out, partFileName(task.id)).apply { writeText("downloaded") }

        val result = commit(task.id, part, DownloadType.Http) as CommitResult.Success
        assertEquals("file_1.bin", result.fileName)
        assertEquals("someone else's file", File(out, "file.bin").readText())
        assertEquals("file_1.bin", taskDB.getHttpTask(task.id)!!.fileName, "the published name is saved")
    }

    @Test
    fun publish_nameTaken_overwriteReplaces() {
        config.overwriteExistingFiles = true
        val out = File(dir, "out").apply { mkdirs() }
        File(out, "file.bin").writeText("stale")
        val task = httpTask().copy(targetFolder = out.absolutePath, fileName = "file.bin")
        taskDB.saveHttpTask(task)
        val part = File(out, partFileName(task.id)).apply { writeText("downloaded") }

        assertEquals("file.bin", (commit(task.id, part, DownloadType.Http) as CommitResult.Success).fileName)
        assertEquals("downloaded", File(out, "file.bin").readText())
    }

    @Test
    fun publish_nameTaken_withNeitherSetting_failsAndKeepsThePart() {
        config.overwriteExistingFiles = false
        config.autoRenameOnConflict = false
        val out = File(dir, "out").apply { mkdirs() }
        File(out, "file.bin").writeText("someone else's file")
        val task = httpTask().copy(targetFolder = out.absolutePath, fileName = "file.bin")
        taskDB.saveHttpTask(task)
        val part = File(out, partFileName(task.id)).apply { writeText("downloaded") }

        assertEquals(CommitResult.Failed(DownloadError.OutputWriteError), commit(task.id, part, DownloadType.Http))
        assertTrue(part.exists(), "part kept for a retry")
    }

    @Test
    fun publish_missingTaskInfo_isInternalError() {
        val part = File(dir, "orphan").apply { writeText("data") }
        assertEquals(CommitResult.Failed(DownloadError.InternalError), commit(123456L, part, DownloadType.Hls))
    }

    @Test
    fun streamingOutput_isTheHexPartFileInTheTargetFolder() {
        val out = File(dir, "videos")
        val hls = hlsTask().copy(targetFolder = out.absolutePath, fileName = "clip.mp4")
        taskDB.saveHlsTask(hls)
        assertEquals(
            File(out, partFileName(hls.id) + ".mp4").absolutePath,
            host().outputFilePath(hls.id, DownloadType.Hls, ".mp4"),
        )
        assertNull(host().outputFilePath(hls.id, DownloadType.Http, ".mp4"), "HTTP writes its part file directly")
    }

    @Test
    fun partFileName_isUnsignedHex() {
        assertEquals(".ffffffffffffffff.xdm-part", partFileName(-1L))
        assertEquals(".ff.xdm-part", partFileName(255L))
    }

    // ---- delete -------------------------------------------------------------------------------

    @Test
    fun deletingPausedHttp_removesItsPartFile() {
        val out = File(dir, "out")
        val id = pausedHttpWithPart(out)
        dm.deleteDownload(id, fromDisk = false)
        assertFalse(File(out, partFileName(id)).exists(), "part file left in the download folder")
    }

    @Test
    fun deletingPausedStreaming_removesPartialOutput() {
        val out = File(dir, "videos").apply { mkdirs() }
        val hls = hlsTask().copy(targetFolder = out.absolutePath, fileName = "clip.mp4")
        taskDB.saveHlsTask(hls)
        stoppedRecord(hls.id, DownloadType.Hls)
        val part = File(out, partFileName(hls.id) + ".mp4").apply { writeText("half a video") }

        dm.deleteDownload(hls.id, fromDisk = false)
        assertFalse(part.exists(), "partial output left in the download folder")
    }

    @Test
    fun deleteIsRefusedWhileMoving() {
        val id = pausedHttpWithPart(File(dir, "out"))
        @Suppress("UNCHECKED_CAST")
        val moving = dm.javaClass.getDeclaredField("moving").apply { isAccessible = true }.get(dm) as MutableSet<Long>
        moving.add(id)
        dm.deleteDownload(id, fromDisk = false)
        assertTrue(appDB.getById(id) != null, "a download being moved was deleted")
        moving.remove(id)
    }

    // ---- change folder ------------------------------------------------------------------------

    @Test
    fun changeFolder_movesThePartAndRecordsTheFolder() {
        val old = File(dir, "old")
        val new = File(dir, "new")
        val id = pausedHttpWithPart(old, "bytes so far")

        assertEquals(ChangeFolderPlan.MOVE, dm.changeFolderPlan(id, new.absolutePath))
        assertEquals(ChangeFolderResult.DONE, changeFolder(id, new))
        assertEquals("bytes so far", File(new, partFileName(id)).readText())
        assertFalse(File(old, partFileName(id)).exists(), "old part left behind")
        assertEquals(new.absolutePath, taskDB.getHttpTask(id)!!.targetFolder)
        assertTrue(File(dir, "$id.state").isFile, "progress is kept")
        assertEquals(RecordStatus.PAUSED, appDB.getById(id)!!.status)
    }

    @Test
    fun changeFolder_sameFolderSpelledDifferently_keepsThePartFile() {
        val old = File(dir, "old")
        val id = pausedHttpWithPart(old, "the only copy")
        val alias = File(dir, "alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), old.toPath())

        assertEquals(ChangeFolderPlan.SAME, dm.changeFolderPlan(id, alias.absolutePath))
        assertEquals(ChangeFolderResult.DONE, changeFolder(id, alias))
        assertEquals("the only copy", File(old, partFileName(id)).readText(), "the part file must survive")
    }

    @Test
    fun changeFolder_plans() {
        val old = File(dir, "old")
        val id = pausedHttpWithPart(old)
        assertEquals(ChangeFolderPlan.SAME, dm.changeFolderPlan(id, old.absolutePath))

        // The part file is gone (its drive unplugged, say): nothing to copy, reconnect or restart.
        old.deleteRecursively()
        assertEquals(ChangeFolderPlan.PART_UNAVAILABLE, dm.changeFolderPlan(id, File(dir, "x").absolutePath))

        appDB.getById(id)!!.status = RecordStatus.FINISHED
        assertEquals(ChangeFolderPlan.NOT_ALLOWED, dm.changeFolderPlan(id, File(dir, "x").absolutePath))
    }

    @Test
    fun changeFolder_clearsAStaleLeftoverInTheDestination() {
        val old = File(dir, "old")
        val new = File(dir, "new").apply { mkdirs() }
        val id = pausedHttpWithPart(old, "current")
        File(new, partFileName(id)).writeText("stale leftover")

        assertEquals(ChangeFolderResult.DONE, changeFolder(id, new))
        assertEquals("current", File(new, partFileName(id)).readText())
    }

    @Test
    fun changeFolder_streaming_updatesTheFolderAndDropsThePartialMux() {
        val old = File(dir, "old").apply { mkdirs() }
        val new = File(dir, "new")
        val hls = hlsTask().copy(targetFolder = old.absolutePath)
        taskDB.saveHlsTask(hls)
        stoppedRecord(hls.id, DownloadType.Hls)
        val mux = File(old, partFileName(hls.id) + ".mp4").apply { writeText("half") }

        assertEquals(ChangeFolderResult.DONE, changeFolder(hls.id, new))
        assertEquals(new.absolutePath, taskDB.getHlsTask(hls.id)!!.targetFolder)
        assertFalse(mux.exists(), "the partial mux is redone, not moved")
    }

    @Test
    fun newDownload_recordKeepsItsCategory() {
        val task = httpTask().copy(categoryId = "CAT_VIDEOS")
        dm.startHttpDownload(task, runNow = false)
        assertEquals("CAT_VIDEOS", appDB.getById(task.id)!!.categoryId)
    }
}
