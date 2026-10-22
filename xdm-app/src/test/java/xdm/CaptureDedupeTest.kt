package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.CapturedVideoTracker
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.integration.ClearTabFilter
import xdm.integration.StreamingVideoDisplayInfo
import java.net.URI

/**
 * A tab that reloads the same page is no longer cleared ([ClearTabFilter]), so the tracker drops
 * duplicate captures instead, keeping the newest.
 */
class CaptureDedupeTest {

    @Test
    fun clearTab_onlyWhenThePageChanges() {
        val filter = ClearTabFilter()
        assertTrue(filter.shouldClear("1", "https://www.facebook.com/"), "first load")
        repeat(20) { assertFalse(filter.shouldClear("1", "https://www.facebook.com/"), "iframe storm on the same page") }
        assertTrue(filter.shouldClear("1", "https://www.facebook.com/watch/?v=9"), "another page")
        assertTrue(filter.shouldClear("2", "https://www.facebook.com/"), "tabs are tracked apart")
        assertTrue(filter.shouldClear("1", null), "no URL: clear as before")
        assertTrue(filter.shouldClear("1", "https://www.facebook.com/"), "back to the first page")
    }

    @Test
    fun clearTab_forgetsOldestTabs() {
        val filter = ClearTabFilter(maxTabs = 2)
        filter.shouldClear("1", "a"); filter.shouldClear("2", "b"); filter.shouldClear("3", "c")
        assertTrue(filter.shouldClear("1", "a"), "evicted tab counts as new")
    }

    private fun display(tab: String) = StreamingVideoDisplayInfo(tabId = tab)

    private fun http(id: Long, url: String) = HttpDownloadTaskInfo(
        id = id, url = url, fileName = "f$id.mp4", respectFileName = true, cookie = null, headers = null,
        origin = null, categoryId = null, targetFolder = "/tmp", maxPiece = 1, authInfo = null, knownFileSize = null,
    )

    private fun hls(id: Long, url: String, audio: String?) = HlsDownloadTaskInfo(
        id = id, fileName = "h$id.mp4", tempDir = "/tmp", respectFileName = true, cookie = null, headers = null,
        origin = null, categoryId = null, targetFolder = "/tmp", maxPiece = 1, authInfo = null,
        url = url, audioUrl = audio, independent = false,
    )

    private fun dash(id: Long, video: String, audio: String) = DashDownloadTaskInfo(
        id = id, fileName = "d$id.mp4", tempDir = "/tmp", respectFileName = true, cookie = null, headers = null,
        origin = null, categoryId = null, targetFolder = "/tmp", maxPiece = 1, authInfo = null,
        videoSegments = listOf(DashSegment(URI(video), 0L to 100L)), audioSegments = listOf(DashSegment(URI(audio))),
        url = "https://h/manifest.mpd", audioMime = "audio/mp4", videoMime = "video/mp4",
    )

    @Test
    fun http_sameUrlSameTab_latestKept() {
        val t = CapturedVideoTracker()
        t.addVideoHttp(listOf(http(1, "https://h/a.mp4") to display("7")))
        t.addVideoHttp(listOf(http(2, "https://h/a.mp4") to display("7")))
        assertEquals(listOf(2L), t.videoList.map { it.id }, "the newer capture replaces the older")
    }

    @Test
    fun sameUrlInAnotherTab_kept() {
        val t = CapturedVideoTracker()
        t.addVideoHttp(listOf(http(1, "https://h/a.mp4") to display("7")))
        t.addVideoHttp(listOf(http(2, "https://h/a.mp4") to display("8")))
        assertEquals(setOf(1L, 2L), t.videoList.map { it.id }.toSet(), "one per tab")
    }

    @Test
    fun hls_urlPair() {
        val t = CapturedVideoTracker()
        t.addVideoHls(listOf(hls(1, "https://h/v720.m3u8", "https://h/aud.m3u8") to display("7")))
        t.addVideoHls(listOf(hls(2, "https://h/v1080.m3u8", "https://h/aud.m3u8") to display("7")))
        assertEquals(2, t.videoList.size, "different variant, same audio: both kept")
        t.addVideoHls(listOf(hls(3, "https://h/v720.m3u8", "https://h/aud.m3u8") to display("7")))
        assertEquals(setOf(2L, 3L), t.videoList.map { it.id }.toSet(), "same pair: the newest replaces it")
    }

    @Test
    fun dash_videoAudioPair() {
        val t = CapturedVideoTracker()
        t.addVideoDash(listOf(dash(1, "https://c/v.mp4", "https://c/a.mp4") to display("7")))
        t.addVideoDash(listOf(dash(2, "https://c/v.mp4", "https://c/a2.mp4") to display("7")))
        assertEquals(2, t.videoList.size, "different audio: both kept")
        t.addVideoDash(listOf(dash(3, "https://c/v.mp4", "https://c/a.mp4") to display("7")))
        assertEquals(setOf(2L, 3L), t.videoList.map { it.id }.toSet(), "same pair: the newest replaces it")
        assertEquals("https://c/a.mp4", t.getDashVideo(3)!!.audioSegments.first().toString(), "newest entry is the one kept")
    }

    @Test
    fun sameId_replacedInPlace() {
        val t = CapturedVideoTracker()
        t.addVideoDash(listOf(dash(1, "https://c/v480.mp4", "https://c/a.mp4") to display("7")))
        t.addVideoDash(listOf(dash(1, "https://c/v1080.mp4", "https://c/a.mp4") to display("7")))
        assertEquals(listOf(1L), t.videoList.map { it.id }, "an upgrade keeps its id")
        assertEquals("https://c/v1080.mp4", t.getDashVideo(1)!!.videoSegments.first().toString(), "upgraded")
    }
}
