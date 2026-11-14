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
 * "Save as MP4 where possible" turned off ([TransmuxingMuxer.setKeepOriginalFormat]): TS segments are
 * written as one `.ts` at packet level (joined, or merged with a separate TS / packed audio rendition)
 * and that is not a fallback; MP4 inputs (fMP4, standalone MP4 parts, together or as separate
 * renditions) are remuxed into MP4 as usual. Turned on, TS is remuxed into MP4. Skips without ffmpeg.
 */
class TestKeepOriginalFormat {

    companion object {
        private val workDir = File(System.getProperty("java.io.tmpdir"), "xdm-keep-original")
        private var ffmpeg: String? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            ffmpeg = which("ffmpeg")
            if (ffmpeg == null) return
            workDir.deleteRecursively()
            workDir.mkdirs()
            val q = arrayOf("-y", "-loglevel", "error")
            val v = arrayOf("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25")
            val a = arrayOf("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000")
            val enc = arrayOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25", "-bf", "2", "-c:a", "aac")
            fun w(name: String) = File(workDir, name).path
            // H.264 + AAC in the same TS segments.
            run(ffmpeg!!, *q, *v, *a, "-t", "6", *enc, "-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
                "-hls_segment_filename", w("av%d.ts"), w("av.m3u8"))
            // Separate TS renditions (both on PID 0x100).
            run(ffmpeg!!, *q, *v, *a, "-t", "6", "-map", "0:v", "-map", "1:a", *enc,
                "-f", "hls", "-hls_time", "2", "-hls_list_size", "0", "-var_stream_map", "v:0,agroup:aud a:0,agroup:aud",
                "-master_pl_name", "rend.m3u8", "-hls_segment_filename", w("rend%v_%d.ts"), w("rend%v.m3u8"))
            // Raw ADTS for packed audio (the ID3 timestamp is added by the test).
            run(ffmpeg!!, *q, *a, "-t", "6", "-c:a", "aac", "-f", "segment", "-segment_time", "2", "-segment_format", "adts", w("raw%d.aac"))
            // fMP4 A+V (init + fragments).
            run(ffmpeg!!, *q, *v, *a, "-t", "4", *enc, "-f", "hls", "-hls_time", "1", "-hls_segment_type", "fmp4", "-hls_list_size", "0",
                "-hls_fmp4_init_filename", "fm_init.mp4", "-hls_segment_filename", w("fm%d.m4s"), w("fm.m3u8"))
            // Standalone (progressive) MP4 parts, each with its own moov: A+V together, and as separate renditions.
            val src = w("src.mp4")
            run(ffmpeg!!, *q, *v, *a, "-t", "6", *enc, "-shortest", src)
            run(ffmpeg!!, *q, "-i", src, "-c", "copy", "-f", "segment", "-segment_time", "2", "-segment_format", "mp4",
                "-reset_timestamps", "1", w("part%d.mp4"))
            run(ffmpeg!!, *q, "-i", src, "-map", "0:v", "-c", "copy", "-f", "segment", "-segment_time", "2", "-segment_format", "mp4", w("vpart%d.mp4"))
            run(ffmpeg!!, *q, "-i", src, "-map", "0:a", "-c", "copy", "-f", "segment", "-segment_time", "2", "-segment_format", "mp4", w("apart%d.mp4"))
        }

        private fun which(cmd: String): String? =
            System.getenv("PATH").split(File.pathSeparator).map { File(it, cmd) }.firstOrNull { it.canExecute() }?.absolutePath

        private fun run(vararg cmd: String) {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }
        }
    }

    private fun requireFfmpeg() = Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not on PATH; skipping")
    private fun path(name: String) = File(workDir, name).absolutePath

    /** Files matching [regex], in segment order (by the number in the name). */
    private fun files(regex: String) = workDir.listFiles { f -> f.name.matches(Regex(regex)) }!!
        .sortedBy { Regex("(\\d+)\\.[a-z0-9]+$").find(it.name)!!.groupValues[1].toInt() }.map { it.absolutePath }

    private fun probe(path: String, vararg args: String): String {
        val p = ProcessBuilder("ffprobe", "-v", "error", *args, path).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    }

    private fun formatName(path: String) = probe(path, "-show_entries", "format=format_name", "-of", "csv=p=0")
    private fun codecs(path: String) =
        probe(path, "-show_entries", "format=nb_streams:stream=index,codec_name", "-of", "csv=p=0").lines()
            .filter { it.matches(Regex("\\d+,.+")) }.distinct().map { it.substringAfter(',') }

    private fun packets(path: String, stream: String) =
        probe(path, "-select_streams", stream, "-count_packets", "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0")
            .lines().first { it.isNotBlank() }.trim().toInt()

    private fun decodeErrors(path: String): List<String> {
        val p = ProcessBuilder(ffmpeg!!, "-v", "error", "-i", path, "-map", "0", "-f", "null", "-").redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readLines().also { p.waitFor() }
    }

    private fun id3(pts90k: Long): ByteArray {
        val owner = "com.apple.streaming.transportStreamTimestamp\u0000".toByteArray(Charsets.US_ASCII)
        val payload = owner + ByteArray(8) { (pts90k ushr (56 - it * 8)).toByte() }
        val frame = "PRIV".toByteArray() + byteArrayOf(0, 0, (payload.size shr 7).toByte(), (payload.size and 0x7F).toByte(), 0, 0) + payload
        val size = frame.size
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0, (size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(),
            (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte()) + frame
    }

    private fun keeping() = TransmuxingMuxer("/nonexistent-appdir").also { it.setKeepOriginalFormat(true) }

    @Test
    fun tsSegmentsAreJoinedIntoOneTsAsTheyAre() {
        requireFfmpeg()
        val segments = files("av\\d+\\.ts")
        val out = path("av_kept.part")
        val m = keeping()
        assert(m.mux(segments, out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
        assertEquals("ts", m.outputContainer)
        assertEquals(OutputKind.REMUXED, m.outputKind, "the user's choice, not a fallback")
        assertArrayEquals(segments.map { File(it).readBytes() }.reduce { x, y -> x + y }, File(out).readBytes(), "not the segments joined")
    }

    @Test
    fun anEmptySegmentDoesNotStopTheTsBeingKept() {
        requireFfmpeg()
        // A packager may list a 0-byte first segment (seen from GStreamer hlssink2).
        val empty = File(workDir, "empty0.ts").also { it.writeBytes(ByteArray(0)) }.absolutePath
        val video = files("rend0_\\d+\\.ts")
        val audio = files("rend1_\\d+\\.ts")
        val out = path("empty_kept.part")
        val m = keeping()
        assert(m.mux(audio, listOf(empty) + video, out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
        assertEquals("ts", m.outputContainer, "kept as .ts")
        assertEquals(video.sumOf { packets(it, "v:0") }, packets(out, "v:0"), "video packets")
    }

    @Test
    fun withMp4ChosenTsIsRemuxed() {
        requireFfmpeg()
        val out = path("av_remuxed.part")
        val m = TransmuxingMuxer("/nonexistent-appdir")
        assert(m.mux(files("av\\d+\\.ts"), out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
        assertEquals("mp4", m.outputContainer)
        assert(formatName(out).contains("mp4")) { "not an MP4: ${formatName(out)}" }
    }

    @Test
    fun separateTsRenditionsAreMergedIntoOneTs() {
        requireFfmpeg()
        val video = files("rend0_\\d+\\.ts")
        val audio = files("rend1_\\d+\\.ts")
        val out = path("rend_kept.part")
        val m = keeping()
        assert(m.mux(audio, video, out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
        assertEquals("ts", m.outputContainer)
        assertEquals(OutputKind.REMUXED, m.outputKind)
        assertEquals("mpegts", formatName(out))
        assertEquals(listOf("h264", "aac"), codecs(out))
        assertEquals(video.sumOf { packets(it, "v:0") }, packets(out, "v:0"), "video packets")
        assertEquals(audio.sumOf { packets(it, "a:0") }, packets(out, "a:0"), "audio packets")
        assertEquals(emptyList<String>(), decodeErrors(out))
    }

    /** [ts] without the packets of [pid] (its PMT entry stays): a declared stream that carries nothing. */
    private fun withoutPid(ts: String, pid: Int, name: String): String {
        val b = File(ts).readBytes()
        val out = java.io.ByteArrayOutputStream()
        for (k in 0 until b.size / 188) {
            val p = ((b[k * 188 + 1].toInt() and 0x1F) shl 8) or (b[k * 188 + 2].toInt() and 0xFF)
            if (p != pid) out.write(b, k * 188, 188)
        }
        return File(workDir, name).also { it.writeBytes(out.toByteArray()) }.absolutePath
    }

    @Test
    fun anEmptyAudioStreamInTheVideoSegmentsIsLeftOut() {
        requireFfmpeg()
        // Some packagers declare an audio stream in the video segments that never carries a packet; listed
        // first, a player would pick it and play silence. Left out when an audio rendition is merged.
        val audioPid = probe(files("av\\d+\\.ts").first(), "-select_streams", "a:0", "-show_entries", "stream=id", "-of", "csv=p=0")
            .lines().first { it.isNotBlank() }.trim().removePrefix("0x").toInt(16)
        val video = files("av\\d+\\.ts").mapIndexed { i, f -> withoutPid(f, audioPid, "avempty$i.ts") }
        val audio = files("rend1_\\d+\\.ts")
        val out = path("empty_audio_plus_rendition.part")
        val m = keeping()
        assert(m.mux(audio, video, out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
        assertEquals(listOf("h264", "aac"), codecs(out), "only the rendition's audio")
        assertEquals(audio.sumOf { packets(it, "a:0") }, packets(out, "a:0"), "the rendition's audio")
        assertEquals(emptyList<String>(), decodeErrors(out))
    }

    @Test
    fun realAudioInTheVideoSegmentsStaysBesideTheRendition() {
        requireFfmpeg()
        // Audio the video segments really carry (another language, the main mix) is kept, as the MP4 keeps it.
        val video = files("av\\d+\\.ts")
        val audio = files("rend1_\\d+\\.ts")
        val out = path("av_plus_rendition.part")
        val m = keeping()
        assert(m.mux(audio, video, out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
        assertEquals(listOf("h264", "aac", "aac"), codecs(out), "both audio streams")
        assertEquals(emptyList<String>(), decodeErrors(out))
    }

    @Test
    fun tsVideoWithPackedAudioIsMergedIntoOneTs() {
        requireFfmpeg()
        val video = files("rend0_\\d+\\.ts")
        val videoStart = (probe(video.first(), "-select_streams", "v:0", "-show_entries", "stream=start_time", "-of", "csv=p=0").lines().first { it.isNotBlank() }.trim().toDouble() * 90000).toLong()
        val packed = files("raw\\d+\\.aac").mapIndexed { i, f ->
            File(workDir, "packed$i.aac").also { it.writeBytes(id3(videoStart + i * 2L * 90000) + File(f).readBytes()) }.absolutePath
        }
        val out = path("packed_kept.part")
        val m = keeping()
        assert(m.mux(packed, video, out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
        assertEquals("ts", m.outputContainer)
        assertEquals(listOf("h264", "aac"), codecs(out))
        assertEquals(emptyList<String>(), decodeErrors(out))
    }

    @Test
    fun fmp4StaysMp4() {
        requireFfmpeg()
        val out = path("fm_kept.part")
        val m = keeping()
        assert(m.mux(listOf(path("fm_init.mp4")) + files("fm\\d+\\.m4s"), out, {}, workDir.path, false, true)) { "mux failed: ${m.lastError}" }
        assertEquals("mp4", m.outputContainer)
        assertEquals(OutputKind.REMUXED, m.outputKind)
        assertEquals(100, packets(out, "v:0"))
    }

    @Test
    fun standaloneMp4PartsStayMp4WithEveryFrame() {
        requireFfmpeg()
        for (keep in listOf(true, false)) {
            val out = path("parts_$keep.part")
            val m = TransmuxingMuxer("/nonexistent-appdir").also { it.setKeepOriginalFormat(keep) }
            assert(m.mux(files("part\\d+\\.mp4"), out, {}, workDir.path, false, true)) { "keep=$keep: mux failed: ${m.lastError}" }
            assertEquals("mp4", m.outputContainer, "keep=$keep")
            assertEquals(packets(path("src.mp4"), "v:0"), packets(out, "v:0"), "keep=$keep: video frames")
            assertEquals(packets(path("src.mp4"), "a:0"), packets(out, "a:0"), "keep=$keep: audio frames")
            assertEquals(emptyList<String>(), decodeErrors(out), "keep=$keep")
        }
    }

    @Test
    fun separateStandaloneMp4RenditionsAreRemuxedIntoOneMp4() {
        requireFfmpeg()
        val out = path("vaparts_kept.part")
        val m = keeping()
        assert(m.mux(files("apart\\d+\\.mp4"), files("vpart\\d+\\.mp4"), out, {}, workDir.path, false, true)) { "mux failed: ${m.lastError}" }
        assertEquals("mp4", m.outputContainer)
        assertEquals(listOf("h264", "aac"), codecs(out))
        assertEquals(packets(path("src.mp4"), "v:0"), packets(out, "v:0"), "video frames")
        assertEquals(packets(path("src.mp4"), "a:0"), packets(out, "a:0"), "audio frames")
        assertEquals(emptyList<String>(), decodeErrors(out))
    }
}
