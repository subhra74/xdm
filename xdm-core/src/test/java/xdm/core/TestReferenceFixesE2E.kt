// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.OutputKind
import xdm.core.media.muxer.impl.TransmuxingMuxer
import java.io.File

/**
 * Reference-review fixes checked on ffmpeg-made streams against what ffmpeg's stream copy writes
 * (edit start at the first keyframe, several audio tracks, section joins, high audio rates).
 * Skipped without ffmpeg.
 */
class TestReferenceFixesE2E {

    companion object {
        private val workDir = File(System.getProperty("java.io.tmpdir"), "xdm-reference-fixes")
        private var ffmpeg: String? = null
        private var ffprobe: String? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            ffmpeg = which("ffmpeg"); ffprobe = which("ffprobe")
            if (ffmpeg == null || ffprobe == null) { ffmpeg = null; return }
            workDir.deleteRecursively(); workDir.mkdirs()
        }

        private fun which(cmd: String): String? {
            val path = System.getenv("PATH") ?: return null
            return path.split(File.pathSeparator).map { File(it, cmd) }.firstOrNull { it.canExecute() }?.absolutePath
        }

        fun run(vararg cmd: String): String {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            check(p.waitFor() == 0) { "command failed: ${cmd.joinToString(" ")}\n$out" }
            return out
        }
    }

    private fun requireFfmpeg() = Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not on PATH; skipping")
    private fun path(name: String) = File(workDir, name).absolutePath
    private fun files(regex: String) = workDir.listFiles { f -> f.name.matches(Regex(regex)) }!!
        .sortedBy { Regex("\\d+").findAll(it.name).last().value.toInt() }.map { it.absolutePath }

    private fun probe(path: String, vararg args: String) = run(ffprobe!!, "-v", "error", *args, path).trim()

    /** Presentation time of the first decoded video frame (edit lists applied). */
    private fun firstVideoFrame(path: String) =
        probe(path, "-select_streams", "v", "-show_entries", "frame=pts_time", "-read_intervals", "%+#1", "-of", "csv=p=0")
            .lines().first().trim(',').toDouble()

    /** Video packets (pts, duration, keyframe) in seconds, edit lists not applied. */
    private fun videoPackets(path: String) =
        probe(path, "-select_streams", "v", "-show_entries", "packet=pts_time,duration_time,flags", "-of", "csv=p=0")
            .lines().mapNotNull { l ->
                val f = l.split(",")
                val pts = f.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                Triple(pts, f.getOrNull(1)?.toDoubleOrNull() ?: 0.0, f.getOrNull(2)?.startsWith("K") == true)
            }

    /** From the first keyframe's presentation to the end of the last presented frame. */
    private fun videoDuration(path: String): Double {
        val p = videoPackets(path)
        return p.maxOf { it.first + it.second } - p.first { it.third }.first
    }

    private fun hls(name: String, vararg encode: String) {
        run(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "4", *encode, "-c:a", "aac",
            "-f", "hls", "-hls_time", "1", "-hls_list_size", "0",
            "-hls_segment_filename", path("$name%d.ts"), path("$name.m3u8"))
    }

    // ---- R5: the presentation starts at the first keyframe ----

    private fun openGopStartsAtTheKeyframe(name: String, vararg encode: String) {
        hls(name, *encode)
        // Segments 1-3: the first keyframe is a CRA / recovery-point I with leading pictures before it.
        val segments = files("$name\\d+\\.ts").drop(1)
        assertEquals(3, segments.size, "segments 1-3")
        val reference = path("${name}_ffmpeg.mp4")
        run(ffmpeg!!, "-y", "-loglevel", "error", "-i", "concat:" + segments.joinToString("|"), "-c", "copy", reference)
        for (ext in listOf("mp4", "mkv")) {
            val out = path("${name}_out.$ext")
            val m = TransmuxingMuxer("/nonexistent-appdir", ext)
            assert(m.mux(segments, out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
            assertEquals(OutputKind.REMUXED, m.outputKind, "$ext remuxed")
            if (ext == "mp4") {
                // Time zero is the first video frame (D14); ffmpeg's file starts with the audio instead.
                val first = firstVideoFrame(out)
                assertTrue(first < 0.005, "$name: first frame shown at $first s, expected 0")
            } else {
                // No edit list: the leading pictures are left out, so the keyframe is presented first.
                val p = videoPackets(out)
                assertEquals(p.first { it.third }.first, p.minOf { it.first }, 1e-6, "$name mkv: a frame presented before the keyframe")
            }
            val d = videoDuration(out)
            val ref = videoDuration(reference)
            assertEquals(ref, d, 0.045, "$name $ext: video lasts $d s, ffmpeg's $ref s")
        }
    }

    @Test
    fun hevcOpenGopStartsAtTheCra() {
        requireFfmpeg()
        openGopStartsAtTheKeyframe("x265og", "-c:v", "libx265", "-pix_fmt", "yuv420p",
            "-x265-params", "log-level=error:open-gop=1:keyint=25:min-keyint=25:bframes=3:scenecut=0")
    }

    @Test
    fun h264OpenGopStartsAtTheRecoveryPoint() {
        requireFfmpeg()
        openGopStartsAtTheKeyframe("x264og", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25", "-bf", "3",
            "-x264-params", "open-gop=1:repeat-headers=1:scenecut=0")
    }

    // ---- R6: several audio tracks ----

    /** Per track, in file order: tkhd flags, alternate_group and the mdhd language (both box versions 0). */
    private fun trackHeaders(path: String): List<Triple<Int, Int, String>> {
        val b = File(path).readBytes()
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun find(type: String, from: Int): Int {
            val t = type.toByteArray()
            for (i in from until b.size - 4) if (b[i] == t[0] && b[i + 1] == t[1] && b[i + 2] == t[2] && b[i + 3] == t[3]) return i
            return -1
        }
        val out = ArrayList<Triple<Int, Int, String>>()
        var at = find("tkhd", 0)
        while (at >= 0) {
            val flags = (u16(at + 5) shl 8) or (b[at + 7].toInt() and 0xFF)
            val group = u16(at + 4 + 4 + 20 + 8 + 2)
            val mdhd = find("mdhd", at)
            val l = u16(mdhd + 4 + 4 + 16)
            val lang = String(charArrayOf('`' + ((l shr 10) and 31), '`' + ((l shr 5) and 31), '`' + (l and 31)))
            out += Triple(flags, group, lang)
            at = find("tkhd", mdhd)
        }
        return out
    }

    @Test
    fun secondAudioTrackIsAnAlternativeWithItsLanguage() {
        requireFfmpeg()
        val ts = path("lang.ts")
        run(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000",
            "-t", "2", "-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
            "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=fra", ts)
        val reference = path("lang_ffmpeg.mp4")
        run(ffmpeg!!, "-y", "-loglevel", "error", "-i", ts, "-map", "0", "-c", "copy", reference)
        val out = path("lang_out.mp4")
        val m = TransmuxingMuxer("/nonexistent-appdir", "mp4")
        assert(m.mux(listOf(ts), out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
        val expected = listOf(Triple(3, 0, "und"), Triple(3, 1, "eng"), Triple(2, 1, "fra"))
        assertEquals(expected, trackHeaders(reference), "ffmpeg's flags, groups, languages")
        assertEquals(expected, trackHeaders(out), "flags, groups, languages as ffmpeg")

        val mkv = path("lang_out.mkv")
        val k = TransmuxingMuxer("/nonexistent-appdir", "mkv")
        assert(k.mux(listOf(ts), mkv, {}, workDir.path, false, false)) { "mkv mux failed: ${k.lastError}" }
        val tags = probe(mkv, "-show_entries", "stream=index:stream_tags=language:stream_disposition=default", "-of", "csv=p=0")
            .lines().filter { it.isNotBlank() }
        // ffprobe leaves an "und" language out.
        assertEquals(listOf("0,1", "1,1,eng", "2,0,fra").map { it.split(",") }, tags.map { it.split(",").take(3) }, "mkv default flags and languages")
    }

    // ---- R8: audio rates above 65535 ----

    /** The 16.16 samplerate field of the first mp4a sample entry, integer part. */
    private fun mp4aRate(path: String): Int {
        val b = File(path).readBytes()
        val t = "mp4a".toByteArray()
        val at = (0 until b.size - 4).first { b[it] == t[0] && b[it + 1] == t[1] && b[it + 2] == t[2] && b[it + 3] == t[3] }
        // type, 6 reserved, data_reference_index, 8 version/revision/vendor, channels, samplesize, 4 pre_defined.
        val o = at + 4 + 6 + 2 + 8 + 2 + 2 + 4
        return ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    }

    @Test
    fun highAudioRatesAreHalvedInTheSampleEntryAsFfmpeg() {
        requireFfmpeg()
        for (rate in listOf(96000, 88200, 64000)) {
            val ts = path("rate$rate.ts")
            run(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=$rate",
                "-t", "1", "-c:a", "aac", "-ar", "$rate", ts)
            val reference = path("rate${rate}_ffmpeg.mp4")
            run(ffmpeg!!, "-y", "-loglevel", "error", "-i", ts, "-c", "copy", reference)
            val out = path("rate${rate}_out.mp4")
            val m = TransmuxingMuxer("/nonexistent-appdir", "mp4")
            assert(m.mux(listOf(ts), out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
            assertEquals(mp4aRate(reference), mp4aRate(out), "$rate Hz: sample entry rate as ffmpeg's")
            assertEquals("$rate", probe(out, "-show_entries", "stream=sample_rate", "-of", "csv=p=0"), "$rate Hz decodes at its rate")
        }
    }

    // ---- R14: AAC / Opus pre-roll groups ----

    /** The first [type] box of [path] (header included), or null. */
    private fun box(path: String, type: String): ByteArray? {
        val b = File(path).readBytes()
        val t = type.toByteArray()
        val at = (4 until b.size - 4).firstOrNull { b[it] == t[0] && b[it + 1] == t[1] && b[it + 2] == t[2] && b[it + 3] == t[3] } ?: return null
        val size = ((b[at - 4].toInt() and 0xFF) shl 24) or ((b[at - 3].toInt() and 0xFF) shl 16) or
            ((b[at - 2].toInt() and 0xFF) shl 8) or (b[at - 1].toInt() and 0xFF)
        return b.copyOfRange(at - 4, at - 4 + size)
    }

    @Test
    fun rollGroupsAsFfmpegWritesThem() {
        requireFfmpeg()
        for ((name, codec) in listOf("aac" to arrayOf("-c:a", "aac"), "opus" to arrayOf("-c:a", "libopus", "-ac", "2"))) {
            val ts = path("roll_$name.ts")
            try {
                run(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "2", *codec, ts)
            } catch (_: IllegalStateException) { continue } // an ffmpeg build without libopus
            val reference = path("roll_${name}_ffmpeg.mp4")
            run(ffmpeg!!, "-y", "-loglevel", "error", "-i", ts, "-c", "copy", reference)
            val out = path("roll_${name}_out.mp4")
            val m = TransmuxingMuxer("/nonexistent-appdir", "mp4")
            assert(m.mux(listOf(ts), out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
            for (type in listOf("sgpd", "sbgp")) {
                val ref = box(reference, type)
                assertTrue(ref != null, "$name: ffmpeg writes $type")
                org.junit.jupiter.api.Assertions.assertArrayEquals(ref, box(out, type), "$name: $type as ffmpeg's")
            }
        }
    }

    // ---- R15: esds rates ----

    /** bufferSizeDB, maxBitrate, avgBitrate of the first esds (4-byte descriptor sizes, as both write them). */
    private fun esdsRates(path: String): List<Long> {
        val e = box(path, "esds")!!
        fun u(o: Int, n: Int) = (0 until n).fold(0L) { v, k -> (v shl 8) or (e[o + k].toLong() and 0xFF) }
        val at = 12 + 1 + 4 + 3 + 1 + 4 + 2
        return listOf(u(at, 3), u(at + 3, 4), u(at + 7, 4))
    }

    @Test
    fun esdsCarriesTheTracksBitrates() {
        requireFfmpeg()
        val ts = path("esds.ts")
        run(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "5",
            "-c:a", "aac", "-b:a", "96k", ts)
        val reference = path("esds_ffmpeg.mp4")
        run(ffmpeg!!, "-y", "-loglevel", "error", "-i", ts, "-c", "copy", reference)
        val out = path("esds_out.mp4")
        val m = TransmuxingMuxer("/nonexistent-appdir", "mp4")
        assert(m.mux(listOf(ts), out, {}, workDir.path, false, false)) { "mux failed: ${m.lastError}" }
        val (buffer, max, avg) = esdsRates(out)
        val ffAvg = esdsRates(reference)[2]
        assertEquals(ffAvg.toDouble(), avg.toDouble(), ffAvg * 0.02, "average bitrate as ffmpeg's ($ffAvg)")
        assertTrue(max >= avg, "max $max >= avg $avg")
        assertTrue(buffer in 1..6144, "bufferSizeDB $buffer: the largest frame")
    }
}
