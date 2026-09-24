package xdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.CommitResult
import xdm.core.downloaders.DownloadType
import java.io.File

/**
 * The publish step of FILE_PLACEMENT.md: working data lives in the temp folder, the destination is
 * resolved only at the end, and the conflict settings decide what happens when the name is taken.
 */
class DownloadManagerPublishTest : DownloadManagerTestBase() {

    private fun commit(id: Long, tmp: File, type: DownloadType): CommitResult =
        host().commitOutputFile(id, tmp.absolutePath, type)

    private fun tempDir(id: Long): String =
        host().getTempDir(id, "https://example/file.bin", null, null)

    @Test
    fun workingDataGoesToThePerDownloadTempFolder() {
        val task = httpTask().copy(defaultDownloadFolder = File(dir, "elsewhere").absolutePath)
        taskDB.saveHttpTask(task)

        val expected = File(config.tempFolder, task.id.toString())
        assertEquals(expected.absolutePath, tempDir(task.id))
        assertTrue("the temp folder must exist before the first byte is written", expected.isDirectory)
    }

    @Test
    fun tempFolderIsIndependentOfTheDestination() {
        val task = httpTask().copy(defaultDownloadFolder = File(dir, "first").absolutePath)
        taskDB.saveHttpTask(task)
        val before = tempDir(task.id)

        // Re-categorizing or repointing a running download must not move its working file.
        taskDB.saveHttpTask(task.copy(defaultDownloadFolder = File(dir, "second").absolutePath))
        assertEquals(before, tempDir(task.id))
    }

    @Test
    fun nameTaken_autoRenamesByDefault() {
        val out = File(dir, "out").apply { mkdirs() }
        File(out, "file.bin").writeText("someone else's file")
        val task = httpTask().copy(defaultDownloadFolder = out.absolutePath, fileName = "file.bin")
        taskDB.saveHttpTask(task)
        val tmp = File(dir, "src.tmp").apply { writeText("downloaded") }

        val result = commit(task.id, tmp, DownloadType.Http) as CommitResult.Success
        assertEquals("file_1.bin", result.fileName)
        assertEquals("someone else's file", File(out, "file.bin").readText())
        assertEquals("downloaded", File(out, "file_1.bin").readText())
    }

    @Test
    fun nameTaken_overwriteReplacesTheExistingFile() {
        config.overwriteExistingFiles = true
        val out = File(dir, "out").apply { mkdirs() }
        File(out, "file.bin").writeText("stale")
        val task = httpTask().copy(defaultDownloadFolder = out.absolutePath, fileName = "file.bin")
        taskDB.saveHttpTask(task)
        val tmp = File(dir, "src.tmp").apply { writeText("downloaded") }

        val result = commit(task.id, tmp, DownloadType.Http) as CommitResult.Success
        assertEquals("file.bin", result.fileName)
        assertEquals("downloaded", File(out, "file.bin").readText())
        assertFalse("nothing renamed aside", File(out, "file_1.bin").exists())
    }

    @Test
    fun nameTaken_withNeitherSetting_failsRatherThanGuessing() {
        config.overwriteExistingFiles = false
        config.autoRenameOnConflict = false
        val out = File(dir, "out").apply { mkdirs() }
        File(out, "file.bin").writeText("someone else's file")
        val task = httpTask().copy(defaultDownloadFolder = out.absolutePath, fileName = "file.bin")
        taskDB.saveHttpTask(task)
        val tmp = File(dir, "src.tmp").apply { writeText("downloaded") }

        assertTrue(commit(task.id, tmp, DownloadType.Http) is CommitResult.Failed)
        assertEquals("existing file untouched", "someone else's file", File(out, "file.bin").readText())
        assertTrue("source kept for a retry", tmp.exists())
    }

    @Test
    fun overwriteIsIgnoredWhenNothingIsInTheWay() {
        config.overwriteExistingFiles = true
        val out = File(dir, "out")
        val task = httpTask().copy(defaultDownloadFolder = out.absolutePath, fileName = "file.bin")
        taskDB.saveHttpTask(task)
        val tmp = File(dir, "src.tmp").apply { writeText("downloaded") }

        val result = commit(task.id, tmp, DownloadType.Http) as CommitResult.Success
        assertEquals("file.bin", result.fileName)
        assertEquals("downloaded", File(out, "file.bin").readText())
    }
}
