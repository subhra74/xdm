package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.MuxContainer
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.media.muxer.transmux.ogg.OggWriter
import java.io.File

/**
 * Ogg Opus output (`.opus`): Opus from WebM moved into Ogg without re-encoding, and the fallback to
 * Matroska audio for anything that isn't one Opus track. Fixtures come from ffmpeg; those tests skip
 * without it (or without libopus).
 */
class TestOggOutput {

    companion object {
        private val workDir = File(System.getProperty("java.io.tmpdir"), "xdm-ogg-output")
        private var ffmpeg: String? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            ffmpeg = which("ffmpeg")
            if (ffmpeg == null) return
            workDir.deleteRecursively()
            workDir.mkdirs()
            val a = arrayOf("-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "5")
            runSoft(ffmpeg!!, *a, "-c:a", "libopus", "-b:a", "96k", "-ac", "2", File(workDir, "audio.webm").path)
            runSoft(ffmpeg!!, *a, "-c:a", "aac", "-ac", "2", File(workDir, "audio.m4a").path)
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            workDir.deleteRecursively()
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

    private fun fixture(name: String): File {
        Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not on PATH; skipping")
        val f = File(workDir, name)
        Assumptions.assumeTrue(f.length() > 0, "no $name fixture (encoder missing?)")
        return f
    }

    private fun tool(name: String, vararg args: String): String {
        val exe = File(File(ffmpeg!!).parentFile, name).absolutePath
        val p = ProcessBuilder(exe, *args).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    private fun decodedMd5(path: String) = tool("ffmpeg", "-v", "error", "-i", path, "-map", "0:a", "-f", "md5", "-")

    @Test
    fun opusFrameLengthsComeFromTheToc() {
        // config 31 (CELT 20 ms), one frame
        assertEquals(960, OggWriter.samplesIn(byteArrayOf((31 shl 3).toByte(), 0)))
        // config 1 (SILK 20 ms), two frames (code 1)
        assertEquals(1920, OggWriter.samplesIn(byteArrayOf(((1 shl 3) or 1).toByte(), 0)))
        // config 16 (CELT 2.5 ms), code 3 with 6 frames
        assertEquals(720, OggWriter.samplesIn(byteArrayOf(((16 shl 3) or 3).toByte(), 6)))
        // config 3 (SILK 60 ms), one frame
        assertEquals(2880, OggWriter.samplesIn(byteArrayOf((3 shl 3).toByte())))
        assertEquals(0, OggWriter.samplesIn(ByteArray(0)), "an empty packet holds no audio")
    }

    @Test
    fun opusIsKeptAsThePlannedContainer() {
        assertEquals("opus", MuxContainer.plan("opus", matroskaInput = true), "WebM input still goes to .opus")
        assertEquals("opus", MuxContainer.plan(".OPUS", matroskaInput = false))
    }

    @Test
    fun webmOpusBecomesOggOpusWithTheSameAudio() {
        val src = fixture("audio.webm")
        val out = File(workDir, "audio.opus")
        val muxer = TransmuxingMuxer("/nonexistent-appdir", "opus")
        assertTrue(muxer.mux(listOf(src.path), out.path, {}, workDir.path, false, false), "mux failed: ${muxer.lastError}")
        assertEquals("opus", muxer.outputContainer)
        val head = out.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII)
        assertEquals("OggS", head, "the file starts with an Ogg page")

        val probe = tool("ffprobe", "-v", "error", "-show_entries", "format=format_name,duration:stream=codec_name",
            "-of", "default=nw=1", out.path)
        assertTrue(probe.contains("codec_name=opus"), "ffprobe: $probe")
        assertTrue(probe.contains("format_name=ogg"), "ffprobe: $probe")
        val duration = Regex("duration=([0-9.]+)").find(probe)!!.groupValues[1].toDouble()
        assertTrue(duration in 4.9..5.1, "lasts $duration s, expected about 5 s")
        // ffmpeg checks every page's CRC: a bad one is a demux error.
        val errors = tool("ffmpeg", "-v", "error", "-i", out.path, "-f", "null", "-")
        assertEquals("", errors, "decode errors")
        assertEquals(decodedMd5(src.path), decodedMd5(out.path), "same decoded audio as the WebM (pre-skip kept)")
    }

    @Test
    fun nonOpusAskedAsOpusFallsBackToMka() {
        val src = fixture("audio.m4a")
        val out = File(workDir, "aac.opus")
        val muxer = TransmuxingMuxer("/nonexistent-appdir", "opus")
        assertTrue(muxer.mux(listOf(src.path), out.path, {}, workDir.path, false, false), "mux failed: ${muxer.lastError}")
        assertEquals("mka", muxer.outputContainer, "AAC can't go into Ogg Opus: Matroska audio instead")
        val probe = tool("ffprobe", "-v", "error", "-show_entries", "format=format_name:stream=codec_name", "-of", "default=nw=1", out.path)
        assertTrue(probe.contains("codec_name=aac") && probe.contains("matroska"), "ffprobe: $probe")
    }
}
