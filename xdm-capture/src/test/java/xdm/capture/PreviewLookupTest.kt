package xdm.capture

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.core.downloaders.HlsDownloadTaskInfo

/** Which captures have a preview to read: only a single MP4 file, never HLS or an unknown id. */
class PreviewLookupTest {
    @Test
    fun noPreviewWithoutAnMp4File() {
        val tracker = CapturedVideoTracker()
        FakeCaptureHost.install(tracker)
        assertNull(CaptureHandler.loadPreview(424242), "unknown id")
        val hls = HlsDownloadTaskInfo(
            id = 7, fileName = "clip.mp4", tempDir = "/tmp", respectFileName = true, cookie = null, headers = null,
            origin = null, categoryId = null, targetFolder = "/tmp", maxPiece = 8, authInfo = null,
            url = "https://cdn.example.com/master.m3u8", audioUrl = null, audioOnly = false, independent = false,
        )
        tracker.addVideoHls(listOf(hls to StreamingVideoDisplayInfo(tabId = "1")))
        assertNull(CaptureHandler.loadPreview(7), "HLS has no single file")
    }
}
