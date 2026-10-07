package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.RecordStatus
import java.io.File
import java.util.Random

/**
 * End-to-end cover for the "Refresh link" API against a local server: start a download, pause it
 * part way, hand it a new URL, headers and cookie through [xdm.app.DownloadManager.updateDownloadLink],
 * then resume. The server rejects the link the download started with, so the resume can only
 * succeed on the refreshed one — and it must carry on from the bytes already on disk.
 */
class DownloadManagerRefreshLinkTest : DownloadManagerTestBase() {

    private val data = ByteArray(4 * 1024 * 1024).apply { Random(7).nextBytes(this) }
    private val fileServer = LocalFileServer(data)

    @AfterEach
    fun stopServer() {
        fileServer.stop()
    }

    @Test
    fun pausedDownloadResumesOnRefreshedLinkWithNewHeadersAndCookie() {
        val task = httpTask().copy(url = fileServer.url("old"), cookie = "session=old", knownFileSize = null)
        fileServer.requiredCookie = "session=old"

        dm.startHttpDownload(task)
        remember()

        // Part way in: enough bytes on disk that the resume has something to continue from.
        assertTrue(
            waitFor(20_000) { (appDB.getById(task.id)?.downloaded ?: 0L) in 1 until data.size.toLong() }
        , "download never made progress")
        dm.stopDownload(task.id)
        assertTrue(
            waitFor(20_000) { appDB.getById(task.id)?.status == RecordStatus.PAUSED }
        , "download never paused")
        val downloadedWhenPaused = appDB.getById(task.id)!!.downloaded
        assertTrue(downloadedWhenPaused > 0, "nothing was downloaded before the pause")

        // The old signed URL and session are dead from here on.
        fileServer.validToken = "new"
        fileServer.requiredCookie = "session=new"
        fileServer.requests.clear()

        dm.updateDownloadLink(
            task.id,
            fileServer.url("new"),
            mapOf("X-Refresh" to listOf("yes")),
            "session=new",
        )

        val info = taskDB.getHttpTask(task.id)!!
        assertEquals(fileServer.url("new"), info.url)
        assertEquals("session=new", info.cookie)
        assertEquals(listOf("yes"), info.headers?.get("X-Refresh"))

        dm.resumeDownload(task.id)
        remember()
        assertTrue(
            waitFor(60_000) { appDB.getById(task.id)?.status == RecordStatus.FINISHED }
        , "download did not finish on the refreshed link")

        // The engine names the file from the response, so take the name it settled on.
        val finished = appDB.getById(task.id)!!
        val output = File(dir, finished.fileName)
        assertTrue(output.isFile, "finished file is missing: ${dir.list()?.toList()}")
        assertEquals(data.size.toLong(), finished.size)
        assertArrayEquals(data, output.readBytes(), "refreshed download produced a different file")

        // Every request after the refresh used the new link, and none of them started from zero
        // for a chunk that was already partly on disk.
        val after = fileServer.requests.toList()
        assertTrue(after.isNotEmpty(), "no request was made after the refresh")
        assertTrue(
            after.all { it.query == "token=new" && it.cookie == "session=new" }
        , "a request still used the stale link")
        assertTrue(
            after.all { it.headers["x-refresh"] == listOf("yes") }
        , "the refreshed headers were not sent")
        assertTrue(after.any { it.rangeStart > 0 }, "the download restarted instead of resuming")
    }

    @Test
    fun refreshedCookieReplacesTheOldOneInsteadOfBeingMerged() {
        val task = httpTask().copy(url = fileServer.url("old"), cookie = "session=old")
        taskDB.saveHttpTask(task)

        dm.updateDownloadLink(task.id, fileServer.url("new"), null, "session=new")

        val info = taskDB.getHttpTask(task.id)
        assertNotNull(info)
        assertEquals("session=new", info!!.cookie)
        assertFalse(info.cookie!!.contains("session=old"), "the old cookie survived the refresh")
    }
}
