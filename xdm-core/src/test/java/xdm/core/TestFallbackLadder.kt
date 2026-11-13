// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.OutputKind
import xdm.core.media.muxer.impl.TransmuxingMuxer
import java.io.File

/**
 * The one-file fallbacks after a remux that would leave a stream out (or fails): TS segments joined
 * (2b), two TS renditions merged at the packet level (2c), TS video with HLS packed audio wrapped into
 * PES (2d), an fMP4 rendition joined (2e), and the MP4 retry when Matroska can't name a track (2a).
 * The unsupported codecs come from ffmpeg (SMPTE 302M audio, VC-2/Dirac video); tests skip without it.
 */
class TestFallbackLadder {

    companion object {
        private val workDir = File(System.getProperty("java.io.tmpdir"), "xdm-fallback-ladder")
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
            val h264 = arrayOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25")
            // 2b: H.264 + SMPTE 302M in the same TS segments.
            run(ffmpeg!!, *q, *v, *a, "-t", "6", *h264, "-c:a", "s302m", "-strict", "-2", "-ac", "2",
                "-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
                "-hls_segment_filename", File(workDir, "mixed%d.ts").path, File(workDir, "mixed.m3u8").path)
            // 2c: separate TS renditions (both on PID 0x100), the audio in SMPTE 302M.
            run(ffmpeg!!, *q, *v, *a, "-t", "6", "-map", "0:v", "-map", "1:a", *h264, "-c:a", "s302m", "-strict", "-2", "-ac", "2",
                "-f", "hls", "-hls_time", "2", "-hls_list_size", "0", "-var_stream_map", "v:0,agroup:aud a:0,agroup:aud",
                "-master_pl_name", "rend.m3u8", "-hls_segment_filename", File(workDir, "rend%v_%d.ts").path, File(workDir, "rend%v.m3u8").path)
            // 2d: VC-2 (Dirac) video TS segments, and packed AAC / AC-3 audio for the same 6 s.
            run(ffmpeg!!, *q, *v, "-t", "6", "-c:v", "vc2", "-b:v", "2M", "-pix_fmt", "yuv420p",
                "-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
                "-hls_segment_filename", File(workDir, "dirac%d.ts").path, File(workDir, "dirac.m3u8").path)
            run(ffmpeg!!, *q, *a, "-t", "6", "-c:a", "aac", "-f", "segment", "-segment_time", "2", "-segment_format", "adts",
                File(workDir, "raw_aac%d.aac").path)
            run(ffmpeg!!, *q, *a, "-t", "6", "-c:a", "ac3", "-f", "segment", "-segment_time", "2", "-segment_format", "ac3",
                File(workDir, "raw_ac3%d.ac3").path)
            // 2a / 2e: fragmented MP4 A+V (init + media segments).
            run(ffmpeg!!, *q, *v, *a, "-t", "4", *h264, "-c:a", "aac",
                "-f", "hls", "-hls_time", "1", "-hls_segment_type", "fmp4", "-hls_list_size", "0",
                "-hls_fmp4_init_filename", "fm_init.mp4", "-hls_segment_filename", File(workDir, "fm%d.m4s").path, File(workDir, "fm.m3u8").path)
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
    }

    private fun requireFfmpeg() = Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not on PATH; skipping")
    private fun path(name: String) = File(workDir, name).absolutePath
    private fun files(regex: String) = workDir.listFiles { f -> f.name.matches(Regex(regex)) }!!
        .sortedBy { Regex("\\d+").findAll(it.name).last().value.toInt() }.map { it.absolutePath }

    private fun probe(path: String, vararg args: String): String {
        val ffprobe = which("ffprobe") ?: return ""
        val p = ProcessBuilder(ffprobe, "-v", "error", *args, path).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    /** Codec per stream; ffprobe lists a TS stream twice (under its program too), so by index. */
    private fun codecs(path: String) = probe(path, "-show_entries", "stream=index,codec_name", "-of", "csv=p=0")
        .lines().filter { it.contains(',') }.map { it.split(",") }.distinctBy { it[0] }.sortedBy { it[0].toInt() }.map { it[1] }

    private fun packets(path: String): Map<String, Int> =
        probe(path, "-count_packets", "-show_entries", "stream=codec_name,nb_read_packets", "-of", "csv=p=0")
            .lines().filter { it.contains(',') }.associate { it.split(",").let { (c, n) -> c to n.toInt() } }

    private fun startTimes(path: String): Map<String, Double> =
        probe(path, "-show_entries", "stream=codec_type,start_time", "-of", "csv=p=0")
            .lines().filter { it.contains(',') }.associate { it.split(",").let { (t, s) -> t to s.toDouble() } }

    private fun decodeErrors(path: String): List<String> {
        val p = ProcessBuilder(ffmpeg!!, "-v", "error", "-i", path, "-map", "0", "-f", "null", "-").redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return text.lines().filter { it.isNotBlank() }
    }

    /** An ID3v2.4 tag holding the HLS packed-audio `transportStreamTimestamp`. */
    private fun id3(pts90k: Long): ByteArray {
        val owner = "com.apple.streaming.transportStreamTimestamp\u0000".toByteArray(Charsets.US_ASCII)
        val payload = owner + ByteArray(8) { (pts90k ushr (56 - it * 8)).toByte() }
        val frame = "PRIV".toByteArray() + byteArrayOf(0, 0, (payload.size shr 7).toByte(), (payload.size and 0x7F).toByte(), 0, 0) + payload
        val size = frame.size
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0, (size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(),
            (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte()) + frame
    }

    // ---- 2b ----

    @Test
    fun tsSegmentsWithAStreamWeCantRemuxAreJoined() {
        requireFfmpeg()
        val segments = files("mixed\\d+\\.ts")
        val out = path("mixed_out.mp4")
        val m = TransmuxingMuxer("/nonexistent-appdir", "mp4")
        assert(m.mux(segments, out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
        assertEquals(OutputKind.TS_FALLBACK, m.outputKind)
        assertEquals("ts", m.outputContainer)
        assertArrayEquals(segments.map { File(it).readBytes() }.reduce { x, y -> x + y }, File(out).readBytes(), "not the segments joined")
        assertEquals(listOf("h264", "s302m"), codecs(out))
    }

    // ---- 2c ----

    @Test
    fun separateTsRenditionsAreMergedAtThePacketLevel() {
        requireFfmpeg()
        val video = files("rend0_\\d+\\.ts")
        val audio = files("rend1_\\d+\\.ts")
        Assumptions.assumeTrue(video.isNotEmpty() && audio.isNotEmpty(), "renditions")
        val out = path("rend_out.mp4")
        val m = TransmuxingMuxer("/nonexistent-appdir", "mp4")
        assert(m.mux(audio, video, out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
        assertEquals(OutputKind.TS_FALLBACK, m.outputKind)
        assertEquals("ts", m.outputContainer)
        // Every packet of both renditions, as ffmpeg's own merge of the master playlist has them.
        val reference = path("rend_ref.ts")
        run(ffmpeg!!, "-y", "-loglevel", "error", "-i", path("rend.m3u8"), "-map", "0", "-c", "copy", reference)
        assertEquals(packets(reference), packets(out))
        assertEquals(emptyList<String>(), decodeErrors(out))
        val start = startTimes(out)
        assertEquals(startTimes(reference)["video"]!!, start["video"]!!, 0.001, "video start")
        assertEquals(startTimes(reference)["audio"]!!, start["audio"]!!, 0.001, "audio start")
    }

    // ---- 2d ----

    @Test
    fun tsVideoWithPackedAudioIsMergedWithTheAudioWrapped() {
        requireFfmpeg()
        val video = files("dirac\\d+\\.ts")
        val videoStart = (startTimes(video.first())["video"]!! * 90000).toLong()
        for ((regex, ext, codec) in listOf(Triple("raw_aac\\d+\\.aac", "aac", "aac"), Triple("raw_ac3\\d+\\.ac3", "ac3", "ac3"))) {
            val raw = files(regex)
            // Packed audio: each segment behind its ID3 timestamp, on the video's clock.
            val packed = raw.mapIndexed { i, f ->
                File(workDir, "packed_${ext}_$i.$ext").also { it.writeBytes(id3(videoStart + i * 2L * 90000) + File(f).readBytes()) }.absolutePath
            }
            val out = path("dirac_$ext.mp4")
            val m = TransmuxingMuxer("/nonexistent-appdir", "mp4")
            assert(m.mux(packed, video, out, {}, workDir.path, false, false)) { "$ext: mux failed: ${m.lastError}" }
            assertEquals(OutputKind.TS_FALLBACK, m.outputKind, ext)
            assertEquals(listOf("dirac", codec), codecs(out), ext)
            // Every audio frame of the packed segments is there, and the audio starts with the video.
            val joined = File(workDir, "raw_all.$ext").also { f -> f.writeBytes(raw.map { File(it).readBytes() }.reduce { x, y -> x + y }) }
            assertEquals(packets(joined.absolutePath)[codec], packets(out)[codec], "$ext: audio frames")
            assertEquals(packets(video.first())["dirac"]!! * video.size, packets(out)["dirac"]!!, 3.0 * video.size, "$ext: video frames")
            val start = startTimes(out)
            assertEquals(start["video"]!!, start["audio"]!!, 0.05, "$ext: A/V start")
            assertEquals(emptyList<String>(), decodeErrors(out).filter { !it.contains("dirac", ignoreCase = true) }, ext)
        }
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Double, message: String) =
        assert(Math.abs(expected - actual) <= tolerance) { "$message: $actual, expected $expected ± $tolerance" }

    // ---- 2e ----

    @Test
    fun anFmp4RenditionIsJoinedWhenTheRemuxFails() {
        requireFfmpeg()
        val segments = listOf(path("fm_init.mp4")) + files("fm\\d+\\.m4s")
        val out = path("fm_joined.mp4")
        val m = TransmuxingMuxer("/nonexistent-appdir", "mp4").also { it.failRemuxForTest = true }
        assert(m.mux(segments, out, {}, workDir.path, false, true)) { "mux failed: ${m.lastError}" }
        assertEquals(OutputKind.AS_IS, m.outputKind)
        assertEquals("mp4", m.outputContainer)
        assertArrayEquals(segments.map { File(it).readBytes() }.reduce { x, y -> x + y }, File(out).readBytes())
        assertEquals(listOf("h264", "aac"), codecs(out))
        assertEquals(100, packets(out)["h264"])
        assertEquals(emptyList<String>(), decodeErrors(out))
    }

    // ---- 2a ----

    @Test
    fun matroskaThatCantNameATrackIsWrittenAsMp4() {
        requireFfmpeg()
        // The audio sample entry renamed to a fourcc no Matroska mapping knows.
        val init = File(workDir, "fm_init_zzzz.mp4").also { f ->
            val b = File(path("fm_init.mp4")).readBytes()
            val i = String(b, Charsets.ISO_8859_1).indexOf("mp4a")
            "zzzz".toByteArray().copyInto(b, i)
            f.writeBytes(b)
        }
        val out = path("fm_zzzz.mkv")
        val m = TransmuxingMuxer("/nonexistent-appdir", "mkv")
        assert(m.mux(listOf(init.absolutePath) + files("fm\\d+\\.m4s"), out, {}, workDir.path, false, true)) { "mux failed: ${m.lastError}" }
        assertEquals("mp4", m.outputContainer)
        assertEquals(OutputKind.REMUXED, m.outputKind)
        val tags = probe(out, "-show_entries", "stream=codec_tag_string", "-of", "csv=p=0").lines()
        assertEquals(listOf("avc1", "zzzz"), tags, "both tracks, the unknown one copied as it is")
    }

    @Test
    fun aRemuxThatWorksIsNotAFallback() {
        requireFfmpeg()
        val out = path("plain.mp4")
        val m = TransmuxingMuxer("/nonexistent-appdir", "mp4")
        assert(m.mux(listOf(path("fm_init.mp4")) + files("fm\\d+\\.m4s"), out, {}, workDir.path, false, true))
        assertEquals(OutputKind.REMUXED, m.outputKind)
        assertEquals("mp4", m.outputContainer)
    }
}
