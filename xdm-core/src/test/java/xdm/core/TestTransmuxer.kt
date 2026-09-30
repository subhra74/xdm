package xdm.core

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.iso.Mp4Demuxer
import xdm.core.media.muxer.transmux.mkv.MatroskaDemuxer
import xdm.core.media.muxer.transmux.mkv.MkvWriter
import xdm.core.media.muxer.transmux.mp4.Mp4Writer
import xdm.core.media.muxer.transmux.ts.TsDemuxer
import java.io.File
import java.io.FileInputStream

/** Discards sample bytes; used when only track metadata matters. */
private object NullSink : SampleSink {
    override fun writeSampleData(data: ByteArray, offset: Int, length: Int): Long = 0
}

/**
 * End-to-end transmux tests. Fixtures (HLS .ts segments) are generated with ffmpeg if it is on the
 * PATH; otherwise every test is skipped via JUnit Assume so the build stays green without ffmpeg.
 */
class TestTransmuxer {

    companion object {
        private val workDir = File(System.getProperty("java.io.tmpdir"), "xdm-transmux-test")
        private var ffmpeg: String? = null

        @BeforeAll
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

            // WebM (Matroska) fixtures for the MKV path: VP9 + Opus. Encoders may be absent, so these
            // are best-effort (runSoft); the webm tests Assume the fixtures exist and skip otherwise.
            runSoft(ffmpeg!!, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "2", "-c:v", "libvpx-vp9", "-b:v", "300k", "-g", "25", "-pix_fmt", "yuv420p",
                "-c:a", "libopus", "-ar", "48000", "-ac", "2",
                File(workDir, "whole.webm").path)
            runSoft(ffmpeg!!, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
                "-t", "2", "-an", "-c:v", "libvpx-vp9", "-b:v", "300k", "-g", "25", "-pix_fmt", "yuv420p",
                File(workDir, "v.webm").path)
            runSoft(ffmpeg!!, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000",
                "-t", "2", "-vn", "-c:a", "libopus", "-ar", "48000", "-ac", "2",
                File(workDir, "a.webm").path)

            // HLS packed audio: raw ADTS segments, each behind an ID3 PRIV transportStreamTimestamp
            // (as RFC 8216 requires), plus raw AC-3 segments without any ID3 tag.
            run(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "4", "-c:a", "aac", "-f", "segment", "-segment_time", "2", "-segment_format", "adts",
                File(workDir, "raw%d.aac").path)
            workDir.listFiles { f -> f.name.matches(Regex("raw\\d+\\.aac")) }!!.sortedBy { it.name }
                .forEachIndexed { i, f ->
                    File(workDir, "packed$i.aac").writeBytes(id3Timestamp(126_000L + i * 2 * 90_000L) + f.readBytes())
                }
            run(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "4", "-c:a", "ac3", "-f", "segment", "-segment_time", "2", "-segment_format", "ac3",
                File(workDir, "packed%d.ac3").path)

            // TS with MP2 audio (MPEG-1 Layer II), and TS with Opus audio (encoder may be absent).
            run(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "2", "-c:a", "mp2", "-f", "mpegts", File(workDir, "mp2_0.ts").path)
            runSoft(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "2", "-c:a", "libopus", "-ac", "2", "-f", "mpegts", File(workDir, "opus_0.ts").path)
            // Muxed A+V TS with B-frames: audio starts ~21 ms before video, which must survive remux.
            run(ffmpeg!!, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "4", "-c:v", "libx264", "-profile:v", "main", "-bf", "3", "-pix_fmt", "yuv420p", "-g", "30",
                "-c:a", "aac", "-f", "hls", "-hls_time", "2", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
                "-hls_segment_filename", File(workDir, "bf%d.ts").path, File(workDir, "bf.m3u8").path)
            // A second fMP4 rendition with a different resolution (a new EXT-X-MAP after a discontinuity).
            run(ffmpeg!!, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=640x360:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100",
                "-t", "2", "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-g", "25",
                "-c:a", "aac", "-ar", "44100", "-ac", "2",
                "-f", "hls", "-hls_time", "1", "-hls_segment_type", "fmp4", "-hls_list_size", "0",
                "-hls_fmp4_init_filename", "fmp4b.mp4",
                "-hls_segment_filename", File(workDir, "fmpb%d.m4s").path, File(workDir, "fmp4b.m3u8").path)
            // Fragmented MP4 audio whose MKV mapping needs a codec-specific CodecPrivate / OTI lookup.
            for ((name, codec) in listOf("flac" to "flac", "alac" to "alac", "mp3" to "libmp3lame")) {
                runSoft(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                    "-t", "2", "-c:a", codec, "-movflags", "frag_keyframe+empty_moov",
                    File(workDir, "audio_$name.mp4").path)
            }
            // Fragmented MP4 with a subtitle (mov_text) track next to A/V.
            File(workDir, "subs.srt").writeText("1\n00:00:00,000 --> 00:00:01,500\nhello\n\n")
            run(ffmpeg!!, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-i", File(workDir, "subs.srt").path,
                "-map", "0:v", "-map", "1:a", "-map", "2:s", "-t", "2",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-c:s", "mov_text",
                "-movflags", "frag_keyframe+empty_moov", File(workDir, "with_subs.mp4").path)
            // Fragmented MP4 carrying Opus (the MP4 `Opus`/dOps sample entry).
            runSoft(ffmpeg!!, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "2", "-c:a", "libopus", "-ac", "2", "-movflags", "frag_keyframe+empty_moov",
                File(workDir, "opus_frag.mp4").path)
        }

        /** An ID3v2.4 tag holding only the HLS packed-audio `transportStreamTimestamp` PRIV frame. */
        private fun id3Timestamp(pts90k: Long): ByteArray {
            val owner = "com.apple.streaming.transportStreamTimestamp\u0000".toByteArray(Charsets.US_ASCII)
            val payload = owner + ByteArray(8) { (pts90k ushr (56 - it * 8)).toByte() }
            val frame = "PRIV".toByteArray(Charsets.US_ASCII) + int32(payload.size) + byteArrayOf(0, 0) + payload
            val size = frame.size
            val syncsafe = byteArrayOf((size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(),
                (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte())
            return "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0) + syncsafe + frame
        }

        private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

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

        /** Like [run] but tolerates failure (e.g. a missing encoder), leaving the fixture absent. */
        private fun runSoft(vararg cmd: String) {
            try {
                val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                p.inputStream.readBytes()
                p.waitFor()
            } catch (_: Exception) { /* fixture simply won't exist */ }
        }
    }

    private fun seg(prefix: String) = workDir.listFiles { f -> f.name.matches(Regex("$prefix\\d+\\.ts")) }
        ?.sortedBy { it.name }?.map { it.absolutePath } ?: emptyList()

    private fun requireFfmpeg() = Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not on PATH; skipping")

    /** Runs ffprobe (if present) and returns the codec_type of each stream, else null. */
    private fun ffprobeStreams(path: String): List<String>? {
        val ffprobe = which("ffprobe") ?: return null
        val p = ProcessBuilder(
            ffprobe, "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", path
        ).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** ffprobe `codec_name,duration` of the first audio stream, e.g. ["aac", "4.032000"], else null. */
    private fun ffprobeAudio(path: String): List<String>? {
        val ffprobe = which("ffprobe") ?: return null
        val p = ProcessBuilder(
            ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name,duration",
            "-of", "csv=p=0", path
        ).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text.split(",")
    }

    /** ffprobe `entries` (e.g. "codec_type,start_time") for each stream of [path], one list per stream. */
    private fun ffprobe(path: String, entries: String): List<List<String>>? {
        val ffprobe = which("ffprobe") ?: return null
        val p = ProcessBuilder(ffprobe, "-v", "error", "-show_entries", "stream=$entries", "-of", "csv=p=0", path)
            .redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { it.split(",") }
    }

    /** Video start minus audio start, in seconds, as ffprobe reports it (edit lists applied). */
    private fun videoMinusAudioStart(path: String): Double? {
        val streams = ffprobe(path, "codec_type,start_time") ?: return null
        val v = streams.first { it[0] == "video" }[1].toDouble()
        val a = streams.first { it[0] == "audio" }[1].toDouble()
        return v - a
    }

    /**
     * Returns [ts] with an extra ID3 timed-metadata stream (stream_type 0x15, PID 0x102) declared in
     * every PMT, like HLS streams that carry an ID3 PID. The CRC isn't updated (the demuxer ignores it).
     */
    private fun withMetadataStream(ts: ByteArray): ByteArray {
        val out = ts.copyOf()
        fun payloadStart(off: Int): Int {
            var p = off + 4
            if ((out[off + 3].toInt() and 0x20) != 0) p += 1 + (out[p].toInt() and 0xFF)
            return p + 1 + (out[p].toInt() and 0xFF) // pointer_field
        }
        fun pid(off: Int) = ((out[off + 1].toInt() and 0x1F) shl 8) or (out[off + 2].toInt() and 0xFF)
        fun pusi(off: Int) = (out[off + 1].toInt() and 0x40) != 0
        var pmtPid = -1
        for (off in 0 until out.size - 187 step 188) {
            if (pid(off) != 0 || !pusi(off)) continue
            val p = payloadStart(off)
            pmtPid = ((out[p + 10].toInt() and 0x1F) shl 8) or (out[p + 11].toInt() and 0xFF)
            break
        }
        check(pmtPid > 0) { "no PAT" }
        for (off in 0 until out.size - 187 step 188) {
            if (pid(off) != pmtPid || !pusi(off)) continue
            val p = payloadStart(off)
            val sectionLength = ((out[p + 1].toInt() and 0x0F) shl 8) or (out[p + 2].toInt() and 0xFF)
            val crc = p + 3 + sectionLength - 4
            check(crc + 4 + 5 <= off + 188) { "no room in PMT packet" }
            System.arraycopy(out, crc, out, crc + 5, 4)
            byteArrayOf(0x15, 0xE1.toByte(), 0x02, 0xF0.toByte(), 0x00).copyInto(out, crc)
            val newLength = sectionLength + 5
            out[p + 1] = ((out[p + 1].toInt() and 0xF0) or (newLength shr 8)).toByte()
            out[p + 2] = newLength.toByte()
        }
        return out
    }

    /** Decodes the audio of [path] with ffmpeg and returns the error lines (empty = clean decode). */
    private fun audioDecodeErrors(path: String): List<String> = decodeErrors(path, "0:a")

    /** Decodes the [map]ped streams of [path] with ffmpeg and returns the error lines (empty = clean). */
    private fun decodeErrors(path: String, map: String = "0"): List<String> {
        val p = ProcessBuilder(ffmpeg!!, "-v", "error", "-i", path, "-map", map, "-f", "null", "-")
            .redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return text.lines().filter { it.isNotBlank() }
    }

    private fun files(regex: String) = workDir.listFiles { f -> f.name.matches(Regex(regex)) }
        ?.sortedBy { it.name }?.map { it.absolutePath } ?: emptyList()

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
        Assumptions.assumeTrue(media.isNotEmpty(), "no fmp4 segments")
        val out = File(workDir, "from_fmp4.mp4").absolutePath
        val ok = TransmuxingMuxer("/nonexistent-appdir").mux(list, out, {}, workDir.path, false, true)
        assert(ok) { "fmp4 mux returned false" }
        assert(File(out).length() > 10_000) { "output too small" }
    }

    @Test
    fun progressiveWholeMp4() {
        requireFfmpeg()
        val whole = File(workDir, "whole.mp4")
        Assumptions.assumeTrue(whole.exists(), "no whole.mp4")
        val out = File(workDir, "from_whole.mp4").absolutePath
        val ok = TransmuxingMuxer("/nonexistent-appdir").mux(listOf(whole.absolutePath), out, {}, workDir.path, true, true)
        assert(ok) { "progressive mux returned false" }
        assert(File(out).length() > 10_000) { "output too small" }
    }

    @Test
    fun webmToMkv() {
        requireFfmpeg()
        val whole = File(workDir, "whole.webm")
        Assumptions.assumeTrue(whole.exists() && whole.length() > 0, "no whole.webm (VP9/Opus encoders?)")
        val out = File(workDir, "from_webm.mkv")
        val ok = TransmuxingMuxer("/nonexistent-appdir")
            .mux(listOf(whole.absolutePath), out.absolutePath, {}, workDir.path, false, false)
        assert(ok) { "webm->mkv mux returned false" }
        assert(out.length() > 10_000) { "output too small" }
        // The output must sniff back as Matroska and carry both streams.
        val demux = MatroskaDemuxer(NullSink).also { it.parseSegment(out.absolutePath) }
        assert(demux.tracks.any { it.codec.isVideo } && demux.tracks.any { !it.codec.isVideo }) {
            "round-trip lost a track: ${demux.tracks.map { it.matroskaCodecId }}"
        }
        ffprobeStreams(out.absolutePath)?.let { streams ->
            assert(streams.contains("video") && streams.contains("audio")) { "ffprobe streams: $streams" }
        }
    }

    @Test
    fun separateWebmToMkv() {
        requireFfmpeg()
        val a = File(workDir, "a.webm"); val v = File(workDir, "v.webm")
        Assumptions.assumeTrue(a.exists() && v.exists() && a.length() > 0 && v.length() > 0, "no separate webm fixtures")
        val out = File(workDir, "merged.mkv")
        val ok = TransmuxingMuxer("/nonexistent-appdir")
            .mux(listOf(a.absolutePath), listOf(v.absolutePath), out.absolutePath, {}, workDir.path, false, false)
        assert(ok) { "separate webm->mkv mux returned false" }
        assert(out.length() > 10_000) { "output too small" }
        ffprobeStreams(out.absolutePath)?.let { streams ->
            assert(streams.contains("video") && streams.contains("audio")) { "ffprobe streams: $streams" }
        }
    }

    @Test
    fun matroskaDemuxerRecoversMetadata() {
        requireFfmpeg()
        val whole = File(workDir, "whole.webm")
        Assumptions.assumeTrue(whole.exists() && whole.length() > 0, "no whole.webm")
        val writer = MkvWriter(File(workDir, "lowlevel.mkv").absolutePath)
        val demux = MatroskaDemuxer(writer)
        demux.parseSegment(whole.absolutePath)
        val video = demux.tracks.first { it.codec.isVideo }
        val audio = demux.tracks.first { !it.codec.isVideo }
        assert(video.width == 320 && video.height == 240) { "bad video dims ${video.width}x${video.height}" }
        assert(video.matroskaCodecId == "V_VP9") { "unexpected video codec ${video.matroskaCodecId}" }
        assert(audio.matroskaCodecId == "A_OPUS") { "unexpected audio codec ${audio.matroskaCodecId}" }
        assert(video.samples.isNotEmpty() && audio.samples.isNotEmpty()) { "no samples recovered" }
        assert(writer.finish(demux.tracks)) { "MkvWriter.finish returned false" }
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

    @Test
    fun packedAdtsWithId3Timestamps() {
        requireFfmpeg()
        val segs = files("packed\\d+\\.aac")
        Assumptions.assumeTrue(segs.size >= 2, "no packed aac fixtures")
        val out = File(workDir, "packed_aac.mp4").absolutePath
        assert(TransmuxingMuxer("/nonexistent-appdir").mux(segs, out, {}, workDir.path, false, false)) {
            "packed aac mux returned false"
        }
        ffprobeAudio(out)?.let { (codec, duration) ->
            assert(codec == "aac") { "unexpected codec $codec" }
            assert(duration.toDouble() in 3.9..4.2) { "unexpected duration $duration" }
        }
        assert(audioDecodeErrors(out).isEmpty()) { "decode errors: ${audioDecodeErrors(out)}" }
    }

    @Test
    fun packedAc3WithoutId3() {
        requireFfmpeg()
        val segs = files("packed\\d+\\.ac3")
        Assumptions.assumeTrue(segs.size >= 2, "no packed ac3 fixtures")
        val out = File(workDir, "packed_ac3.mp4").absolutePath
        assert(TransmuxingMuxer("/nonexistent-appdir").mux(segs, out, {}, workDir.path, false, false)) {
            "packed ac3 mux returned false"
        }
        ffprobeAudio(out)?.let { (codec, duration) ->
            assert(codec == "ac3") { "unexpected codec $codec" }
            assert(duration.toDouble() in 3.9..4.2) { "unexpected duration $duration" }
        }
        assert(audioDecodeErrors(out).isEmpty()) { "decode errors: ${audioDecodeErrors(out)}" }
    }

    @Test
    fun mp2InTsIsLayerTwoAndDecodesCleanly() {
        requireFfmpeg()
        val seg = File(workDir, "mp2_0.ts").absolutePath
        val writer = Mp4Writer(File(workDir, "mp2.mp4").absolutePath)
        val demux = TsDemuxer(writer)
        val bytes = File(seg).readBytes()
        demux.feed(bytes, bytes.size)
        demux.finish()
        assert(writer.finish(demux.tracks)) { "no usable track" }
        val audio = demux.tracks.single()
        assert(audio.mpegAudioLayer == 2 && audio.sampleRate == 48000) {
            "layer=${audio.mpegAudioLayer} rate=${audio.sampleRate}"
        }
        // ~2s of 1152-sample frames at 48 kHz: no fake frames from payload bytes.
        assert(audio.samples.size in 80..86) { "unexpected frame count ${audio.samples.size}" }
        val errors = audioDecodeErrors(File(workDir, "mp2.mp4").absolutePath)
        assert(errors.isEmpty()) { "decode errors: $errors" }
    }

    @Test
    fun opusInTs() {
        requireFfmpeg()
        val seg = File(workDir, "opus_0.ts")
        Assumptions.assumeTrue(seg.exists() && seg.length() > 0, "no opus TS fixture (libopus?)")
        val out = File(workDir, "opus_ts.mp4").absolutePath
        assert(TransmuxingMuxer("/nonexistent-appdir").mux(listOf(seg.absolutePath), out, {}, workDir.path, false, false)) {
            "opus TS mux returned false"
        }
        ffprobeAudio(out)?.let { (codec, duration) ->
            assert(codec == "opus") { "unexpected codec $codec" }
            assert(duration.toDouble() in 1.9..2.2) { "unexpected duration $duration" }
        }
        assert(audioDecodeErrors(out).isEmpty()) { "decode errors: ${audioDecodeErrors(out)}" }
    }

    @Test
    fun bFrameH264ToMkvDecodesCleanly() {
        requireFfmpeg()
        // v*.ts is main profile with B-frames: blocks must be written in decode order, not PTS order.
        val out = File(workDir, "bframes.mkv").absolutePath
        assert(TransmuxingMuxer("/nonexistent-appdir").mux(seg("a"), seg("v"), out, {}, workDir.path, false, false)) {
            "ts->mkv mux returned false"
        }
        ffprobeStreams(out)?.let { streams ->
            assert(streams.contains("video") && streams.contains("audio")) { "ffprobe streams: $streams" }
        }
        val errors = decodeErrors(out)
        assert(errors.isEmpty()) { "decode errors: ${errors.take(5)}" }
    }

    @Test
    fun mp4OpusToMkvUsesOpusHead() {
        requireFfmpeg()
        val src = File(workDir, "opus_frag.mp4")
        Assumptions.assumeTrue(src.exists() && src.length() > 0, "no fragmented mp4 opus fixture (libopus?)")
        val out = File(workDir, "opus_from_mp4.mkv").absolutePath
        assert(TransmuxingMuxer("/nonexistent-appdir").mux(listOf(src.absolutePath), out, {}, workDir.path, false, true)) {
            "mp4 opus->mkv mux returned false"
        }
        ffprobeAudio(out)?.let { (codec, _) -> assert(codec == "opus") { "unexpected codec $codec" } }
        val errors = audioDecodeErrors(out)
        assert(errors.isEmpty()) { "decode errors: ${errors.take(5)}" }
    }

    @Test
    fun bFrameVideoKeepsAvSyncInMp4() {
        requireFfmpeg()
        val segs = seg("bf")
        val out = File(workDir, "bf_sync.mp4").absolutePath
        assert(TransmuxingMuxer("/nonexistent-appdir").mux(segs, out, {}, workDir.path, false, false)) {
            "mux returned false"
        }
        val expected = videoMinusAudioStart(segs.first()) ?: return
        val actual = videoMinusAudioStart(out)!!
        // Edit lists are in 1 ms movie ticks.
        assert(kotlin.math.abs(actual - expected) < 0.002) { "A/V offset $actual s, source has $expected s" }
    }

    @Test
    fun metadataStreamInTsIsSkipped() {
        requireFfmpeg()
        val patched = seg("m").mapIndexed { i, s ->
            File(workDir, "id3pid$i.ts").also { it.writeBytes(withMetadataStream(File(s).readBytes())) }.absolutePath
        }
        val out = File(workDir, "id3pid.mp4").absolutePath
        assert(TransmuxingMuxer("/nonexistent-appdir").mux(patched, out, {}, workDir.path, false, false)) {
            "mux with an ID3 PID returned false"
        }
        ffprobeStreams(out)?.let { streams ->
            assert(streams == listOf("video", "audio")) { "ffprobe streams: $streams" }
        }
    }

    @Test
    fun repeatedAndChangedInitSegmentsKeepAllSamples() {
        requireFfmpeg()
        val a = files("fmp\\d+\\.m4s")
        val b = files("fmpb\\d+\\.m4s")
        Assumptions.assumeTrue(a.isNotEmpty() && b.isNotEmpty(), "no fmp4 fixtures")
        val initA = File(workDir, "fmp4.mp4").absolutePath
        val initB = File(workDir, "fmp4b.mp4").absolutePath
        // Every media segment preceded by its EXT-X-MAP; the second rendition restarts at t=0.
        val list = a.flatMap { listOf(initA, it) } + b.flatMap { listOf(initB, it) }
        val out = File(workDir, "multi_init.mp4").absolutePath
        assert(TransmuxingMuxer("/nonexistent-appdir").mux(list, out, {}, workDir.path, false, true, true)) {
            "mux returned false"
        }
        ffprobe(out, "codec_type,nb_frames")?.let { streams ->
            val videoFrames = streams.first { it[0] == "video" }[1].toInt()
            assert(videoFrames == 125) { "expected 75 + 50 video frames, got $videoFrames" }
        }
        val errors = decodeErrors(out)
        assert(errors.isEmpty()) { "decode errors: ${errors.take(5)}" }
    }

    @Test
    fun mp4AudioCodecsMapToMkv() {
        requireFfmpeg()
        for ((name, expected) in listOf("flac" to "flac", "alac" to "alac", "mp3" to "mp3")) {
            val src = File(workDir, "audio_$name.mp4")
            if (!src.exists() || src.length() == 0L) continue // encoder missing
            val out = File(workDir, "audio_$name.mkv").absolutePath
            assert(TransmuxingMuxer("/nonexistent-appdir").mux(listOf(src.absolutePath), out, {}, workDir.path, false, true)) {
                "$name mp4->mkv mux returned false"
            }
            ffprobeAudio(out)?.let { (codec, _) -> assert(codec == expected) { "$name: unexpected codec $codec" } }
            val errors = audioDecodeErrors(out)
            assert(errors.isEmpty()) { "$name decode errors: ${errors.take(5)}" }
        }
    }

    @Test
    fun subtitleTrackInMp4IsSkipped() {
        requireFfmpeg()
        for (ext in listOf("mp4", "mkv")) {
            val out = File(workDir, "no_subs.$ext").absolutePath
            val src = File(workDir, "with_subs.mp4").absolutePath
            assert(TransmuxingMuxer("/nonexistent-appdir").mux(listOf(src), out, {}, workDir.path, false, true)) {
                "mux to $ext returned false"
            }
            ffprobeStreams(out)?.let { streams ->
                assert(streams == listOf("video", "audio")) { "$ext streams: $streams" }
            }
            val errors = decodeErrors(out)
            assert(errors.isEmpty()) { "$ext decode errors: ${errors.take(5)}" }
        }
    }

    @Test
    fun dolbyVisionConfigCarriedToMkv() {
        requireFfmpeg()
        val media = files("fmp\\d+\\.m4s")
        Assumptions.assumeTrue(media.isNotEmpty(), "no fmp4 segments")
        val out = File(workDir, "dolby_vision.mkv").absolutePath
        val writer = MkvWriter(out)
        val demux = Mp4Demuxer(writer)
        for (seg in listOf(File(workDir, "fmp4.mp4").absolutePath) + media) demux.parseSegment(seg)
        val video = demux.tracks.first { it.codec.isVideo }
        // Re-signal the AVC track as Dolby Vision profile 9 (AVC base layer): 'dvav' + a dvvC record.
        val record = ByteArray(24).also {
            it[0] = 1                                   // dv_version_major
            val bits = (9 shl 9) or (5 shl 3) or (1 shl 2) or 1 // profile 9, level 5, rpu + bl present
            it[2] = (bits shr 8).toByte(); it[3] = bits.toByte()
            it[4] = 0x20                                // dv_bl_signal_compatibility_id = 2
        }
        val dvvC = int32(8 + record.size) + "dvvC".toByteArray(Charsets.US_ASCII) + record
        val entry = video.sampleEntryBox!! + dvvC
        int32(entry.size).copyInto(entry, 0)
        "dvav".toByteArray(Charsets.US_ASCII).copyInto(entry, 4)
        video.sampleEntryBox = entry
        assert(writer.finish(demux.tracks)) { "MkvWriter.finish returned false" }

        val ffprobe = which("ffprobe") ?: return
        val p = ProcessBuilder(ffprobe, "-v", "error", "-select_streams", "v:0", "-show_streams", out)
            .redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        assert(text.contains("dv_profile=9") && text.contains("dv_bl_signal_compatibility_id=2")) {
            "no Dolby Vision configuration in MKV: ${text.lines().filter { it.startsWith("dv_") || it.startsWith("side_data") }}"
        }
    }
}
