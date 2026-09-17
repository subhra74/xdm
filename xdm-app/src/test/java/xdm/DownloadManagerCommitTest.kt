package xdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.app.DbRecord
import xdm.app.RecordStatus
import xdm.core.downloaders.CommitResult
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.HttpDownloadTaskInfo
import java.io.File

/**
 * Regression for CODE_REVIEW B7 (app side): committing the finished file.
 *
 * - failures carry a real reason instead of always meaning "disk space";
 * - HLS/DASH ask the host for an output path inside the destination folder, so muxing writes there
 *   and the commit is a same-folder rename;
 * - deleting a paused streaming download also removes that partial output.
 *
 * Uses reflection for API added by the fix so the tests also compile against the old code.
 */
class DownloadManagerCommitTest : DownloadManagerTestBase() {

    private fun commit(id: Long, tmp: File, type: DownloadType): CommitResult =
        host().commitOutputFile(id, tmp.absolutePath, type)

    /** The failure reason, or null if [r] has none (old `CommitResult.Failed` object). */
    private fun errorName(r: CommitResult): String? =
        runCatching { r.javaClass.getMethod("getError").invoke(r).toString() }.getOrNull()

    private fun outputFilePath(id: Long, type: DownloadType, ext: String): String? {
        val m = host().javaClass.methods.firstOrNull { it.name == "outputFilePath" }
            ?: throw AssertionError("host has no outputFilePath(id, type, ext)")
        return m.invoke(host(), id, type, ext) as String?
    }

    private fun httpTaskIn(folder: File, fileName: String = "file.bin") =
        httpTask().copy(defaultDownloadFolder = folder.absolutePath, fileName = fileName)

    @Test
    fun uncreatableDestinationFolder_reportsOutputWriteError() {
        val blocker = File(dir, "blocker").apply { writeText("not a folder") }
        val task = httpTaskIn(File(blocker, "sub"))
        taskDB.saveHttpTask(task)
        val tmp = File(dir, "download.tmp").apply { writeText("data") }

        val result = commit(task.id, tmp, DownloadType.Http)
        assertTrue(result is CommitResult.Failed)
        assertEquals("OutputWriteError", errorName(result))
        assertTrue("temp file must be kept for a retry", tmp.exists())
    }

    @Test
    fun missingTaskInfo_reportsInternalError() {
        val tmp = File(dir, "orphan.tmp").apply { writeText("data") }
        val result = commit(123456L, tmp, DownloadType.Hls)
        assertTrue(result is CommitResult.Failed)
        assertEquals("InternalError", errorName(result))
    }

    @Test
    fun commit_movesFileAndNeverOverwritesExistingFile() {
        val out = File(dir, "out").apply { mkdirs() }
        File(out, "file.bin").writeText("someone else's file")
        val task: HttpDownloadTaskInfo = httpTaskIn(out)
        taskDB.saveHttpTask(task)
        val tmp = File(out, "download.tmp").apply { writeText("downloaded") }

        val result = commit(task.id, tmp, DownloadType.Http) as CommitResult.Success
        assertEquals("file_1.bin", result.fileName)
        assertEquals("someone else's file", File(out, "file.bin").readText())
        assertEquals("downloaded", File(out, "file_1.bin").readText())
        assertFalse(tmp.exists())
        assertEquals("file_1.bin", taskDB.getHttpTask(task.id)!!.fileName)
    }

    @Test
    fun streamingOutputPath_isHiddenPartFileInDestinationFolder() {
        val out = File(dir, "videos")
        val hls = hlsTask().copy(defaultDownloadFolder = out.absolutePath, fileName = "clip.mp4", autoCategorize = true)
        taskDB.saveHlsTask(hls)

        val path = outputFilePath(hls.id, DownloadType.Hls, ".mp4")
        assertEquals(File(File(out, "Video"), ".${hls.id}.xdm-part.mp4").absolutePath, path)
        assertEquals("HTTP keeps its own temp file", null, outputFilePath(hls.id, DownloadType.Http, ".mp4"))
    }

    @Test
    fun deletingPausedStreamingDownload_removesPartialOutput() {
        val out = File(dir, "videos").apply { mkdirs() }
        val hls = hlsTask().copy(defaultDownloadFolder = out.absolutePath, fileName = "clip.mp4")
        taskDB.saveHlsTask(hls)
        appDB.addActive(
            DbRecord(
                id = hls.id, size = 0, downloaded = 0, progress = 0, date = 0L, fileName = "clip.mp4",
                eta = 0, speed = 0f, status = RecordStatus.PAUSED, selected = false,
                downloadType = DownloadType.Hls,
            )
        )
        val part = File(out, ".${hls.id}.xdm-part.mp4").apply { writeText("half a video") }

        dm.deleteDownload(hls.id, fromDisk = false)
        assertFalse("partial output left in the download folder", part.exists())
    }
}
