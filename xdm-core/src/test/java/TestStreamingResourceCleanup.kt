import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.web.streaming.downloader.StreamingDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Regression for CODE_REVIEW B10 (streaming side):
 * - the per-task segment thread pool must be shut down when the download ends, not only on Pause;
 * - the per-task HTTP client must be closed on success, failure and Pause;
 * - after Pause the download thread must not stay parked on the segment latch forever (segments
 *   still queued when the pool is shut down never count down).
 */
class TestStreamingResourceCleanup : StreamingE2EBase() {

    private var nextId = 3000L

    private fun hlsTask(caseName: String, url: String, client: CountingHttpClient, maxSegments: Int): Pair<HlsDownloaderTask, StreamingTestHost> {
        val h = host(caseName)
        val info = HlsDownloadTaskInfo(
            id = nextId++, fileName = "$caseName.mp4",
            tempDir = File(root, "$caseName/tmp").absolutePath, respectFileName = false,
            cookie = null, headers = null, origin = null, autoCategorize = false,
            defaultDownloadFolder = File(root, "$caseName/out").absolutePath,
            userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null,
            url = url, audioUrl = null, audioOnly = false, independent = false,
        )
        return HlsDownloaderTask(info, client, muxer(), h, root.absolutePath, config(maxSegments)) to h
    }

    private fun executorOf(task: StreamingDownloaderTask): ExecutorService =
        StreamingDownloaderTask::class.java.getDeclaredField("executorService")
            .apply { isAccessible = true }.get(task) as ExecutorService

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(30)
        }
        return cond()
    }

    private fun downloadThreads(): List<Thread> =
        Thread.getAllStackTraces().entries
            .filter { (_, st) -> st.any { it.methodName == "downloadChunks" } }
            .map { it.key }

    @Test
    fun success_shutsDownPoolAndClosesClient() {
        requireFfmpeg()
        val d = caseDir("ok")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25", "-t", "3",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25",
            "-f", "hls", "-hls_time", "1", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "seg%d.ts").path, File(d, "index.m3u8").path
        )
        val client = CountingHttpClient()
        val (task, h) = hlsTask("ok", url("ok/index.m3u8"), client, maxSegments = 4)
        task.start()
        awaitSuccess(h)

        assertTrue("segment pool still running after success", waitFor(3000) { executorOf(task).isShutdown })
        assertTrue("HTTP client not closed after success", waitFor(3000) { client.closeCount.get() >= 1 })
    }

    @Test
    fun failure_shutsDownPoolAndClosesClient() {
        caseDir("fail")
        val client = CountingHttpClient()
        val (task, h) = hlsTask("fail", url("fail/missing.m3u8"), client, maxSegments = 4)
        task.start()
        awaitFailure(h, 30)

        assertTrue("segment pool still running after failure", waitFor(3000) { executorOf(task).isShutdown })
        assertTrue("HTTP client not closed after failure", waitFor(3000) { client.closeCount.get() >= 1 })
    }

    @Test
    fun pause_withQueuedSegments_downloadThreadExitsAndClientCloses() {
        // 20 slow segments on a 2-thread pool: 18 are still queued when Pause shuts the pool down.
        val slow = MockHttpServer()
        try {
            val segCount = 20
            val playlist = buildString {
                append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n")
                repeat(segCount) { append("#EXTINF:1.0,\n").append(slow.url("/s%02d.ts".format(it))).append('\n') }
                append("#EXT-X-ENDLIST\n")
            }
            slow.register("/index.m3u8", Endpoint(playlist.toByteArray()))
            repeat(segCount) {
                slow.register("/s%02d.ts".format(it), Endpoint(ByteArray(256 * 1024), plan = {
                    ConnPlan(throttleChunk = 4 * 1024, throttleSleepMs = 50)
                }))
            }

            val client = CountingHttpClient()
            val (task, h) = hlsTask("pause", slow.url("/index.m3u8"), client, maxSegments = 2)
            task.start()
            assertTrue("segments never started", waitFor(10_000) { h.initInfo != null && downloadThreads().isNotEmpty() })

            task.stop()
            assertTrue("pause not acknowledged", h.latch.await(10, TimeUnit.SECONDS))
            assertTrue(
                "download thread still parked on the segment latch after Pause",
                waitFor(3000) { downloadThreads().isEmpty() }
            )
            assertTrue("HTTP client not closed after pause", waitFor(3000) { client.closeCount.get() >= 1 })
        } finally {
            slow.stop()
        }
    }
}
