// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsPlaylistStore
import java.io.File

/**
 * Sites that sign a playlist link for one request: the page's player used it, so XDM's own fetch when the
 * download starts fails. The text the browser received is stored in `<id>.playlists` (HlsPlaylistStore) and
 * the download sets up its segments from it: a finished playlist without a fetch, a live one fetched first
 * with the stored text as the fallback.
 */
class TestHlsCapturedPlaylist : StreamingE2EBase() {

    private var nextId = 7000L

    private fun info(id: Long, caseName: String, url: String) = HlsDownloadTaskInfo(
        id = id, fileName = "$caseName.mp4",
        tempDir = File(root, "$caseName/tmp").absolutePath, respectFileName = false,
        cookie = null, headers = null, origin = null, categoryId = null,
        targetFolder = File(root, "$caseName/out").absolutePath,
        maxPiece = 0, authInfo = null,
        url = url, audioUrl = null, audioOnly = false, independent = false,
    )

    /** TS segments and their playlist text, with or without `#EXT-X-ENDLIST`. The playlist is not written. */
    private fun buildCase(caseName: String, finished: Boolean): String {
        val d = caseDir(caseName)
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25", "-t", "3", "-an",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "seg%d.ts").path, File(d, "gen.m3u8").path
        )
        val gen = File(d, "gen.m3u8")
        val text = gen.readText().let { if (finished) it else it.replace("#EXT-X-ENDLIST", "") }
        gen.delete()
        return text
    }

    private fun run(id: Long, caseName: String, url: String) = host(caseName).also {
        HlsDownloaderTask(info(id, caseName, url), newClient(), muxer(), it, root.absolutePath, config()).start()
    }

    @Test
    fun finishedPlaylist_isTakenFromTheCapture_whenTheLinkNoLongerWorks() {
        requireFfmpeg()
        val text = buildCase("once", finished = true)
        // Not on the server: the link worked only for the page's player.
        val url = url("once/index.m3u8")
        val id = nextId++
        HlsPlaylistStore.save(id, root.absolutePath, mapOf(url to text.toByteArray())).getOrThrow()

        val h = run(id, "once", url)
        awaitSuccess(h)
        verifyOutput(h, expectVideo = true, expectAudio = false, minDuration = 2.0)
    }

    @Test
    fun finishedPlaylist_isNotFetched() {
        requireFfmpeg()
        val text = buildCase("nofetch", finished = true)
        // What the link gives now lists a segment that does not exist: fetching it would fail the download.
        File(caseDir("nofetch"), "index.m3u8").writeText(
            "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1.0,\nmissing.ts\n#EXT-X-ENDLIST\n"
        )
        val url = url("nofetch/index.m3u8")
        val id = nextId++
        HlsPlaylistStore.save(id, root.absolutePath, mapOf(url to text.toByteArray())).getOrThrow()

        awaitSuccess(run(id, "nofetch", url))
    }

    @Test
    fun livePlaylist_isFetched_andTheCaptureIsTheFallback() {
        requireFfmpeg()
        val text = buildCase("live", finished = false)
        val d = caseDir("live")
        val url = url("live/index.m3u8")

        // The link works: its current text is used, not the stored one (which lists a missing segment).
        File(d, "index.m3u8").writeText(text)
        val fetched = nextId++
        HlsPlaylistStore.save(
            fetched, root.absolutePath,
            mapOf(url to "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1.0,\nmissing.ts\n".toByteArray()),
        ).getOrThrow()
        awaitSuccess(run(fetched, "live", url))

        // The link refuses: the stored text is used.
        File(d, "index.m3u8").delete()
        val fallback = nextId++
        HlsPlaylistStore.save(fallback, root.absolutePath, mapOf(url to text.toByteArray())).getOrThrow()
        awaitSuccess(run(fallback, "live-fallback", url))
    }

    @Test
    fun store_roundTrips_andIsEmptyWhenMissing() {
        val dir = root.absolutePath
        assertTrue(HlsPlaylistStore.load(1, dir).isEmpty(), "no file, nothing saved")
        val texts = mapOf("https://a.example/v.m3u8" to "#EXTM3U\nv".toByteArray(), "https://a.example/a.m3u8" to ByteArray(0))
        HlsPlaylistStore.save(1, dir, texts).getOrThrow()
        val loaded = HlsPlaylistStore.load(1, dir)
        assertEquals(texts.keys, loaded.keys, "every playlist read back")
        for ((url, text) in texts) assertArrayEquals(text, loaded[url], "text of $url")
        HlsPlaylistStore.delete(1, dir)
        assertTrue(HlsPlaylistStore.load(1, dir).isEmpty(), "deleted")
    }
}
