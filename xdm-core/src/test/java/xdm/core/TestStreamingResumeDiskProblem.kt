package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HLS/DASH segments live in the temp folder until they are muxed. If that folder is gone when a paused
 * download resumes, the download must fail with a disk error and keep its state, not silently
 * download every segment again.
 */
class TestStreamingResumeDiskProblem : StreamingE2EBase() {

    private fun task(id: Long, url: String): Pair<HlsDownloaderTask, StreamingTestHost> {
        val h = host("resume-$id")
        val info = HlsDownloadTaskInfo(
            id = id, fileName = "v.mp4",
            tempDir = File(root, "resume-$id/tmp").absolutePath, respectFileName = false,
            cookie = null, headers = null, origin = null, categoryId = null,
            targetFolder = File(root, "resume-$id/out").absolutePath, maxPiece = 0, authInfo = null,
            url = url, audioUrl = null, audioOnly = false, independent = false,
        )
        return HlsDownloaderTask(info, newClient(), muxer(), h, root.absolutePath, config(2)) to h
    }

    @Test
    fun tempSegmentsGoneWhilePaused_failsWithDiskErrorAndKeepsState() {
        val server = MockHttpServer()
        try {
            val playlist = buildString {
                append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n")
                repeat(10) { append("#EXTINF:1.0,\n").append(server.url("/s%02d.ts".format(it))).append('\n') }
                append("#EXT-X-ENDLIST\n")
            }
            server.register("/index.m3u8", Endpoint(playlist.toByteArray()))
            repeat(10) {
                // The first two finish at once; the rest are slow, so the pause catches them mid-way.
                val slow = it >= 2
                server.register("/s%02d.ts".format(it), Endpoint(ByteArray(64 * 1024), plan = {
                    if (slow) ConnPlan(throttleChunk = 4 * 1024, throttleSleepMs = 100) else ConnPlan()
                }))
            }

            val (first, h1) = task(7001, server.url("/index.m3u8"))
            first.start()
            Thread.sleep(1500)
            first.stop()
            assertTrue(h1.latch.await(10, TimeUnit.SECONDS), "pause not acknowledged")

            val temp = File(root, "resume-7001/tmp")
            assertTrue(temp.isDirectory, "no temp segment folder after the pause")
            assertTrue(temp.deleteRecursively(), "could not delete the temp segment folder")

            val (second, h2) = task(7001, server.url("/index.m3u8"))
            second.resume()
            assertEquals(DownloadError.DiskSpaceError, awaitFailure(h2, 15), "missing segments must not start over")
            assertTrue(File(root, "7001.state").isFile, "the saved state must be kept")
        } finally {
            server.stop()
        }
    }
}
