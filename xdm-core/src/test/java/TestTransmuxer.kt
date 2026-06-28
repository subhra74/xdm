import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.media.muxer.transmux.mp4.Mp4Writer
import xdm.core.media.muxer.transmux.ts.TsDemuxer
import java.io.File
import java.io.FileInputStream

/**
 * End-to-end transmux tests. Fixtures (HLS .ts segments) are generated with ffmpeg if it is on the
 * PATH; otherwise every test is skipped via JUnit Assume so the build stays green without ffmpeg.
 */
class TestTransmuxer {

    companion object {
        private val workDir = File(System.getProperty("java.io.tmpdir"), "xdm-transmux-test")
        private var ffmpeg: String? = null

        @BeforeClass
        @JvmStatic
        fun setup() {
            ffmpeg = which("ffmpeg")
            if (ffmpeg == null) return
            workDir.mkdirs()
            // 3s clip -> 1s muxed (A+V) TS segments: H.264 baseline + AAC.
            run(ffmpeg!!, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "3", "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p",
                "-g", "25", "-c:a", "aac", "-ar", "44100", "-ac", "2",
                "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
                "-hls_segment_filename", File(workDir, "m%d.ts").path, File(workDir, "m.m3u8").path)
            // 2s video-only (main profile, has B-frames) and audio-only TS segments.
            run(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
                "-t", "2", "-an", "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-g", "25",
                "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
                "-hls_segment_filename", File(workDir, "v%d.ts").path, File(workDir, "v.m3u8").path)
            run(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000",
                "-t", "2", "-vn", "-c:a", "aac", "-ar", "48000", "-ac", "2",
                "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
                "-hls_segment_filename", File(workDir, "a%d.ts").path, File(workDir, "a.m3u8").path)

            // Fragmented MP4 (CMAF): init fmp4.mp4 + media fmp%d.m4s, A+V together.
            run(ffmpeg!!, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "3", "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-g", "25",
                "-c:a", "aac", "-ar", "44100", "-ac", "2",
                "-f", "hls", "-hls_time", "1", "-hls_segment_type", "fmp4", "-hls_list_size", "0",
                "-hls_fmp4_init_filename", "fmp4.mp4",
                "-hls_segment_filename", File(workDir, "fmp%d.m4s").path, File(workDir, "fmp4.m3u8").path)

            // Progressive single-file MP4 (faststart so moov precedes mdat).
            run(ffmpeg!!, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "2", "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-g", "25",
                "-c:a", "aac", "-ar", "44100", "-ac", "2", "-movflags", "+faststart",
                File(workDir, "whole.mp4").path)
        }

        private fun which(cmd: String): String? {
            val path = System.getenv("PATH") ?: return null
            for (dir in path.split(File.pathSeparator)) {
                val f = File(dir, cmd)
                if (f.canExecute()) return f.absolutePath
            }
            return null
        }

        private fun run(vararg cmd: String) {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            check(p.waitFor() == 0) { "command failed: ${cmd.joinToString(" ")}" }
        }
    }

    private fun seg(prefix: String) = workDir.listFiles { f -> f.name.matches(Regex("$prefix\\d+\\.ts")) }
        ?.sortedBy { it.name }?.map { it.absolutePath } ?: emptyList()

    private fun requireFfmpeg() = Assume.assumeTrue("ffmpeg not on PATH; skipping", ffmpeg != null)

    @Test
    fun muxedSegmentsProduceBothTracks() {
        requireFfmpeg()
        val out = File(workDir, "result.mp4").absolutePath
        val ok = TransmuxingMuxer("/nonexistent-appdir").mux(seg("m"), out, {}, workDir.path, false, false)
        assert(ok) { "mux returned false" }
        assert(File(out).length() > 10_000) { "output too small" }
    }

    @Test
    fun separateAudioVideoPlaylists() {
        requireFfmpeg()
        val out = File(workDir, "merged.mp4").absolutePath
        val ok = TransmuxingMuxer("/nonexistent-appdir").mux(seg("a"), seg("v"), out, {}, workDir.path, false, false)
        assert(ok) { "a/v mux returned false" }
        assert(File(out).length() > 10_000) { "output too small" }
    }

    @Test
    fun fragmentedMp4InitPlusMedia() {
        requireFfmpeg()
        // The list is the init segment followed by media segments, in order.
        val media = workDir.listFiles { f -> f.name.matches(Regex("fmp\\d+\\.m4s")) }
            ?.sortedBy { it.name }?.map { it.absolutePath } ?: emptyList()
        val list = listOf(File(workDir, "fmp4.mp4").absolutePath) + media
        Assume.assumeTrue("no fmp4 segments", media.isNotEmpty())
        val out = File(workDir, "from_fmp4.mp4").absolutePath
        val ok = TransmuxingMuxer("/nonexistent-appdir").mux(list, out, {}, workDir.path, false, true)
        assert(ok) { "fmp4 mux returned false" }
        assert(File(out).length() > 10_000) { "output too small" }
    }

    @Test
    fun progressiveWholeMp4() {
        requireFfmpeg()
        val whole = File(workDir, "whole.mp4")
        Assume.assumeTrue("no whole.mp4", whole.exists())
        val out = File(workDir, "from_whole.mp4").absolutePath
        val ok = TransmuxingMuxer("/nonexistent-appdir").mux(listOf(whole.absolutePath), out, {}, workDir.path, true, true)
        assert(ok) { "progressive mux returned false" }
        assert(File(out).length() > 10_000) { "output too small" }
    }

    @Test
    fun demuxerRecoversTrackMetadata() {
        requireFfmpeg()
        val writer = Mp4Writer(File(workDir, "lowlevel.mp4").absolutePath)
        val demux = TsDemuxer(writer)
        val buf = ByteArray(256 * 1024)
        for (s in seg("m")) FileInputStream(s).use { ins ->
            while (true) { val n = ins.read(buf); if (n <= 0) break; demux.feed(buf, n) }
        }
        demux.finish()
        writer.finish(demux.tracks)

        val video = demux.tracks.first { it.codec.isVideo }
        val audio = demux.tracks.first { !it.codec.isVideo }
        assert(video.width == 320 && video.height == 240) { "bad video dims ${video.width}x${video.height}" }
        assert(video.samples.size == 75) { "expected 75 video frames, got ${video.samples.size}" }
        assert(audio.sampleRate == 44100 && audio.channelCount == 2) { "bad audio params" }
        assert(audio.isReady() && video.isReady())
    }
}
