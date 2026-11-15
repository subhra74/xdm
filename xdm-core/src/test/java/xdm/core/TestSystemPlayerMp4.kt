// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.SegmentInfo
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.storage.LocalStorage
import java.io.File
import java.nio.ByteBuffer

/**
 * What system players need from the MP4 (checked against AVFoundation, Media Foundation and GStreamer
 * with tools/ffparity --native and native/mf_check.ps1):
 *  - HEVC as `hvc1` (AVFoundation refuses `hev1`);
 *  - a sample entry per video config (AVFoundation stops decoding at an in-band resolution change);
 *  - every section opening at a keyframe (AVFoundation stops at a section that opens mid-GOP);
 *  - time zero at the first video frame, audio before it skipped by its edit (Media Foundation
 *    ignores the empty edit that would delay the video, and plays it early), and every track's edit
 *    list opening with a 1 ms empty edit (Media Foundation applies media_time only after one).
 * Fixtures are made with ffmpeg; the tests skip without it.
 */
class TestSystemPlayerMp4 {
    companion object {
        private val workDir = File(System.getProperty("java.io.tmpdir"), "xdm-sysplayer-test")
        private val ffmpeg = which("ffmpeg")

        private fun which(name: String): String? = System.getenv("PATH")?.split(File.pathSeparator)
            ?.map { File(it, name) }?.firstOrNull { it.canExecute() }?.absolutePath

        @BeforeAll
        @JvmStatic
        fun setUp() {
            workDir.deleteRecursively(); workDir.mkdirs()
        }

        private fun run(vararg cmd: String): Boolean {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            return p.waitFor() == 0
        }

        /** TS segments of [seconds] of test picture + tone, cut every second ([splitByTime]: not only at keyframes). */
        private fun hls(prefix: String, size: String, seconds: Int, vararg codec: String, splitByTime: Boolean = false): List<String> {
            val ok = run(ffmpeg!!, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=$size:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "$seconds", *codec, "-c:a", "aac",
                "-f", "hls", "-hls_time", "1", "-hls_list_size", "0", *(if (splitByTime) arrayOf("-hls_flags", "split_by_time") else arrayOf()),
                "-hls_segment_filename", File(workDir, "$prefix%d.ts").path, File(workDir, "$prefix.m3u8").path)
            Assumptions.assumeTrue(ok, "ffmpeg couldn't make $prefix (${codec.joinToString(" ")})")
            return workDir.listFiles { f -> f.name.matches(Regex("$prefix\\d+\\.ts")) }!!
                .sortedBy { it.name.removePrefix(prefix).removeSuffix(".ts").toInt() }.map { it.absolutePath }
        }

        private fun mux(segments: List<String>, sections: List<Int>?, name: String): File {
            val out = File(workDir, name)
            val m = TransmuxingMuxer(workDir.path, null)
            if (sections != null) m.setSegmentInfo(segments.indices.associate { segments[it] to SegmentInfo(0.0, sections[it], false) })
            assertTrue(m.mux(segments, LocalStorage.file(out.path), {}, workDir.path, false, false, sections != null), "mux failed")
            return out
        }
    }

    private fun requireFfmpeg() = Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not on PATH; skipping")

    // ---- a minimal reader of the boxes these tests look at ----

    private class Box(val type: String, val data: ByteBuffer, val start: Int, val end: Int) {
        fun children(skip: Int = 0): List<Box> {
            val out = ArrayList<Box>()
            var p = start + skip
            while (p + 8 <= end) {
                val size = data.getInt(p).toLong() and 0xFFFFFFFFL
                val t = String(ByteArray(4) { data.get(p + 4 + it) }, Charsets.US_ASCII)
                val (h, len) = if (size == 1L) 16 to data.getLong(p + 8).toInt() else 8 to size.toInt()
                if (len < h) break
                out.add(Box(t, data, p + h, p + len))
                p += len
            }
            return out
        }
        fun child(t: String) = children().first { it.type == t }
        fun u32(at: Int) = data.getInt(start + at).toLong() and 0xFFFFFFFFL
    }

    private class VideoTrack(val trak: Box) {
        val stbl = trak.child("mdia").child("minf").child("stbl")
        private val stsd = stbl.child("stsd")
        /** (fourcc, width, height) of each sample entry. */
        val entries = stsd.children(8).map { Triple(it.type, it.data.getShort(it.start + 24).toInt() and 0xFFFF, it.data.getShort(it.start + 26).toInt() and 0xFFFF) }
        val sampleCount = stbl.child("stsz").u32(8).toInt()
        /** stsc runs: (first chunk, description index); one sample per chunk in our files. */
        val descriptionRuns = stbl.child("stsc").let { b -> (0 until b.u32(4).toInt()).map { b.u32(8 + it * 12).toInt() to b.u32(16 + it * 12).toInt() } }
        val syncSamples = stbl.children().firstOrNull { it.type == "stss" }?.let { b -> (0 until b.u32(4).toInt()).map { b.u32(8 + it * 4).toInt() } }
        /** elst entries (segment duration, media time). */
        val edits = trak.child("edts").child("elst").let { b ->
            val v = b.data.get(b.start).toInt()
            (0 until b.u32(4).toInt()).map {
                if (v == 1) b.data.getLong(b.start + 8 + it * 20) to b.data.getLong(b.start + 16 + it * 20)
                else b.u32(8 + it * 12) to b.data.getInt(b.start + 12 + it * 12).toLong()
            }
        }
        val tkhdWidth = trak.child("tkhd").let { it.data.getInt(it.end - 8) ushr 16 }
    }

    private fun tracks(f: File): List<Pair<String, VideoTrack>> {
        val buf = ByteBuffer.wrap(f.readBytes())
        val moov = Box("file", buf, 0, buf.capacity()).children().first { it.type == "moov" }
        return moov.children().filter { it.type == "trak" }.map { trak ->
            val hdlr = trak.child("mdia").child("hdlr")
            String(ByteArray(4) { buf.get(hdlr.start + 8 + it) }, Charsets.US_ASCII) to VideoTrack(trak)
        }
    }

    private fun video(f: File) = tracks(f).first { it.first == "vide" }.second

    private fun keyframeFlags(ts: String): List<Boolean> {
        val p = ProcessBuilder(which("ffprobe") ?: return emptyList(), "-v", "error", "-select_streams", "v",
            "-show_entries", "packet=flags", "-of", "csv=p=0", ts).start()
        val lines = p.inputStream.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        p.waitFor()
        return lines.map { it.startsWith("K") }
    }

    @Test
    fun resolutionChangeGetsASampleEntryOfItsOwn() {
        requireFfmpeg()
        val big = hls("res_a", "640x360", 2, "-c:v", "libx264", "-profile:v", "high", "-g", "25")
        val small = hls("res_b", "320x180", 2, "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "25")
        val out = mux(big + small, big.map { 0 } + small.map { 1 }, "res_change.mp4")
        val v = video(out)
        assertEquals(listOf(Triple("avc1", 640, 360), Triple("avc1", 320, 180)), v.entries, "one avc1 entry per resolution")
        assertEquals(640, v.tkhdWidth, "the track's display size is the first config's")
        assertEquals(2, v.descriptionRuns.size, "samples switch entry once")
        assertEquals(1, v.descriptionRuns[0].second, "the first samples use entry 1")
        assertEquals(2, v.descriptionRuns[1].second, "the 320x180 samples use entry 2")
        assertEquals(100, v.sampleCount, "every frame kept")
        assertEquals(51, v.descriptionRuns[1].first, "the switch is at the first 320x180 frame")
    }

    @Test
    fun aSectionThatOpensMidGopStartsAtItsFirstKeyframe() {
        requireFfmpeg()
        val first = hls("sec_a", "320x240", 2, "-c:v", "libx264", "-g", "25")
        // GOP of 75 frames cut every second: its second segment opens mid-GOP.
        val second = hls("sec_b", "320x240", 6, "-c:v", "libx264", "-g", "75", "-bf", "0", splitByTime = true).drop(1)
        val flags = second.flatMap { keyframeFlags(it) }
        Assumptions.assumeTrue(flags.isNotEmpty() && !flags.first(), "fixture doesn't open mid-GOP")
        val kept = flags.size - flags.indexOf(true)
        val out = mux(first + second, first.map { 0 } + second.map { 1 }, "midgop_section.mp4")
        val v = video(out)
        assertEquals(50 + kept, v.sampleCount, "frames of the second section before its first keyframe dropped")
        assertTrue(v.syncSamples!!.contains(51), "the second section opens with a sync sample")
    }

    @Test
    fun hevcIsWrittenAsHvc1() {
        requireFfmpeg()
        val segs = hls("hevc", "320x240", 2, "-c:v", "libx265", "-x265-params", "log-level=error:keyint=25", "-pix_fmt", "yuv420p")
        val v = video(mux(segs, null, "hevc.mp4"))
        assertEquals("hvc1", v.entries.single().first, "hvc1, not hev1")
    }

    @Test
    fun outputStartsAtTheFirstVideoFrame() {
        requireFfmpeg()
        // B-frames delay the first presented frame; the AAC starts earlier.
        val segs = hls("zero", "320x240", 3, "-c:v", "libx264", "-bf", "3", "-g", "25")
        val all = tracks(mux(segs, null, "zero.mp4"))
        val v = all.first { it.first == "vide" }.second
        val a = all.first { it.first == "soun" }.second
        // Each track: a 1 ms empty edit (Media Foundation applies media_time only after one), then its media.
        assertEquals(1L to -1L, v.edits[0], "the video opens with the 1 ms empty edit only: ${v.edits}")
        assertEquals(1L to -1L, a.edits[0], "the audio opens with the same: ${a.edits}")
        assertEquals(2, v.edits.size, "video: empty edit + media: ${v.edits}")
        assertTrue(v.edits[1].second > 0, "the video's edit skips its B-frame delay: ${v.edits}")
        assertTrue(a.edits[1].second > 0, "the audio before the first video frame is skipped: ${a.edits}")
    }
}
