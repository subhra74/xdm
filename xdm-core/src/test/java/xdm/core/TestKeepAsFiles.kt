// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import xdm.core.downloaders.AssemblyNote
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.media.muxer.impl.TransmuxingMuxer
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The downloader's own fallbacks, after the muxer's one-file fallbacks: the download kept as a
 * playlist folder (HLS-playable segments) or as files side by side, never lost; a playlist folder
 * that wasn't published is published by the next start. Fixtures from ffmpeg; tests skip without it.
 */
class TestKeepAsFiles : StreamingE2EBase() {

    private val ffmpeg: String? = System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, "ffmpeg") }?.firstOrNull { it.canExecute() }?.path

    private fun needFfmpeg() = Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not on PATH; skipping")

    private fun run(vararg args: String) {
        val p = ProcessBuilder(ffmpeg!!, "-y", "-loglevel", "error", *args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { out }
    }

    private fun failingMuxer() = TransmuxingMuxer(root.absolutePath, "mp4").also {
        it.failRemuxForTest = true
        it.failOneFileFallbacksForTest = true
    }

    private fun task(case: String, id: Long, video: String, audio: String?, muxer: TransmuxingMuxer, host: StreamingTestHost) = HlsDownloaderTask(
        HlsDownloadTaskInfo(
            id = id, fileName = "v.mp4", tempDir = File(root, "$case/tmp").absolutePath, respectFileName = false,
            cookie = null, headers = null, origin = null, categoryId = null, targetFolder = File(root, "$case/out").absolutePath,
            maxPiece = 0, authInfo = null, url = url(video), audioUrl = audio?.let { url(it) }, audioOnly = false, independent = false,
        ), newClient(), muxer, host, root.absolutePath, config(2),
    )

    private fun await(h: StreamingTestHost) = assertTrue(h.latch.await(60, TimeUnit.SECONDS), "timed out")

    private fun probe(path: String, vararg args: String): String {
        val p = ProcessBuilder("ffprobe", "-v", "error", *args, path).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    /** Frames per stream type ffmpeg decodes from [path] (a playlist is read through its segments). */
    private fun frames(path: String): Map<String, Int> =
        probe(path, "-count_frames", "-show_entries", "stream=index,codec_type,nb_read_frames", "-of", "csv=p=0")
            .lines().filter { it.count { c -> c == ',' } == 2 }.map { it.split(",") }.distinctBy { it[0] }
            .associate { it[1] to it[2].toInt() }

    @Test
    fun separateRenditionsBecomeAPlaylistFolder() {
        needFfmpeg()
        val dir = caseDir("folder")
        run("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "6",
            "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25", "-c:a", "aac",
            "-f", "hls", "-hls_time", "2", "-hls_list_size", "0", "-var_stream_map", "v:0 a:0",
            "-hls_segment_filename", File(dir, "r%v_%d.ts").path, File(dir, "r%v.m3u8").path)
        val h = host("folder")
        task("folder", 9101, "folder/r0.m3u8", "folder/r1.m3u8", failingMuxer(), h).start()
        await(h)
        assertNull(h.failure, "failed: ${h.failure}")
        val success = requireNotNull(h.success)
        assertEquals(AssemblyNote.PLAYLIST_FOLDER, success.note)
        assertEquals("out-9101/out-9101.m3u8", success.finalFileName)
        val folder = requireNotNull(h.folderOutput)
        val master = File(folder, "out-9101.m3u8").readText()
        assertTrue("#EXT-X-MEDIA:TYPE=AUDIO" in master && "video.m3u8" in master, master)
        assertTrue(File(folder, "video.m3u8").readText().contains("#EXT-X-ENDLIST"))
        // ffmpeg plays the folder: every frame of both renditions.
        val got = frames(File(folder, "out-9101.m3u8").path)
        assertEquals(150, got["video"] ?: -1, "video frames $got")
        assertTrue((got["audio"] ?: 0) > 250, "audio frames $got")
        assertTrue(File(root, "folder/tmp").listFiles()?.none { it.name.endsWith(".ts") } ?: true, "segments left in the temp folder")
    }

    @Test
    fun fmp4WithItsInitSegmentBecomesAPlaylistFolder() {
        needFfmpeg()
        val dir = caseDir("fmp4")
        run("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "6",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25", "-c:a", "aac",
            "-f", "hls", "-hls_time", "2", "-hls_list_size", "0", "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
            "-hls_segment_filename", File(dir, "f%d.m4s").path, File(dir, "f.m3u8").path)
        val h = host("fmp4")
        task("fmp4", 9102, "fmp4/f.m3u8", null, failingMuxer(), h).start()
        await(h)
        assertEquals(AssemblyNote.PLAYLIST_FOLDER, requireNotNull(h.success) { "failed: ${h.failure}" }.note)
        val playlist = File(requireNotNull(h.folderOutput), "out-9102.m3u8")
        assertTrue(playlist.readText().contains("#EXT-X-MAP:URI="), playlist.readText())
        assertEquals(150, frames(playlist.path)["video"] ?: -1)
    }

    @Test
    fun anUnpublishedPlaylistFolderIsPublishedByTheNextStart() {
        needFfmpeg()
        val dir = caseDir("restart")
        run("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-t", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25",
            "-f", "hls", "-hls_time", "2", "-hls_list_size", "0", "-hls_segment_filename", File(dir, "s%d.ts").path, File(dir, "s.m3u8").path)
        val first = host("restart").also { it.failFolderCommits.set(1) }
        task("restart", 9103, "restart/s.m3u8", null, failingMuxer(), first).start()
        await(first)
        assertEquals(DownloadError.OutputWriteError, first.failure)
        assertTrue(File(root, "restart/tmp").walk().any { it.name == "index.m3u8" }, "the built folder must stay")

        val second = host("restart")
        task("restart", 9103, "restart/s.m3u8", null, failingMuxer(), second).resume()
        await(second)
        assertEquals(AssemblyNote.PLAYLIST_FOLDER, requireNotNull(second.success) { "failed: ${second.failure}" }.note)
        assertEquals(100, frames(File(requireNotNull(second.folderOutput), "out-9103.m3u8").path)["video"] ?: -1)
    }

    @Test
    fun partsNoPlaylistCanCarryArePublishedSideBySide() {
        needFfmpeg()
        val dir = caseDir("side")
        // Sorenson H.263 FLV: no demuxer reads it, FLV parts can't be joined, and HLS can't carry FLV.
        for (i in 0..1) run("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-t", "2", "-c:v", "flv1", "-f", "flv", File(dir, "p$i.flv").path)
        File(dir, "p.m3u8").writeText("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\np0.flv\n#EXTINF:2.0,\np1.flv\n#EXT-X-ENDLIST\n")
        val h = host("side")
        task("side", 9104, "side/p.m3u8", null, TransmuxingMuxer(root.absolutePath, "mp4"), h).start()
        await(h)
        val success = requireNotNull(h.success) { "failed: ${h.failure}" }
        assertEquals(AssemblyNote.SEPARATE_FILES, success.note)
        assertArrayEquals(File(dir, "p0.flv").readBytes(), requireNotNull(h.finalFile).readBytes())
        assertEquals(listOf("out-9104.part2.flv"), h.sidecars.map { it.name })
        assertArrayEquals(File(dir, "p1.flv").readBytes(), h.sidecars[0].readBytes())
        assertEquals("out-9104.part2.flv", success.noteDetail)
    }
}
