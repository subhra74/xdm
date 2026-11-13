// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.mkv.Ebml
import xdm.core.media.muxer.transmux.mkv.EbmlBuf
import xdm.core.media.muxer.transmux.mkv.MatroskaDemuxer
import xdm.core.media.muxer.transmux.mkv.MkvWriter
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

/**
 * The Matroska output: the seek index (SeekHead + Cues) and DefaultDuration, block times that go
 * back past a cluster start, the shared timeline repair, ContentEncoding on Matroska input, and the
 * MP4 sample entries mapped to Matroska codecs. Fixtures come from ffmpeg; tests skip without it.
 */
class TestMkvOutput {

    companion object {
        private val workDir = File(System.getProperty("java.io.tmpdir"), "xdm-mkv-output")
        private var ffmpeg: String? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            ffmpeg = which("ffmpeg")
            if (ffmpeg == null) return
            workDir.mkdirs()
            val q = arrayOf("-y", "-loglevel", "error")
            val v = arrayOf("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25")
            val a = arrayOf("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000")
            // 10 s A+V TS segments, a keyframe every second; and audio-only.
            run(ffmpeg!!, *q, *v, *a, "-t", "10", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25",
                "-c:a", "aac", "-ac", "2", "-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
                "-hls_segment_filename", File(workDir, "av%d.ts").path, File(workDir, "av.m3u8").path)
            run(ffmpeg!!, *q, *a, "-t", "10", "-c:a", "aac", "-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
                "-hls_segment_filename", File(workDir, "ao%d.ts").path, File(workDir, "ao.m3u8").path)
            // Two fMP4 renditions; the second restarts at 0 (no discontinuity flag passed).
            for (name in listOf("fa", "fb")) {
                run(ffmpeg!!, *q, *v, *a, "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25",
                    "-c:a", "aac", "-f", "hls", "-hls_time", "1", "-hls_segment_type", "fmp4", "-hls_list_size", "0",
                    "-hls_fmp4_init_filename", "${name}_init.mp4",
                    "-hls_segment_filename", File(workDir, "$name%d.m4s").path, File(workDir, "$name.m3u8").path)
            }
            // H.264 video only in Matroska, the source of the ContentEncoding fixtures.
            run(ffmpeg!!, *q, *v, "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25", File(workDir, "h264.mkv").path)
            runSoft(ffmpeg!!, *q, *v, *a, "-t", "2", "-c:v", "libvpx-vp9", "-b:v", "300k", "-g", "25", "-pix_fmt", "yuv420p",
                "-c:a", "libopus", "-ac", "2", File(workDir, "whole.webm").path)
            // MP4/MOV sample entries for the Matroska mappings (encoders may be missing: those skip).
            val mappings = listOf(
                "pcm_s16le.mov" to arrayOf(*a, "-t", "1", "-c:a", "pcm_s16le"),
                "pcm_s16be.mov" to arrayOf(*a, "-t", "1", "-c:a", "pcm_s16be"),
                "pcm_s24le.mov" to arrayOf(*a, "-t", "1", "-c:a", "pcm_s24le"),
                "pcm_f32le.mov" to arrayOf(*a, "-t", "1", "-c:a", "pcm_f32le"),
                "pcm_u8.mov" to arrayOf(*a, "-t", "1", "-c:a", "pcm_u8"),
                "pcm_s16le.mp4" to arrayOf(*a, "-t", "1", "-c:a", "pcm_s16le"),
                "pcm_f32le.mp4" to arrayOf(*a, "-t", "1", "-c:a", "pcm_f32le"),
                "aac.mov" to arrayOf(*a, "-t", "1", "-c:a", "aac", "-ac", "2"),
                "mp2.mp4" to arrayOf(*a, "-t", "1", "-c:a", "mp2"),
                "opus.mp4" to arrayOf(*a, "-t", "1", "-c:a", "libopus"),
                "prores.mov" to arrayOf(*v, "-t", "1", "-c:v", "prores_ks", "-profile:v", "0"),
                "mpeg4.mp4" to arrayOf(*v, "-t", "1", "-c:v", "mpeg4"),
                "mpeg2.mp4" to arrayOf(*v, "-t", "1", "-c:v", "mpeg2video"),
                "mjpeg.mov" to arrayOf(*v, "-t", "1", "-c:v", "mjpeg", "-pix_fmt", "yuvj420p"),
            )
            for ((name, args) in mappings) runSoft(ffmpeg!!, *q, *args, File(workDir, "map_$name").path)
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
    private fun files(regex: String) = workDir.listFiles { f -> f.name.matches(Regex(regex)) }
        ?.sortedBy { it.name }?.map { it.absolutePath } ?: emptyList()

    private fun mux(list: List<String>, out: String, discontinuous: Boolean = false) {
        val m = TransmuxingMuxer("/nonexistent-appdir")
        assert(m.mux(list, out, {}, workDir.path, false, true, discontinuous)) { "mux returned false: ${m.lastError}" }
    }

    private fun probe(path: String, vararg args: String): String {
        val ffprobe = which("ffprobe") ?: return ""
        val p = ProcessBuilder(ffprobe, "-v", "error", *args, path).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    private fun decodeErrors(path: String): List<String> {
        val p = ProcessBuilder(ffmpeg!!, "-v", "error", "-i", path, "-map", "0", "-f", "null", "-").redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return text.lines().filter { it.isNotBlank() }
    }

    /** md5 of all decoded media of [path] (stream [map]), independent of how it is cut into packets. */
    private fun decodedMd5(path: String, map: String = "0"): String {
        val p = ProcessBuilder(ffmpeg!!, "-v", "error", "-i", path, "-map", map, "-f", "md5", "-").redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    // ---- a small EBML walker for checking the output's structure ----

    private class El(val id: Long, val start: Int, val dataStart: Int, val end: Int)

    private fun children(b: ByteArray, from: Int, to: Int): List<El> {
        val out = ArrayList<El>()
        var p = from
        while (p < to) {
            val start = p
            var len = 1; var mask = 0x80
            val first = b[p].toInt() and 0xFF
            while (len < 4 && first and mask == 0) { mask = mask ushr 1; len++ }
            var id = 0L
            repeat(len) { id = (id shl 8) or (b[p++].toLong() and 0xFF) }
            val sf = b[p].toInt() and 0xFF
            var sl = 1; var sm = 0x80
            while (sl < 8 && sf and sm == 0) { sm = sm ushr 1; sl++ }
            var size = (sf and (sm - 1)).toLong()
            for (i in 1 until sl) size = (size shl 8) or (b[p + i].toLong() and 0xFF)
            p += sl
            val end = minOf(to.toLong(), p + size).toInt()
            out.add(El(id, start, p, end))
            p = end
        }
        return out
    }

    private fun uint(b: ByteArray, e: El): Long { var v = 0L; for (i in e.dataStart until e.end) v = (v shl 8) or (b[i].toLong() and 0xFF); return v }

    /** The Segment's children and where its data starts. */
    private fun segment(b: ByteArray): Pair<Int, List<El>> {
        val top = children(b, 0, b.size)
        val seg = top.first { it.id == Ebml.SEGMENT }
        return seg.dataStart to children(b, seg.dataStart, seg.end)
    }

    // ---- seek index ----

    @Test
    fun seekHeadPointsAtCuesWithOneCuePerKeyframe() {
        requireFfmpeg()
        val out = path("cues.mkv")
        mux(files("av\\d+\\.ts"), out)
        val b = File(out).readBytes()
        val (segData, level1) = segment(b)
        assert(level1.first().id == Ebml.SEEK_HEAD) { "first element is ${level1.first().id.toString(16)}, not a SeekHead" }
        val seeks = children(b, level1.first().dataStart, level1.first().end).filter { it.id == Ebml.SEEK }.associate { seek ->
            val kids = children(b, seek.dataStart, seek.end)
            val id = kids.first { it.id == Ebml.SEEK_ID }.let { e -> uint(b, e) }
            id to uint(b, kids.first { it.id == Ebml.SEEK_POSITION })
        }
        for (id in listOf(Ebml.INFO, Ebml.TRACKS, Ebml.CUES)) {
            val pos = seeks[id] ?: error("SeekHead has no entry for ${id.toString(16)}")
            val at = level1.firstOrNull { it.start == (segData + pos).toInt() }
            assert(at?.id == id) { "SeekHead entry ${id.toString(16)} points at ${at?.id?.toString(16)}" }
        }
        val cues = level1.single { it.id == Ebml.CUES }
        val points = children(b, cues.dataStart, cues.end).filter { it.id == Ebml.CUE_POINT }
        // One keyframe a second for 10 s.
        assert(points.size == 10) { "expected 10 cue points, got ${points.size}" }
        // Every cue's cluster position is a Cluster.
        for (cp in points) {
            val pos = children(b, cp.dataStart, cp.end).first { it.id == Ebml.CUE_TRACK_POSITIONS }
                .let { tp -> uint(b, children(b, tp.dataStart, tp.end).first { it.id == Ebml.CUE_CLUSTER_POSITION }) }
            assert(level1.any { it.start == (segData + pos).toInt() && it.id == Ebml.CLUSTER }) { "cue at $pos isn't a cluster" }
        }
        // ffmpeg seeks with the index to 7 s and decodes from there.
        val p = ProcessBuilder(ffmpeg!!, "-v", "error", "-ss", "7", "-i", out, "-f", "null", "-").redirectErrorStream(true).start()
        val errors = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        assert(p.waitFor() == 0 && errors.isEmpty()) { "seek to 7 s: $errors" }
    }

    @Test
    fun audioOnlyHasACuePerCluster() {
        requireFfmpeg()
        val out = path("cues_audio.mka")
        mux(files("ao\\d+\\.ts"), out)
        val b = File(out).readBytes()
        val (_, level1) = segment(b)
        val clusters = level1.count { it.id == Ebml.CLUSTER }
        val cues = level1.single { it.id == Ebml.CUES }
        val points = children(b, cues.dataStart, cues.end).count { it.id == Ebml.CUE_POINT }
        assert(clusters > 1 && points == clusters) { "$clusters clusters, $points cue points" }
    }

    @Test
    fun tracksCarryDefaultDuration() {
        requireFfmpeg()
        val out = path("cues.mkv")
        mux(files("av\\d+\\.ts"), out)
        val b = File(out).readBytes()
        val (_, level1) = segment(b)
        val tracks = level1.single { it.id == Ebml.TRACKS }
        val durations = children(b, tracks.dataStart, tracks.end).map { entry ->
            children(b, entry.dataStart, entry.end).firstOrNull { it.id == Ebml.DEFAULT_DURATION }?.let { uint(b, it) }
        }
        // 25 fps video, 1024-sample AAC frames at 48 kHz.
        assert(durations.toSet() == setOf(40_000_000L, 21_333_333L)) { "DefaultDuration: $durations" }
    }

    // ---- block times ----

    private class CountingSink : SampleSink {
        var pos = 0L
        override fun writeSampleData(data: ByteArray, offset: Int, length: Int): Long = pos.also { pos += length }
    }

    @Test
    fun blockTimeFarBeforeItsClusterStartsANewCluster() {
        workDir.mkdirs()
        val out = path("wrap.mkv")
        val writer = MkvWriter(out, workDir.path)
        val track = Track(Codec.VP9).apply { matroskaCodecId = "V_VP9"; timescale = 1000; width = 16; height = 16 }
        // A keyframe at 40 s, then (in decode order) a frame presented at 1 s: 39 s before the cluster
        // start, outside a block's signed 16-bit offset.
        for ((pts, key) in listOf(40_000L to true, 1_000L to false, 40_040L to false)) {
            val at = writer.writeSampleData(byteArrayOf(1, 2, 3), 0, 3)
            track.samples.add(at, 3, pts, track.samples.size.toLong() * 40, key)
        }
        assert(writer.finish(listOf(track))) { "finish returned false" }
        val d = MatroskaDemuxer(CountingSink()).also { it.parseSegment(out) }
        val s = d.tracks.single().samples
        val got = (0 until s.size).map { s.pts(it) }
        assert(got == listOf(39_000L, 0L, 39_040L)) { "block times read back: $got" }
    }

    // ---- shared timeline ----

    @Test
    fun matroskaTimestampsRestartingAreRepairedInMkv() {
        requireFfmpeg()
        val webm = File(workDir, "whole.webm")
        Assumptions.assumeTrue(webm.exists() && webm.length() > 0, "no VP9/Opus encoder")
        val out = path("webm_twice.mkv")
        mux(listOf(webm.absolutePath, webm.absolutePath), out) // no discontinuity flag
        val pts = probe(out, "-select_streams", "v:0", "-show_entries", "packet=pts_time", "-of", "csv=p=0")
            .lines().filter { it.isNotBlank() }.map { it.toDouble() }
        assert(pts.size == 100) { "expected 100 video packets, got ${pts.size}" }
        val sorted = pts.sorted()
        assert(sorted.last() > 3.8) { "second copy not placed after the first: last pts ${sorted.last()}" }
        val errors = decodeErrors(out)
        assert(errors.isEmpty()) { "decode errors: ${errors.take(5)}" }
    }

    @Test
    fun mp4TimestampsGoingBackAreRepairedWithoutTheFlag() {
        requireFfmpeg()
        val list = listOf(path("fa_init.mp4")) + files("fa\\d+\\.m4s") + listOf(path("fb_init.mp4")) + files("fb\\d+\\.m4s")
        val out = path("fmp4_restart.mp4")
        mux(list, out) // no discontinuity flag: the backward jump alone triggers the repair
        val errors = decodeErrors(out)
        assert(errors.isEmpty()) { "decode errors: ${errors.take(5)}" }
        val duration = probe(out, "-show_entries", "format=duration", "-of", "csv=p=0").toDoubleOrNull() ?: return
        assert(duration > 3.8) { "duration $duration: the second rendition overlaps the first" }
    }

    // ---- ContentEncoding ----

    private class Frame(val data: ByteArray, val pts: Long, val key: Boolean)

    /** The frames and CodecPrivate of the H.264 Matroska fixture. */
    private fun h264Frames(): Pair<List<Frame>, ByteArray> {
        val bytes = ByteArrayOutputStream()
        val sink = object : SampleSink {
            override fun writeSampleData(data: ByteArray, offset: Int, length: Int): Long =
                bytes.size().toLong().also { bytes.write(data, offset, length) }
        }
        val d = MatroskaDemuxer(sink).also { it.parseSegment(path("h264.mkv")) }
        val t = d.tracks.single()
        val all = bytes.toByteArray()
        val s = t.samples
        val frames = (0 until s.size).map { i ->
            Frame(all.copyOfRange(s.fileOffset(i).toInt(), s.fileOffset(i).toInt() + s.sampleSize(i)), s.pts(i), s.isKeyframe(i))
        }
        return frames to t.decoderConfigRecord!!
    }

    private fun zlib(data: ByteArray): ByteArray {
        val d = Deflater(); d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** A Matroska file of [frames] whose track says it is stored with [encoding] (algo, scope, settings, type). */
    private fun encodedMkv(name: String, frames: List<Frame>, codecPrivate: ByteArray, algo: Long, scope: Long,
                           settings: ByteArray?, type: Long = 0, encode: (ByteArray) -> ByteArray): String {
        val b = EbmlBuf()
        b.master(Ebml.EBML) { str(Ebml.DOC_TYPE, "matroska"); uint(Ebml.DOC_TYPE_VERSION, 4); uint(Ebml.DOC_TYPE_READ_VERSION, 2) }
        b.master(Ebml.SEGMENT) {
            master(Ebml.INFO) { uint(Ebml.TIMECODE_SCALE, 1_000_000) }
            master(Ebml.TRACKS) {
                master(Ebml.TRACK_ENTRY) {
                    uint(Ebml.TRACK_NUMBER, 1); uint(Ebml.TRACK_TYPE, Ebml.TRACK_TYPE_VIDEO)
                    str(Ebml.CODEC_ID, "V_MPEG4/ISO/AVC")
                    bin(Ebml.CODEC_PRIVATE, if (scope and 2L != 0L) encode(codecPrivate) else codecPrivate)
                    master(Ebml.VIDEO) { uint(Ebml.PIXEL_WIDTH, 320); uint(Ebml.PIXEL_HEIGHT, 240) }
                    master(Ebml.CONTENT_ENCODINGS) {
                        master(Ebml.CONTENT_ENCODING) {
                            uint(Ebml.CONTENT_ENCODING_SCOPE, scope)
                            uint(Ebml.CONTENT_ENCODING_TYPE, type)
                            master(Ebml.CONTENT_COMPRESSION) {
                                uint(Ebml.CONTENT_COMP_ALGO, algo)
                                settings?.let { bin(Ebml.CONTENT_COMP_SETTINGS, it) }
                            }
                        }
                    }
                }
            }
            master(Ebml.CLUSTER) {
                uint(Ebml.TIMECODE, 0)
                for (f in frames) {
                    val data = if (scope and 1L != 0L) encode(f.data) else f.data
                    val rel = f.pts.toInt()
                    bin(Ebml.SIMPLE_BLOCK, byteArrayOf(0x81.toByte(), (rel shr 8).toByte(), rel.toByte(), if (f.key) 0x80.toByte() else 0) + data)
                }
            }
        }
        return File(workDir, name).also { it.writeBytes(b.toByteArray()) }.absolutePath
    }

    @Test
    fun contentEncodingsAreUndone() {
        requireFfmpeg()
        val (frames, codecPrivate) = h264Frames()
        Assumptions.assumeTrue(frames.all { it.data.size < 65536 && it.data[0].toInt() == 0 && it.data[1].toInt() == 0 }, "frames too big for the 00 00 prefix")
        val reference = path("enc_reference.mkv")
        mux(listOf(path("h264.mkv")), reference)
        val want = decodedMd5(reference)
        val stripped = encodedMkv("enc_strip.mkv", frames, codecPrivate, algo = 3, scope = 1, settings = byteArrayOf(0, 0)) {
            it.copyOfRange(2, it.size)
        }
        val zlibbed = encodedMkv("enc_zlib.mkv", frames, codecPrivate, algo = 0, scope = 3, settings = null) { zlib(it) }
        for (src in listOf(stripped, zlibbed)) {
            val out = src.replace(".mkv", "_out.mkv")
            mux(listOf(src), out)
            assert(decodedMd5(out) == want) { "${File(src).name}: decodes differently from the plain file" }
        }
    }

    @Test
    fun encryptedTrackIsDropped() {
        requireFfmpeg()
        val (frames, codecPrivate) = h264Frames()
        val encrypted = encodedMkv("enc_crypt.mkv", frames, codecPrivate, algo = 0, scope = 1, settings = null, type = 1) { it }
        val m = TransmuxingMuxer("/nonexistent-appdir")
        assert(!m.mux(listOf(encrypted), path("enc_crypt_out.mkv"), {}, workDir.path, false, true)) { "an encrypted-only file muxed" }
    }

    // ---- MP4 sample entries mapped to Matroska ----

    @Test
    fun mp4SampleEntriesMapToMatroskaCodecs() {
        requireFfmpeg()
        val expected = mapOf(
            "pcm_s16le.mov" to "pcm_s16le", "pcm_s16be.mov" to "pcm_s16be", "pcm_s24le.mov" to "pcm_s24le",
            "pcm_f32le.mov" to "pcm_f32le", "pcm_s16le.mp4" to "pcm_s16le", "pcm_f32le.mp4" to "pcm_f32le", "pcm_u8.mov" to "pcm_u8",
            "aac.mov" to "aac", "mp2.mp4" to "mp2", "opus.mp4" to "opus",
            "prores.mov" to "prores", "mpeg4.mp4" to "mpeg4", "mpeg2.mp4" to "mpeg2video", "mjpeg.mov" to "mjpeg",
        )
        var checked = 0
        for ((name, codec) in expected) {
            val src = File(workDir, "map_$name")
            if (!src.exists() || src.length() == 0L) continue // encoder or muxer support missing
            val out = path("map_${name.replace('.', '_')}.mkv")
            mux(listOf(src.absolutePath), out)
            val got = probe(out, "-show_entries", "stream=codec_name,channels", "-of", "csv=p=0").lines().first().split(",")
            assert(got[0] == codec) { "$name: codec ${got[0]}, want $codec" }
            val srcChannels = probe(src.absolutePath, "-show_entries", "stream=channels", "-of", "csv=p=0").lines().first()
            if (srcChannels.isNotBlank()) assert(got.getOrNull(1) == srcChannels) { "$name: channels ${got.getOrNull(1)}, source $srcChannels" }
            val errors = decodeErrors(out)
            assert(errors.isEmpty()) { "$name decode errors: ${errors.take(5)}" }
            // PCM is regrouped into ~20 ms blocks: the samples must stay the same.
            if (codec.startsWith("pcm_")) assert(decodedMd5(out) == decodedMd5(src.absolutePath)) { "$name: PCM decodes differently" }
            checked++
        }
        assert(checked >= 8) { "only $checked mapping fixtures could be made" }
    }

    @Test
    fun opusFromMp4GetsSeekPreRoll() {
        requireFfmpeg()
        val src = File(workDir, "map_opus.mp4")
        Assumptions.assumeTrue(src.exists() && src.length() > 0, "no Opus encoder")
        val out = path("opus_preroll.mka")
        mux(listOf(src.absolutePath), out)
        val b = File(out).readBytes()
        val (_, level1) = segment(b)
        val tracks = level1.single { it.id == Ebml.TRACKS }
        val entry = children(b, tracks.dataStart, tracks.end).single()
        val kids = children(b, entry.dataStart, entry.end)
        assert(kids.firstOrNull { it.id == Ebml.SEEK_PRE_ROLL }?.let { uint(b, it) } == 80_000_000L) { "no 80 ms SeekPreRoll" }
        assert(kids.none { it.id == Ebml.CODEC_DELAY }) { "CodecDelay written for an input whose timestamps already skip the pre-skip" }
    }
}
