// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.impl.NoMediaException
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.media.muxer.impl.UnsupportedContainerException
import java.io.File

/**
 * Containers outside MP4/TS/Matroska/packed audio: FLV (remuxed by the FLV demuxer, also as a list
 * of parts and next to a separate audio track), containers published as they are (a single file,
 * or parts joined when the container allows it), and merges or part lists that must fail with a
 * reason instead of losing a track. Fixtures from ffmpeg; tests skip without it.
 */
class TestContainerFallback {

    companion object {
        private val workDir = File(System.getProperty("java.io.tmpdir"), "xdm-container-fallback")
        private var ffmpeg: String? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            ffmpeg = which("ffmpeg")
            if (ffmpeg == null) return
            workDir.mkdirs()
            val v = arrayOf("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25")
            val a = arrayOf("-f", "lavfi", "-i", "sine=sample_rate=44100")
            val q = arrayOf("-y", "-loglevel", "error")
            // One continuous FLV, then cut into 3 parts without re-encoding (each part has its own header).
            run(ffmpeg!!, *q, *v, *a, "-t", "9", "-g", "25", "-c:v", "libx264", "-bf", "2", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-f", "flv", File(workDir, "whole.flv").path)
            run(ffmpeg!!, *q, "-i", File(workDir, "whole.flv").path, "-c", "copy", "-f", "segment", "-segment_time", "3",
                "-segment_format", "flv", File(workDir, "part%d.flv").path)
            runSoft(ffmpeg!!, *q, *v, *a, "-t", "3", "-g", "25", "-c:v", "libx265", "-c:a", "aac", "-f", "flv", File(workDir, "hevc.flv").path)
            runSoft(ffmpeg!!, *q, *v, *a, "-t", "3", "-g", "25", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "libmp3lame",
                "-f", "flv", File(workDir, "mp3.flv").path)
            run(ffmpeg!!, *q, *v, "-t", "3", "-g", "25", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "flv", File(workDir, "video_only.flv").path)
            run(ffmpeg!!, *q, *a, "-t", "3", "-c:a", "aac", File(workDir, "audio.m4a").path)
            // Sorenson H.263 FLV: a codec the FLV demuxer leaves out.
            run(ffmpeg!!, *q, *v, "-t", "3", "-c:v", "flv1", "-f", "flv", File(workDir, "sorenson.flv").path)
            run(ffmpeg!!, *q, *v, "-t", "3", "-c:v", "flv1", "-f", "flv", File(workDir, "sorenson2.flv").path)
            // Containers published as they are.
            runSoft(ffmpeg!!, *q, "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "6", "-c:a", "libopus",
                "-f", "segment", "-segment_time", "3", "-segment_format", "ogg", File(workDir, "ogg%d.ogg").path)
            run(ffmpeg!!, *q, *v, *a, "-t", "6", "-g", "25", "-c:v", "mpeg2video", "-c:a", "mp2",
                "-f", "segment", "-segment_time", "3", "-segment_format", "mpeg", File(workDir, "ps%d.mpg").path)
            runSoft(ffmpeg!!, *q, *v, *a, "-t", "3", "-c:v", "mpeg4", "-c:a", "libmp3lame", File(workDir, "clip.avi").path)
            run(ffmpeg!!, *q, *v, "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p", File(workDir, "video.mp4").path)
        }

        private fun which(cmd: String): String? {
            val path = System.getenv("PATH") ?: return null
            return path.split(File.pathSeparator).map { File(it, cmd) }.firstOrNull { it.canExecute() }?.absolutePath
        }

        private fun run(vararg cmd: String) {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            check(p.waitFor() == 0) { "command failed: ${cmd.joinToString(" ")}\n$out" }
        }

        private fun runSoft(vararg cmd: String) {
            try {
                val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                p.inputStream.readBytes(); p.waitFor()
            } catch (_: Exception) { /* fixture won't exist */ }
        }
    }

    private fun requireFfmpeg() = Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not on PATH; skipping")
    private fun path(name: String) = File(workDir, name).absolutePath
    private fun fixture(name: String) = File(workDir, name).also { Assumptions.assumeTrue(it.length() > 0, "no $name") }.absolutePath
    private fun files(regex: String) = workDir.listFiles { f -> f.name.matches(Regex(regex)) }!!.sortedBy { it.name }.map { it.absolutePath }

    private fun muxer() = TransmuxingMuxer("/nonexistent-appdir", "mp4")
    private fun mux(list: List<String>, out: String) = muxer().also {
        File(out).delete()
        it.mux(list, out, {}, workDir.path, false, false)
    }

    private fun probe(path: String, vararg args: String): String {
        val ffprobe = which("ffprobe") ?: return ""
        val p = ProcessBuilder(ffprobe, "-v", "error", *args, path).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    private fun codecs(path: String) = probe(path, "-show_entries", "stream=codec_name", "-of", "csv=p=0").lines().filter { it.isNotBlank() }

    private fun decodeErrors(path: String): List<String> {
        val p = ProcessBuilder(ffmpeg!!, "-v", "error", "-i", path, "-map", "0", "-f", "null", "-").redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return text.lines().filter { it.isNotBlank() }
    }

    private fun videoMd5(path: String): String {
        val p = ProcessBuilder(ffmpeg!!, "-v", "error", "-i", path, "-map", "0:v", "-f", "md5", "-").redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    // ---- FLV ----

    @Test
    fun flvPartsAreRemuxedLikeTheWholeFile() {
        requireFfmpeg()
        val parts = files("part\\d+\\.flv")
        assert(parts.size >= 3) { "expected 3 FLV parts, got ${parts.size}" }
        val out = path("parts.mp4")
        val m = mux(parts, out)
        assert(File(out).length() > 0 && m.outputContainer == "mp4") { "mux failed: ${m.lastError}" }
        assertEquals(listOf("h264", "aac"), codecs(out))
        // The same pictures as the uncut file, in order; the parts' timestamps placed back to back.
        assertEquals(videoMd5(path("whole.flv")), videoMd5(out), "video differs from the whole FLV")
        val duration = probe(out, "-show_entries", "format=duration", "-of", "csv=p=0").toDouble()
        assert(duration in 8.9..9.2) { "duration $duration" }
        assertEquals(emptyList<String>(), decodeErrors(out))
    }

    @Test
    fun flvWithHevcAndWithMp3() {
        requireFfmpeg()
        for ((name, want) in listOf("hevc.flv" to listOf("hevc", "aac"), "mp3.flv" to listOf("h264", "mp3"))) {
            val src = File(workDir, name)
            if (src.length() == 0L) continue // encoder missing
            val out = path("$name.mp4")
            val m = mux(listOf(src.absolutePath), out)
            assert(File(out).length() > 0) { "$name: mux failed: ${m.lastError}" }
            assertEquals(want, codecs(out), name)
            assertEquals(videoMd5(src.absolutePath), videoMd5(out), "$name: video differs")
            assertEquals(emptyList<String>(), decodeErrors(out), name)
        }
    }

    @Test
    fun flvVideoMergesWithSeparateAudio() {
        requireFfmpeg()
        val out = path("flv_merge.mp4")
        File(out).delete()
        val m = muxer()
        assert(m.mux(path("video_only.flv"), path("audio.m4a"), out, {}, workDir.path)) { "merge failed: ${m.lastError}" }
        assertEquals(listOf("h264", "aac"), codecs(out))
        assertEquals(emptyList<String>(), decodeErrors(out))
    }

    // ---- published as they are ----

    @Test
    fun aSingleFileNoDemuxerReadsIsPublishedAsItIs() {
        requireFfmpeg()
        for ((name, ext) in listOf("sorenson.flv" to "flv", "clip.avi" to "avi")) {
            val src = File(workDir, name)
            if (src.length() == 0L) continue
            val out = path("asis_$name.out")
            val m = mux(listOf(src.absolutePath), out)
            assertEquals(ext, m.outputContainer, "$name: ${m.lastError}")
            assertArrayEquals(src.readBytes(), File(out).readBytes(), "$name: not published byte for byte")
        }
    }

    @Test
    fun partsOfAJoinableContainerAreJoined() {
        requireFfmpeg()
        for ((regex, ext) in listOf("ogg\\d+\\.ogg" to "ogg", "ps\\d+\\.mpg" to "mpg")) {
            val parts = files(regex)
            if (parts.size < 2) continue
            val out = path("joined.$ext.out")
            val m = mux(parts, out)
            assertEquals(ext, m.outputContainer, "$regex: ${m.lastError}")
            assertArrayEquals(parts.map { File(it).readBytes() }.reduce { x, y -> x + y }, File(out).readBytes(), "$regex: not joined byte for byte")
        }
        // The program stream plays through all parts.
        val ps = path("joined.mpg.out")
        val duration = probe(ps, "-show_entries", "format=duration", "-of", "csv=p=0").toDoubleOrNull() ?: return
        assert(duration > 5.5) { "joined MPEG-PS lasts $duration s" }
    }

    // ---- failing with a reason ----

    @Test
    fun partsThatCantBeJoinedFailWithAReason() {
        requireFfmpeg()
        val out = path("sorenson_parts.out")
        val m = mux(listOf(path("sorenson.flv"), path("sorenson2.flv")), out)
        assert(m.lastError is UnsupportedContainerException) { "lastError: ${m.lastError}" }
        assert(!File(out).exists()) { "an output was left behind" }
    }

    @Test
    fun mergeWithAnUnreadableTrackFailsInsteadOfDroppingIt() {
        requireFfmpeg()
        val ogg = files("ogg\\d+\\.ogg").firstOrNull()
        Assumptions.assumeTrue(ogg != null, "no Opus encoder")
        // Two files.
        val out = path("bad_merge.mp4")
        File(out).delete()
        val m = muxer()
        assert(!m.mux(path("video.mp4"), ogg!!, out, {}, workDir.path)) { "merge with an Ogg track succeeded" }
        assert(m.lastError is UnsupportedContainerException && m.lastError!!.message!!.contains("Ogg")) { "lastError: ${m.lastError}" }
        assert(!File(out).exists()) { "an output was left behind" }
        // Separate audio and video lists.
        val lists = muxer()
        assert(!lists.mux(listOf(ogg), listOf(path("video.mp4")), out, {}, workDir.path, false, false, false)) { "list merge succeeded" }
        assert(lists.lastError is UnsupportedContainerException) { "lastError: ${lists.lastError}" }
        assert(!File(out).exists()) { "an output was left behind" }
    }

    @Test
    fun aSingleUnrecognisedFileStillFails() {
        workDir.mkdirs()
        val page = File(workDir, "page.ts").also { it.writeText("<!DOCTYPE html><html><body>403</body></html>\n".repeat(100)) }
        val out = path("page.out")
        val m = mux(listOf(page.absolutePath), out)
        assert(m.lastError is NoMediaException) { "lastError: ${m.lastError}" }
        assert(!File(out).exists()) { "an output was left behind" }
    }
}
