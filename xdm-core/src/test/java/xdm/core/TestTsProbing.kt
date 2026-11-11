// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.media.muxer.transmux.ts.EsProbe
import xdm.core.media.muxer.transmux.ts.StreamReader
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * TS streams the PMT doesn't describe: video under private data (0x06), 0x80 or an unknown type
 * (0xD0), a private stream with descriptors, and a TS with no PAT/PMT at all. Each must give the
 * same media as the stream with its proper PMT. Fixtures from ffmpeg (skipped without it), patched.
 */
class TestTsProbing {

    companion object {
        private val workDir = File(System.getProperty("java.io.tmpdir"), "xdm-ts-probing")
        private var ffmpeg: String? = null
        private val codecs = listOf(
            "h264" to arrayOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac"),
            "hevc" to arrayOf("-c:v", "libx265", "-c:a", "aac"),
            "mpeg2" to arrayOf("-c:v", "mpeg2video", "-c:a", "mp2"),
            "mpeg4" to arrayOf("-c:v", "mpeg4", "-c:a", "aac"),
        )

        @BeforeAll
        @JvmStatic
        fun setup() {
            ffmpeg = which("ffmpeg")
            if (ffmpeg == null) return
            workDir.mkdirs()
            for ((name, args) in codecs) {
                runSoft(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
                    "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "4", "-g", "25", *args, "-f", "mpegts",
                    File(workDir, "$name.ts").path)
            }
            // Long enough that the packets held back without a PMT run past the auto-guess window.
            runSoft(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=25",
                "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "20", "-g", "25", "-c:v", "libx264",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-f", "mpegts", File(workDir, "long.ts").path)
        }

        private fun which(cmd: String): String? {
            val path = System.getenv("PATH") ?: return null
            return path.split(File.pathSeparator).map { File(it, cmd) }.firstOrNull { it.canExecute() }?.absolutePath
        }

        private fun runSoft(vararg cmd: String) {
            try {
                val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                p.inputStream.readBytes(); p.waitFor()
            } catch (_: Exception) { /* fixture won't exist */ }
        }
    }

    private fun requireFfmpeg() = Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not on PATH; skipping")
    private fun fixture(name: String) = File(workDir, name).also { Assumptions.assumeTrue(it.length() > 0, "no $name") }

    // ---- TS patching ----

    private fun pid(b: ByteArray, o: Int) = ((b[o + 1].toInt() and 0x1F) shl 8) or (b[o + 2].toInt() and 0xFF)
    private fun pusi(b: ByteArray, o: Int) = b[o + 1].toInt() and 0x40 != 0
    private fun payload(b: ByteArray, o: Int): Int {
        var p = o + 4
        if (b[o + 3].toInt() and 0x20 != 0) p += 1 + (b[p].toInt() and 0xFF)
        return p
    }

    private fun pmtPid(b: ByteArray): Int {
        for (o in 0 until b.size - 187 step 188) {
            if (pid(b, o) != 0 || !pusi(b, o)) continue
            val p = payload(b, o).let { it + 1 + (b[it].toInt() and 0xFF) }
            return ((b[p + 10].toInt() and 0x1F) shl 8) or (b[p + 11].toInt() and 0xFF)
        }
        error("no PAT")
    }

    /**
     * Runs [edit] on each PMT section start (section offset, ES-loop start, section end without CRC).
     * The CRC isn't updated: the demuxer doesn't check it.
     */
    private fun eachPmt(b: ByteArray, edit: (p: Int, loop: Int, end: Int) -> Unit) {
        val pmt = pmtPid(b)
        for (o in 0 until b.size - 187 step 188) {
            if (pid(b, o) != pmt || !pusi(b, o)) continue
            val p = payload(b, o).let { it + 1 + (b[it].toInt() and 0xFF) }
            val sectionLength = ((b[p + 1].toInt() and 0x0F) shl 8) or (b[p + 2].toInt() and 0xFF)
            val programInfo = ((b[p + 10].toInt() and 0x0F) shl 8) or (b[p + 11].toInt() and 0xFF)
            edit(p, p + 12 + programInfo, p + 3 + sectionLength - 4)
        }
    }

    private val videoTypes = setOf(0x1B, 0x24, 0x02, 0x10)

    /** [src] with the video's PMT stream type set to [type], its registration renamed and [descriptor] added. */
    private fun patched(src: File, name: String, type: Int, descriptor: ByteArray? = null): String {
        val b = src.readBytes()
        eachPmt(b) { p, loop, end ->
            var q = loop
            while (q + 5 <= end) {
                val infoLen = ((b[q + 3].toInt() and 0x0F) shl 8) or (b[q + 4].toInt() and 0xFF)
                if ((b[q].toInt() and 0xFF) in videoTypes) {
                    b[q] = type.toByte()
                    // An "HEVC" registration would name the codec: rename it so the content decides.
                    for (d in q + 5 until q + 5 + infoLen - 3) {
                        if (String(b, d, 4, Charsets.US_ASCII) == "HEVC") "XXXX".toByteArray().copyInto(b, d)
                    }
                    if (descriptor != null) {
                        // Insert after the ES_info_length; the stuffing after the section makes room.
                        val at = q + 5
                        val pktEnd = p - (p % 188) + 188
                        System.arraycopy(b, at, b, at + descriptor.size, pktEnd - at - descriptor.size)
                        descriptor.copyInto(b, at)
                        val newInfo = infoLen + descriptor.size
                        b[q + 3] = ((b[q + 3].toInt() and 0xF0) or (newInfo shr 8)).toByte(); b[q + 4] = newInfo.toByte()
                        val sl = (((b[p + 1].toInt() and 0x0F) shl 8) or (b[p + 2].toInt() and 0xFF)) + descriptor.size
                        b[p + 1] = ((b[p + 1].toInt() and 0xF0) or (sl shr 8)).toByte(); b[p + 2] = sl.toByte()
                    }
                    return@eachPmt
                }
                q += 5 + infoLen
            }
        }
        return File(workDir, name).also { it.writeBytes(b) }.absolutePath
    }

    /** [src] without its PAT, PMT and SDT packets. */
    private fun withoutPsi(src: File, name: String): String {
        val b = src.readBytes()
        val pmt = pmtPid(b)
        val out = ByteArrayOutputStream()
        for (o in 0 until b.size - 187 step 188) if (pid(b, o) !in setOf(0, pmt, 0x11)) out.write(b, o, 188)
        return File(workDir, name).also { it.writeBytes(out.toByteArray()) }.absolutePath
    }

    // ---- muxing and comparing ----

    private fun mux(src: String, out: String): Boolean =
        TransmuxingMuxer("/nonexistent-appdir").mux(listOf(src), out, {}, workDir.path, false, false)

    /** md5 of the decoded [kind] ("v" or "a") stream of [path], or "" when it has none. */
    private fun md5(path: String, kind: String): String {
        // ffmpeg prints an md5 even when an optional map matches nothing: look for the stream first.
        val probe = ProcessBuilder(which("ffprobe") ?: return "?", "-v", "error", "-select_streams", kind,
            "-show_entries", "stream=index", "-of", "csv=p=0", path).redirectErrorStream(true).start()
        val streams = probe.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        probe.waitFor()
        if (streams.isEmpty()) return ""
        val p = ProcessBuilder(ffmpeg!!, "-v", "error", "-i", path, "-map", "0:$kind", "-f", "md5", "-").redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    private fun assertSameMedia(reference: String, actual: String, label: String, audio: Boolean = true) {
        val v = md5(reference, "v")
        assert(v.isNotEmpty()) { "$label: reference has no video" }
        assertEquals(v, md5(actual, "v"), "$label: video differs")
        if (audio) assertEquals(md5(reference, "a"), md5(actual, "a"), "$label: audio differs")
    }

    private fun reference(codec: String): String {
        val out = File(workDir, "${codec}_ref.mp4").absolutePath
        assert(mux(fixture("$codec.ts").absolutePath, out)) { "$codec: reference mux failed" }
        return out
    }

    @Test
    fun videoUnderUnknownStreamTypesIsIdentified() {
        requireFfmpeg()
        for ((codec, _) in codecs) {
            if (File(workDir, "$codec.ts").length() == 0L) continue // encoder missing
            val ref = reference(codec)
            for (type in listOf(0x06, 0x80, 0xD0)) {
                val label = "$codec as 0x${Integer.toHexString(type)}"
                val out = File(workDir, "${codec}_${type}.mp4").absolutePath
                assert(mux(patched(fixture("$codec.ts"), "${codec}_$type.ts", type), out)) { "$label: mux failed" }
                assertSameMedia(ref, out, label)
            }
        }
    }

    @Test
    fun privateStreamWithDescriptorsIsProbedUnlessTheySayNotAv() {
        requireFfmpeg()
        val ref = reference("h264")
        // A stream_identifier_descriptor says nothing about the codec: the content decides.
        val out = File(workDir, "h264_streamid.mp4").absolutePath
        assert(mux(patched(fixture("h264.ts"), "h264_streamid.ts", 0x06, byteArrayOf(0x52, 1, 7)), out)) { "mux failed" }
        assertSameMedia(ref, out, "0x06 + stream_identifier")
        // A teletext_descriptor marks it as not audio/video: left out, audio only.
        val teletext = File(workDir, "h264_teletext.mp4").absolutePath
        assert(mux(patched(fixture("h264.ts"), "h264_teletext.ts", 0x06, byteArrayOf(0x56, 5, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0x09, 0)), teletext)) { "mux failed" }
        assertEquals("", md5(teletext, "v"), "a stream marked as teletext was muxed as video")
        assertEquals(md5(ref, "a"), md5(teletext, "a"), "audio differs")
    }

    @Test
    fun streamsWithoutPatOrPmtAreGuessed() {
        requireFfmpeg()
        for ((codec, _) in codecs) {
            if (File(workDir, "$codec.ts").length() == 0L) continue
            val ref = reference(codec)
            val out = File(workDir, "${codec}_nopsi.mp4").absolutePath
            assert(mux(withoutPsi(fixture("$codec.ts"), "${codec}_nopsi.ts"), out)) { "$codec without PSI: mux failed" }
            assertSameMedia(ref, out, "$codec without PSI")
        }
        // Past the window: the guess is made while the stream goes on.
        val long = fixture("long.ts")
        val ref = File(workDir, "long_ref.mp4").absolutePath
        assert(mux(long.absolutePath, ref)) { "long reference mux failed" }
        val out = File(workDir, "long_nopsi.mp4").absolutePath
        assert(mux(withoutPsi(long, "long_nopsi.ts"), out)) { "long without PSI: mux failed" }
        assertSameMedia(ref, out, "long without PSI")
    }

    // ---- the content probe on its own ----

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun probeTellsTheVideoFormatsApart() {
        val h264Aud = bytes(0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x41, 0x9A, 0x22)
        val hevcAud = bytes(0, 0, 0, 1, 0x46, 0x01, 0x50, 0, 0, 0, 1, 0x02, 0x01, 0xD0)
        val hevcParams = bytes(0, 0, 1, 0x40, 0x01, 0x0C, 0, 0, 1, 0x42, 0x01, 0x01, 0, 0, 1, 0x44, 0x01, 0xC1)
        val h264Params = bytes(0, 0, 1, 0x67, 0x64, 0x00, 0x1F, 0, 0, 1, 0x68, 0xEB, 0xE3, 0, 0, 1, 0x65, 0x88)
        val mpeg2 = bytes(0, 0, 1, 0xB3, 0x14, 0x00, 0xF0, 0, 0, 1, 0xB8, 0x00, 0, 0, 1, 0x00, 0x00, 0x0F)
        val mpeg4 = bytes(0, 0, 1, 0xB3, 0x00, 0x10, 0, 0, 1, 0xB6, 0x10, 0x60)
        // An MPEG-2 picture with slices whose codes look like an H.264 SPS/PPS and an AUD.
        val mpeg2Picture = bytes(0, 0, 1, 0x00, 0x01, 0xFF, 0, 0, 1, 0x09, 0x10, 0, 0, 1, 0x67, 0x64, 0x00,
            0, 0, 1, 0x68, 0x10, 0, 0, 1, 0x46, 0x01, 0x50)
        val video = 0xE0
        assertEquals(StreamReader.H264, EsProbe.identify(h264Aud, 0, h264Aud.size, video))
        assertEquals(StreamReader.H265, EsProbe.identify(hevcAud, 0, hevcAud.size, video))
        assertEquals(StreamReader.H265, EsProbe.identify(hevcParams, 0, hevcParams.size, video))
        assertEquals(StreamReader.H264, EsProbe.identify(h264Params, 0, h264Params.size, video))
        assertEquals(StreamReader.MPEG_VIDEO, EsProbe.identify(mpeg2, 0, mpeg2.size, video))
        assertEquals(StreamReader.MPEG4_VISUAL, EsProbe.identify(mpeg4, 0, mpeg4.size, video), "B3 + B6 is MPEG-4's GOV, not MPEG-2")
        assertNull(EsProbe.identify(mpeg2Picture, 0, mpeg2Picture.size, video), "an MPEG-2 picture taken for a NAL stream")
        // Audio sync words still win on an audio stream_id.
        val adts = bytes(0xFF, 0xF1, 0x50, 0x80, 0x01, 0x7F, 0xFC, 0x21)
        assertEquals(StreamReader.ADTS, EsProbe.identify(adts, 0, adts.size, 0xC0))
    }
}
