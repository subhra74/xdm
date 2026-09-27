package xdm

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
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

    @After
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
            "download never made progress",
            waitFor(20_000) { (appDB.getById(task.id)?.downloaded ?: 0L) in 1 until data.size.toLong() }
        )
        dm.stopDownload(task.id)
        assertTrue(
            "download never paused",
            waitFor(20_000) { appDB.getById(task.id)?.status == RecordStatus.PAUSED }
        )
        val downloadedWhenPaused = appDB.getById(task.id)!!.downloaded
        assertTrue("nothing was downloaded before the pause", downloadedWhenPaused > 0)

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
            "download did not finish on the refreshed link",
            waitFor(60_000) { appDB.getById(task.id)?.status == RecordStatus.FINISHED }
        )

        // The engine names the file from the response, so take the name it settled on.
        val finished = appDB.getById(task.id)!!
        val output = File(dir, finished.fileName)
        assertTrue("finished file is missing: ${dir.list()?.toList()}", output.isFile)
        assertEquals(data.size.toLong(), finished.size)
        assertArrayEquals("refreshed download produced a different file", data, output.readBytes())

        // Every request after the refresh used the new link, and none of them started from zero
        // for a chunk that was already partly on disk.
        val after = fileServer.requests.toList()
        assertTrue("no request was made after the refresh", after.isNotEmpty())
        assertTrue(
            "a request still used the stale link",
            after.all { it.query == "token=new" && it.cookie == "session=new" }
        )
        assertTrue(
            "the refreshed headers were not sent",
            after.all { it.headers["x-refresh"] == listOf("yes") }
        )
        assertTrue("the download restarted instead of resuming", after.any { it.rangeStart > 0 })
    }

    @Test
    fun refreshedCookieReplacesTheOldOneInsteadOfBeingMerged() {
        val task = httpTask().copy(url = fileServer.url("old"), cookie = "session=old")
        taskDB.saveHttpTask(task)

        dm.updateDownloadLink(task.id, fileServer.url("new"), null, "session=new")

        val info = taskDB.getHttpTask(task.id)
        assertNotNull(info)
        assertEquals("session=new", info!!.cookie)
        assertFalse("the old cookie survived the refresh", info.cookie!!.contains("session=old"))
    }
}
