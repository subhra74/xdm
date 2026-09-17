package xdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.integration.StreamingVideoDisplayInfo
import java.io.File
import java.net.URI

/**
 * Regression for CODE_REVIEW B6: downloading the same detected video more than once must create
 * independent downloads (own id, own task info, state and temp folder) and must not modify the
 * entry stored in the video tracker.
 */
class DownloadManagerVideoTest : DownloadManagerTestBase() {

    private fun recordIds(): List<Long> = (0 until appDB.size).map { appDB.getByIndex(it).id }

    private fun downloadTwice(videoId: Long): List<Long> {
        config.maxParallelDownloads = 5
        dm.addVideoDownload(videoId, "first.mp4", File(dir, "out1").absolutePath, autoSelectFolder = false)
        dm.addVideoDownload(videoId, "second.mp4", File(dir, "out2").absolutePath, autoSelectFolder = true)
        remember()
        return recordIds()
    }

    @Test
    fun httpVideoDownloadedTwice_getsTwoIndependentDownloads() {
        val source = httpTask().apply { fileName = "detected.mp4" }
        tracker.addVideoHttp(listOf(source to StreamingVideoDisplayInfo()))

        val ids = downloadTwice(source.id)
        assertEquals("records", 2, ids.size)
        assertNotEquals("the two downloads share an id", ids[0], ids[1])
        assertEquals(
            "each download keeps its own task info",
            listOf("first.mp4", "second.mp4"), ids.map { taskDB.getHttpTask(it)!!.fileName }
        )
        assertEquals(listOf(File(dir, "out1").absolutePath, File(dir, "out2").absolutePath),
            ids.map { taskDB.getHttpTask(it)!!.defaultDownloadFolder })
    }

    @Test
    fun hlsVideoDownloadedTwice_getsSeparateIdsAndTempFolders() {
        val source = hlsTask().apply { fileName = "detected.mp4" }
        tracker.addVideoHls(listOf(source to StreamingVideoDisplayInfo()))

        val ids = downloadTwice(source.id)
        assertEquals("records", 2, ids.size)
        assertNotEquals("the two downloads share an id", ids[0], ids[1])
        val temps = ids.map { taskDB.getHlsTask(it)!!.tempDir }
        assertNotEquals("the two downloads share a temp folder", temps[0], temps[1])
    }

    @Test
    fun dashVideoDownloadedTwice_getsSeparateIds() {
        val source = DashDownloadTaskInfo(
            id = nextId++, fileName = "detected.mp4", tempDir = "", respectFileName = false, cookie = null,
            headers = null, origin = null, autoCategorize = false, defaultDownloadFolder = dir.absolutePath,
            userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null,
            videoSegments = listOf(DashSegment(URI("$hangingBase/v.m4s"), null)),
            audioSegments = listOf(DashSegment(URI("$hangingBase/a.m4s"), null)),
            url = "$hangingBase/manifest.mpd", audioMime = "audio/mp4", videoMime = "video/mp4",
        )
        tracker.addVideoDash(listOf(source to StreamingVideoDisplayInfo()))

        val ids = downloadTwice(source.id)
        assertEquals("records", 2, ids.size)
        assertNotEquals("the two downloads share an id", ids[0], ids[1])
        ids.forEach { assertNotNull(taskDB.getDashTask(it)) }
    }

    @Test
    fun trackerEntry_isNotModifiedByDownloading() {
        val http = httpTask().apply { fileName = "detected.bin" }
        val hls = hlsTask().apply { fileName = "detected.mp4" }
        tracker.addVideoHttp(listOf(http.copy() to StreamingVideoDisplayInfo()))
        tracker.addVideoHls(listOf(hls.copy() to StreamingVideoDisplayInfo()))

        config.maxParallelDownloads = 5
        dm.addVideoDownload(http.id, "renamed.bin", File(dir, "elsewhere").absolutePath, autoSelectFolder = true)
        dm.addVideoDownload(hls.id, "renamed.mp4", File(dir, "elsewhere").absolutePath, autoSelectFolder = true)
        remember()

        assertEquals(http, tracker.getHttpVideo(http.id))
        assertEquals(hls, tracker.getHlsVideo(hls.id))
    }

    @Test
    fun startWithIdThatAlreadyHasARecord_assignsNewId() {
        config.maxParallelDownloads = 5
        val task = httpTask()
        dm.startHttpDownload(task)
        dm.startHttpDownload(task.copy(fileName = "again.bin"))
        remember()

        val ids = recordIds()
        assertEquals("records", 2, ids.size)
        assertTrue("duplicate id in the downloads list: $ids", ids.toSet().size == 2)
        assertEquals(DownloadType.Http, appDB.getById(ids[1])!!.downloadType)
        assertEquals("again.bin", taskDB.getHttpTask(ids[1])!!.fileName)
        assertEquals("f${task.id}.bin", taskDB.getHttpTask(task.id)!!.fileName)
    }
}
