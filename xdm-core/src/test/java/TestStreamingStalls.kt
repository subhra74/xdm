import org.junit.Assert.assertEquals
import org.junit.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import java.io.File

/**
 * Regression for CODE_REVIEW B11 (HLS/DASH): a segment whose connection stalls must time out and be
 * retried, and a segment that never arrives must fail the download after `maxRetries` attempts
 * without progress instead of retrying forever.
 */
class TestStreamingStalls : StreamingE2EBase() {

    /** Generates real TS segments and serves them from a [MockHttpServer] with [plan] per segment index. */
    private fun serveSegments(caseName: String, slow: MockHttpServer, plan: (segment: Int, ReqCtx) -> ConnPlan): String {
        val d = caseDir(caseName)
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25", "-t", "3", "-an",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "seg%d.ts").path, File(d, "index.m3u8").path
        )
        val segs = d.listFiles { f -> f.name.matches(Regex("seg\\d+\\.ts")) }!!.sortedBy { it.name }
        val playlist = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n")
        segs.forEachIndexed { i, seg ->
            val path = "/$caseName/s$i.ts"
            slow.register(path, Endpoint(seg.readBytes(), plan = { ctx -> plan(i, ctx) }))
            playlist.append("#EXTINF:1.0,\n").append(slow.url(path)).append('\n')
        }
        playlist.append("#EXT-X-ENDLIST\n")
        slow.register("/$caseName/index.m3u8", Endpoint(playlist.toString().toByteArray()))
        return slow.url("/$caseName/index.m3u8")
    }

    private fun start(caseName: String, url: String, maxRetries: Int): StreamingTestHost {
        val h = host(caseName)
        val info = HlsDownloadTaskInfo(
            id = System.nanoTime(), fileName = "$caseName.mp4", tempDir = File(root, "$caseName/tmp").absolutePath,
            respectFileName = false, cookie = null, headers = null, origin = null, autoCategorize = false,
            defaultDownloadFolder = File(root, "$caseName/out").absolutePath, userSelectedDownloadFolder = null,
            maxPiece = 0, authInfo = null, url = url, audioUrl = null, audioOnly = false, independent = false,
        )
        HlsDownloaderTask(
            info, CountingHttpClient(httpClientWithReadTimeout(1)), muxer(), h, root.absolutePath,
            E2EConfig(maxSegments = 2, maxRetries = maxRetries)
        ).start()
        return h
    }

    @Test
    fun stalledSegment_timesOutAndIsRetried() {
        requireFfmpeg()
        val slow = MockHttpServer()
        try {
            val url = serveSegments("once", slow) { seg, ctx ->
                if (seg == 1 && ctx.connIndex == 1) ConnPlan(stallAfterBytes = 1024, stallMs = -1) else ConnPlan()
            }
            val h = start("once", url, maxRetries = 3)
            awaitSuccess(h, 40)
            verifyOutput(h, expectVideo = true, expectAudio = false, minDuration = 2.0)
        } finally {
            slow.stop()
        }
    }

    @Test
    fun segmentThatNeverArrives_failsAfterMaxRetries() {
        requireFfmpeg()
        val slow = MockHttpServer()
        try {
            val url = serveSegments("never", slow) { seg, _ ->
                if (seg == 1) ConnPlan(stallAfterBytes = 0, stallMs = -1) else ConnPlan()
            }
            val h = start("never", url, maxRetries = 1)
            assertEquals(DownloadError.NetworkError, awaitFailure(h, 40))
        } finally {
            slow.stop()
        }
    }
}
