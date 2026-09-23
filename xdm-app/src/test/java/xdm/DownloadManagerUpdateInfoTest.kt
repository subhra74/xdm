package xdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.web.http.makeContext
import xdm.core.downloaders.web.loadState
import xdm.core.downloaders.web.saveState
import xdm.core.network.http.impl.HttpClientImpl
import java.io.File

/**
 * Regression for CODE_REVIEW B21: `updateDownloadInfo` (the "Refresh link" path) rewrites both the
 * task info and the `<id>.state` file. The rewrite must happen after the read has finished, not
 * from inside it — writing a file while its own read is open fails the rename on Windows.
 */
class DownloadManagerUpdateInfoTest : DownloadManagerTestBase() {

    @Test
    fun refreshedLinkIsWrittenToTaskInfoAndState() {
        val client = HttpClientImpl(2)
        try {
            val task = httpTask().copy(url = "https://old.example/file.bin", cookie = "old=1")
            taskDB.saveHttpTask(task)

            // A state file, as a paused download would have left behind.
            val (context, _) = makeContext(task, host(), dir.absolutePath, client)
            saveState(context, dir.absolutePath)

            dm.updateDownloadInfo(
                task.id,
                task.copy(url = "https://new.example/file.bin?sig=abc", cookie = "new=2")
            )

            val info = taskDB.getHttpTask(task.id)!!
            assertEquals("https://new.example/file.bin?sig=abc", info.url)
            assertEquals("new=2", info.cookie)

            val reloaded = loadState(task.id, dir.absolutePath, client, host()).getOrThrow()
            assertEquals("https://new.example/file.bin?sig=abc", reloaded.url)
            assertEquals("new=2", reloaded.cookie)

            // The rewrite must land as a real state file, not be left behind as a temp copy.
            assertTrue(File(dir, "${task.id}.state").isFile)
            assertTrue(!File(dir, "${task.id}.state.bak1").exists())
        } finally {
            client.close()
        }
    }
}
