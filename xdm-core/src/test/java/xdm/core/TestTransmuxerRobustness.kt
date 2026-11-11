// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.iso.Mp4Demuxer
import xdm.core.media.muxer.transmux.mkv.MatroskaDemuxer
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Damaged and unusual inputs to the transmuxer: segments cut short, Matroska `Tracks` repeated,
 * negative composition offsets in version-0 boxes, several sample entries in one `stsd`, and a
 * segment list that switches container (a TS insert inside an fMP4 stream). Fixtures come from
 * ffmpeg; every test is skipped when it isn't on the PATH.
 */
class TestTransmuxerRobustness {

    companion object {
        private val workDir = File(System.getProperty("java.io.tmpdir"), "xdm-transmux-robust")
        private var ffmpeg: String? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            ffmpeg = which("ffmpeg")
            if (ffmpeg == null) return
            workDir.mkdirs()
            val av = arrayOf("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100")
            val h264 = arrayOf("-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-g", "25",
                "-c:a", "aac", "-ar", "44100", "-ac", "2")
            // 3 s fMP4 (init + 1 s media segments) and the same as TS segments, for the mixed list.
            run(ffmpeg!!, "-y", "-loglevel", "error", *av, "-t", "3", *h264,
                "-f", "hls", "-hls_time", "1", "-hls_segment_type", "fmp4", "-hls_list_size", "0",
                "-hls_fmp4_init_filename", "fa_init.mp4",
                "-hls_segment_filename", File(workDir, "fa%d.m4s").path, File(workDir, "fa.m3u8").path)
            run(ffmpeg!!, "-y", "-loglevel", "error", *av, "-t", "3", *h264,
                "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
                "-hls_segment_filename", File(workDir, "ts%d.ts").path, File(workDir, "ts.m3u8").path)
            // A second fMP4 rendition at another size: its avc1 entry goes into a two-entry stsd.
            run(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100", "-t", "2", *h264,
                "-f", "hls", "-hls_time", "1", "-hls_segment_type", "fmp4", "-hls_list_size", "0",
                "-hls_fmp4_init_filename", "fb_init.mp4",
                "-hls_segment_filename", File(workDir, "fb%d.m4s").path, File(workDir, "fb.m3u8").path)
            // Progressive MP4, moov first.
            run(ffmpeg!!, "-y", "-loglevel", "error", *av, "-t", "2", *h264, "-movflags", "+faststart",
                File(workDir, "whole.mp4").path)
            // B-frames with negative composition offsets: fragmented (trun v1) and progressive (ctts v1).
            val bf = arrayOf("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-t", "2", "-an",
                "-c:v", "libx264", "-profile:v", "main", "-bf", "2", "-pix_fmt", "yuv420p", "-g", "25")
            run(ffmpeg!!, "-y", "-loglevel", "error", *bf,
                "-movflags", "frag_keyframe+empty_moov+negative_cts_offsets", File(workDir, "neg_frag.mp4").path)
            run(ffmpeg!!, "-y", "-loglevel", "error", *bf,
                "-movflags", "+negative_cts_offsets", File(workDir, "neg_prog.mp4").path)
            // Rotated video: 90°, and 90° after a horizontal flip; progressive and fragmented MP4, and
            // ffmpeg's own MKV of each (the reference for our MKV output).
            val whole = File(workDir, "whole.mp4").path
            run(ffmpeg!!, "-y", "-loglevel", "error", "-display_rotation", "90", "-i", whole, "-c", "copy", File(workDir, "rot90.mp4").path)
            run(ffmpeg!!, "-y", "-loglevel", "error", "-display_rotation", "90", "-display_hflip", "-i", whole, "-c", "copy",
                File(workDir, "rot90f.mp4").path)
            run(ffmpeg!!, "-y", "-loglevel", "error", "-display_rotation", "90", "-i", whole, "-c", "copy",
                "-movflags", "frag_keyframe+empty_moov", File(workDir, "rot90_frag.mp4").path)
            for (name in listOf("rot90", "rot90f")) {
                run(ffmpeg!!, "-y", "-loglevel", "error", "-i", File(workDir, "$name.mp4").path, "-c", "copy", File(workDir, "$name.mkv").path)
            }
            // WebM (encoders may be missing: those tests skip).
            runSoft(ffmpeg!!, "-y", "-loglevel", "error", *av, "-t", "2",
                "-c:v", "libvpx-vp9", "-b:v", "300k", "-g", "25", "-pix_fmt", "yuv420p",
                "-c:a", "libopus", "-ar", "48000", "-ac", "2", File(workDir, "whole.webm").path)
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

        private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        private fun u32(b: ByteArray, o: Int) =
            ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    }

    private fun requireFfmpeg() = Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not on PATH; skipping")
    private fun path(name: String) = File(workDir, name).absolutePath
    private fun files(regex: String) = workDir.listFiles { f -> f.name.matches(Regex(regex)) }
        ?.sortedBy { it.name }?.map { it.absolutePath } ?: emptyList()

    /** A copy of [src] holding only its first [fraction] of bytes, like a download that stopped early. */
    private fun cut(src: String, name: String, fraction: Double): String {
        val bytes = File(src).readBytes()
        return File(workDir, name).also { it.writeBytes(bytes.copyOf((bytes.size * fraction).toInt())) }.absolutePath
    }

    /** ffprobe's packet count per stream type, e.g. {video=75, audio=130}. */
    private fun packetCounts(path: String): Map<String, Int> {
        val ffprobe = which("ffprobe") ?: return emptyMap()
        val p = ProcessBuilder(ffprobe, "-v", "error", "-count_packets",
            "-show_entries", "stream=codec_type,nb_read_packets", "-of", "csv=p=0", path).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return text.lines().map { it.trim() }.filter { it.contains(',') }.associate { it.split(",").let { (t, n) -> t to n.toInt() } }
    }

    private fun decodeErrors(path: String): List<String> {
        val p = ProcessBuilder(ffmpeg!!, "-v", "error", "-i", path, "-map", "0", "-f", "null", "-")
            .redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return text.lines().filter { it.isNotBlank() }
    }

    private fun mux(list: List<String>, out: String, discontinuous: Boolean = false): TransmuxingMuxer =
        TransmuxingMuxer("/nonexistent-appdir").also { m ->
            assert(m.mux(list, out, {}, workDir.path, false, true, discontinuous)) { "mux returned false: ${m.lastError}" }
        }

    /** A sink that only counts bytes: the demuxer tests look at the sample tables. */
    private class CountingSink : SampleSink {
        var pos = 0L
        override fun writeSampleData(data: ByteArray, offset: Int, length: Int): Long = pos.also { pos += length }
    }

    // ---- truncated segments ----

    @Test
    fun truncatedFragmentedSegmentKeepsTheRest() {
        requireFfmpeg()
        val media = files("fa\\d+\\.m4s")
        Assumptions.assumeTrue(media.size == 3, "fixtures")
        val whole = path("fa_whole_out.mp4")
        mux(listOf(path("fa_init.mp4")) + media, whole)
        val list = listOf(path("fa_init.mp4"), media[0], media[1], cut(media[2], "fa_cut.m4s", 0.5))
        val out = path("fa_cut_out.mp4")
        mux(list, out)
        val full = packetCounts(whole)
        val got = packetCounts(out)
        if (full.isNotEmpty()) {
            // The samples whose data is in the first half of the last segment are kept, the rest dropped.
            assert(got.values.sum() < full.values.sum()) { "nothing dropped: $got of $full" }
            assert(got.values.sum() > full.values.sum() * 2 / 3) { "too much dropped: $got of $full" }
        }
        val errors = decodeErrors(out)
        assert(errors.isEmpty()) { "decode errors: ${errors.take(5)}" }
    }

    @Test
    fun truncatedProgressiveMp4KeepsTheSamplesWithData() {
        requireFfmpeg()
        val out = path("whole_cut_out.mp4")
        mux(listOf(cut(path("whole.mp4"), "whole_cut.mp4", 0.6)), out)
        val video = packetCounts(out)["video"] ?: return
        assert(video in 1..49) { "expected part of the 50 frames, got $video" }
        val errors = decodeErrors(out)
        assert(errors.isEmpty()) { "decode errors: ${errors.take(5)}" }
    }

    @Test
    fun truncatedWebmDropsTheCutFrame() {
        requireFfmpeg()
        val webm = File(workDir, "whole.webm")
        Assumptions.assumeTrue(webm.exists() && webm.length() > 0, "no VP9/Opus encoder")
        val out = path("webm_cut_out.mkv")
        mux(listOf(cut(webm.absolutePath, "webm_cut.webm", 0.6)), out)
        val video = packetCounts(out)["video"] ?: return
        assert(video in 1..49) { "expected part of the 50 frames, got $video" }
        val errors = decodeErrors(out)
        assert(errors.isEmpty()) { "decode errors: ${errors.take(5)}" }
    }

    // ---- repeated Matroska Tracks ----

    @Test
    fun repeatedMatroskaTracksContinueTheTrack() {
        requireFfmpeg()
        val webm = File(workDir, "whole.webm")
        Assumptions.assumeTrue(webm.exists() && webm.length() > 0, "no VP9/Opus encoder")
        fun counts(paths: List<String>) = MatroskaDemuxer(CountingSink()).let { d ->
            paths.forEach { d.parseSegment(it) }; d.tracks.map { it.samples.size }
        }
        val once = counts(listOf(webm.absolutePath))
        // The same stream again: a new EBML header, Segment and Tracks, as when a DASH WebM
        // representation repeats its init segment.
        val twice = counts(listOf(webm.absolutePath, webm.absolutePath))
        assert(twice == once.map { it * 2 }) { "tracks once $once, twice $twice" }
    }

    // ---- signed composition offsets ----

    /** A copy of [src] with every [box] (a full box) set to version 0. */
    private fun asVersion0(src: String, box: String, name: String): String {
        val b = File(src).readBytes()
        val tag = box.toByteArray(Charsets.US_ASCII)
        var n = 0
        for (i in 4 until b.size - 4) {
            if (b[i] == tag[0] && b[i + 1] == tag[1] && b[i + 2] == tag[2] && b[i + 3] == tag[3] && b[i + 4].toInt() == 1) {
                b[i + 4] = 0; n++
            }
        }
        assert(n > 0) { "no version-1 $box in $src" }
        return File(workDir, name).also { it.writeBytes(b) }.absolutePath
    }

    private fun ptsDts(path: String): List<Pair<Long, Long>> = Mp4Demuxer(CountingSink()).let { d ->
        d.parseSegment(path)
        val s = d.tracks.single().samples
        (0 until s.size).map { s.pts(it) to s.dts(it) }
    }

    @Test
    fun negativeOffsetsInVersion0BoxesAreReadSigned() {
        requireFfmpeg()
        for ((src, box) in listOf("neg_frag.mp4" to "trun", "neg_prog.mp4" to "ctts")) {
            val v1 = ptsDts(path(src))
            assert(v1.any { it.first < it.second }) { "$src: fixture has no negative composition offset" }
            val v0 = ptsDts(asVersion0(path(src), box, "v0_$src"))
            assert(v0 == v1) { "$src: $box version 0 read differently from version 1" }
        }
    }

    // ---- several sample entries ----

    private val containerBoxes = setOf("moov", "trak", "mdia", "minf", "stbl", "mvex")

    /** Rebuilds the boxes in [b], letting [f] replace a leaf box's content (null keeps it). */
    private fun rewrite(b: ByteArray, f: (type: String, content: ByteArray) -> ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        var p = 0
        while (p + 8 <= b.size) {
            val size = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.US_ASCII)
            val content = b.copyOfRange(p + 8, p + size)
            val newContent = if (type in containerBoxes) rewrite(content, f) else f(type, content) ?: content
            out.write(int32(newContent.size + 8)); out.write(type.toByteArray(Charsets.US_ASCII)); out.write(newContent)
            p += size
        }
        return out.toByteArray()
    }

    /** The avc1 sample entry (with its header) of an init segment. */
    private fun avc1Entry(init: String): ByteArray {
        val b = File(init).readBytes()
        val i = String(b, Charsets.ISO_8859_1).indexOf("avc1") - 4
        return b.copyOfRange(i, i + u32(b, i))
    }

    /** [init] with [extra] added to the video stsd and trex pointing track 1 at entry [index]. */
    private fun twoEntryInit(init: String, extra: ByteArray, index: Int, name: String): String {
        val patched = rewrite(File(init).readBytes()) { type, c ->
            when {
                type == "stsd" && String(c, 12, 4, Charsets.US_ASCII) == "avc1" ->
                    c.copyOfRange(0, 4) + int32(u32(c, 4) + 1) + c.copyOfRange(8, c.size) + extra
                type == "trex" && u32(c, 4) == 1 -> c.copyOf().also { int32(index).copyInto(it, 8) }
                else -> null
            }
        }
        return File(workDir, name).also { it.writeBytes(patched) }.absolutePath
    }

    @Test
    fun everySampleEntryOfTheStsdIsUsed() {
        requireFfmpeg()
        val a = files("fa\\d+\\.m4s")
        val b = files("fb\\d+\\.m4s")
        Assumptions.assumeTrue(a.isNotEmpty() && b.isNotEmpty(), "fixtures")
        val extra = avc1Entry(path("fb_init.mp4"))
        // Both init segments carry both entries; the second says (in trex) that its samples use entry 2.
        val init1 = twoEntryInit(path("fa_init.mp4"), extra, 1, "two_entries_1.mp4")
        val init2 = twoEntryInit(path("fa_init.mp4"), extra, 2, "two_entries_2.mp4")
        val d = Mp4Demuxer(CountingSink())
        (listOf(init1) + a + listOf(init2) + b).forEach { d.parseSegment(it) }
        val video = d.tracks.first { it.codec.isVideo }
        assert(video.additionalSampleEntries.size == 1) { "entries: ${1 + video.additionalSampleEntries.size}" }
        val s = video.samples
        val indexes = (0 until s.size).map { s.descriptionIndex(it) }
        assert(indexes == List(75) { 1 } + List(50) { 2 }) { "description indexes: ${indexes.groupingBy { it }.eachCount()}" }
        // The 640x360 frames are decoded with the second entry's parameter sets.
        val out = path("two_entries_out.mp4")
        mux(listOf(init1) + a + listOf(init2) + b, out, discontinuous = true) // rendition B restarts at 0
        val errors = decodeErrors(out)
        assert(errors.isEmpty()) { "decode errors: ${errors.take(5)}" }
    }

    // ---- rotation ----

    /** ffprobe's display matrix of the video stream, "" when there is none. */
    private fun displayMatrix(path: String): String {
        val ffprobe = which("ffprobe") ?: return "?"
        val p = ProcessBuilder(ffprobe, "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream_side_data=displaymatrix", "-of", "default=nw=1", path).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    private fun muxTo(src: String, out: String) {
        val m = TransmuxingMuxer("/nonexistent-appdir")
        assert(m.mux(listOf(src), out, {}, workDir.path, false, true)) { "mux of $src returned false: ${m.lastError}" }
    }

    @Test
    fun rotationFromMp4IsKeptInMp4AndMkv() {
        requireFfmpeg()
        Assumptions.assumeTrue(which("ffprobe") != null, "no ffprobe")
        for ((src, mkvReference) in listOf("rot90.mp4" to "rot90.mkv", "rot90f.mp4" to "rot90f.mkv", "rot90_frag.mp4" to "rot90.mkv")) {
            val want = displayMatrix(path(src))
            assert(want.isNotEmpty()) { "$src: fixture has no display matrix" }
            val mp4 = path("out_$src")
            muxTo(path(src), mp4)
            assert(displayMatrix(mp4) == want) { "$src -> mp4: ${displayMatrix(mp4)}, want $want" }
            val mkv = path("out_${src.substringBefore('.')}.mkv")
            muxTo(path(src), mkv)
            val ffmpegMkv = displayMatrix(path(mkvReference))
            assert(ffmpegMkv.isNotEmpty() && displayMatrix(mkv) == ffmpegMkv) { "$src -> mkv: ${displayMatrix(mkv)}, ffmpeg's: $ffmpegMkv" }
            val errors = decodeErrors(mkv)
            assert(errors.isEmpty()) { "$src -> mkv decode errors: ${errors.take(5)}" }
        }
    }

    @Test
    fun rotationFromMkvIsKept() {
        requireFfmpeg()
        for (name in listOf("rot90", "rot90f")) {
            // The Matroska pose reads back as the MP4 matrix it was written from.
            val fromMkv = MatroskaDemuxer(CountingSink()).also { it.parseSegment(path("$name.mkv")) }.tracks.first { it.codec.isVideo }.matrix
            val fromMp4 = Mp4Demuxer(CountingSink()).also { it.parseSegment(path("$name.mp4")) }.tracks.first { it.codec.isVideo }.matrix
            assert(fromMkv != null && fromMkv.contentEquals(fromMp4)) { "$name: mkv ${fromMkv?.toList()}, mp4 ${fromMp4?.toList()}" }
            if (which("ffprobe") == null) continue
            val out = path("out_mkv_$name.mkv")
            muxTo(path("$name.mkv"), out)
            assert(displayMatrix(out) == displayMatrix(path("$name.mkv"))) { "$name mkv -> mkv: ${displayMatrix(out)}" }
        }
    }

    @Test
    fun unrotatedVideoGetsNoMatrix() {
        requireFfmpeg()
        Assumptions.assumeTrue(which("ffprobe") != null, "no ffprobe")
        for (ext in listOf("mp4", "mkv")) {
            val out = path("out_plain.$ext")
            muxTo(path("whole.mp4"), out)
            assert(displayMatrix(out).isEmpty()) { "$ext: unexpected display matrix ${displayMatrix(out)}" }
        }
    }

    // ---- containers switching inside one list ----

    @Test
    fun tsSegmentInsideFragmentedMp4List() {
        requireFfmpeg()
        val fmp4 = files("fa\\d+\\.m4s")
        val ts = files("ts\\d+\\.ts")
        Assumptions.assumeTrue(fmp4.size == 3 && ts.size == 3, "fixtures")
        val init = path("fa_init.mp4")
        for ((name, list) in listOf(
            "fmp4_ts_fmp4" to listOf(init, fmp4[0], fmp4[1], ts[1], fmp4[2]),
            "ts_fmp4_fmp4" to listOf(ts[0], init, fmp4[0], fmp4[1], ts[2]),
        )) {
            val out = path("$name.mp4")
            mux(list, out)
            val counts = packetCounts(out)
            if (counts.isNotEmpty()) {
                assert(counts["video"] == 100) { "$name: expected 4 segments of 25 frames, got $counts" }
                assert((counts["audio"] ?: 0) > 150) { "$name: audio packets $counts" }
            }
            val errors = decodeErrors(out)
            assert(errors.isEmpty()) { "$name decode errors: ${errors.take(5)}" }
        }
    }
}
