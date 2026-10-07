package xdm.core

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.impl.TransmuxingMuxer
import java.io.File

/**
 * A/V sync probe for [TransmuxingMuxer]. A 30000/1001 fps video flashes white on every 60th frame
 * while a 1 kHz tone beeps at exactly the same instants (silence otherwise). After muxing, ffmpeg
 * measures each flash onset (blackdetect) and each beep onset (silencedetect) on the output's own
 * timeline; audio-minus-video per marker is the A/V error, and its spread across the clip is drift.
 */
class TestAvSync {

    companion object {
        private val dir = File(System.getProperty("java.io.tmpdir"), "xdm-avsync-test")
        private var ffmpeg: String? = null
        private const val DURATION = 120
        /** 60 frames at 30000/1001 fps. */
        private const val PERIOD = 2.002

        @BeforeAll
        @JvmStatic
        fun setup() {
            ffmpeg = which("ffmpeg")
            if (ffmpeg == null || which("ffprobe") == null) { ffmpeg = null; return }
            dir.deleteRecursively(); dir.mkdirs()
            // Master: H.264 main with B-frames + AAC 44.1 kHz (1024-sample frames don't divide the 90 kHz clock).
            run(ffmpeg!!, "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=black:s=320x240:r=30000/1001:d=$DURATION," +
                    "drawbox=enable='lt(mod(n\\,60)\\,3)':t=fill:c=white",
                "-f", "lavfi", "-i", "aevalsrc='if(lt(mod(t\\,$PERIOD)\\,0.1)\\,0.8*sin(2*PI*1000*t)\\,0)':s=44100:d=$DURATION",
                "-c:v", "libx264", "-profile:v", "main", "-bf", "3", "-g", "60", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-ac", "2", f("master.ts"))
            // A: muxed A+V TS segments.
            hls("mux_ts", "-map", "0:v", "-map", "0:a", "-hls_segment_type", "mpegts")
            // B: separate video-only / audio-only TS renditions on the master's timeline.
            hls("v_ts", "-map", "0:v", "-hls_segment_type", "mpegts")
            hls("a_ts", "-map", "0:a", "-hls_segment_type", "mpegts")
            // C: muxed A+V fMP4 (CMAF) segments.
            hls("mux_fmp4", "-map", "0:v", "-map", "0:a", "-hls_segment_type", "fmp4", "-bsf:a", "aac_adtstoasc")
            // D: separate fMP4 renditions (HLS-fMP4 / DASH style).
            hls("v_fmp4", "-map", "0:v", "-hls_segment_type", "fmp4")
            hls("a_fmp4", "-map", "0:a", "-hls_segment_type", "fmp4", "-bsf:a", "aac_adtstoasc")
            // E: progressive MP4 A+V, and separate progressive video.mp4 + audio.m4a (YouTube style).
            run(ffmpeg!!, "-y", "-loglevel", "error", "-i", f("master.ts"), "-c", "copy", "-movflags", "+faststart", f("whole.mp4"))
            run(ffmpeg!!, "-y", "-loglevel", "error", "-i", f("master.ts"), "-map", "0:v", "-c", "copy", f("video.mp4"))
            run(ffmpeg!!, "-y", "-loglevel", "error", "-i", f("master.ts"), "-map", "0:a", "-c", "copy", f("audio.m4a"))
        }

        private fun hls(name: String, vararg extra: String) {
            run(ffmpeg!!, "-y", "-loglevel", "error", "-copyts", "-i", f("master.ts"), *extra, "-c", "copy",
                "-f", "hls", "-hls_time", "4", "-hls_list_size", "0",
                "-hls_fmp4_init_filename", "${name}_init.mp4",
                "-hls_segment_filename", f("${name}_%03d.seg"), f("$name.m3u8"))
        }

        private fun f(name: String) = File(dir, name).path

        private fun which(cmd: String): String? = System.getenv("PATH")?.split(File.pathSeparator)
            ?.map { File(it, cmd) }?.firstOrNull { it.canExecute() }?.absolutePath

        private fun run(vararg cmd: String): String {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            check(p.waitFor() == 0) { "command failed: ${cmd.joinToString(" ")}\n$out" }
            return out
        }
    }

    private fun requireFfmpeg() = Assumptions.assumeTrue(ffmpeg != null, "ffmpeg/ffprobe not on PATH; skipping")

    /** Playlist segments in order, init segment first when there is one. */
    private fun segments(name: String): List<String> {
        val init = File(dir, "${name}_init.mp4")
        val media = dir.listFiles { x -> x.name.matches(Regex("${name}_\\d+\\.seg")) }!!.sortedBy { it.name }.map { it.path }
        return (if (init.exists()) listOf(init.path) else emptyList()) + media
    }

    // The clip ends black and silent, so both detectors' last event is the end of stream: dropped.

    /** Flash onsets (s) on [path]'s own timeline (-copyts: no start-time normalisation). */
    private fun flashes(path: String): List<Double> =
        Regex("black_end:([0-9.]+)").findAll(run(ffmpeg!!, "-hide_banner", "-copyts", "-i", path, "-map", "0:v",
            "-vf", "blackdetect=d=0.01:pix_th=0.5", "-f", "null", "-")).map { it.groupValues[1].toDouble() }.toList().dropLast(1)

    /** Beep onsets (s) on [path]'s own timeline. */
    private fun beeps(path: String): List<Double> =
        Regex("silence_end: ([0-9.]+)").findAll(run(ffmpeg!!, "-hide_banner", "-copyts", "-i", path, "-map", "0:a",
            "-af", "silencedetect=n=-30dB:d=0.05", "-f", "null", "-")).map { it.groupValues[1].toDouble() }.toList().dropLast(1)

    private class Sync(val offsetsMs: List<Double>, val audioDur: Double, val videoDur: Double) {
        val mean get() = offsetsMs.average()
        val drift get() = offsetsMs.max() - offsetsMs.min()
        override fun toString() = "markers=%d  A-V mean=%+.1f ms  min=%+.1f  max=%+.1f  drift=%.1f ms  dur v=%.3f a=%.3f"
            .format(offsetsMs.size, mean, offsetsMs.min(), offsetsMs.max(), drift, videoDur, audioDur)
    }

    private fun measure(path: String): Sync {
        val v = flashes(path)
        val a = beeps(path)
        // Pair each flash with the nearest beep (both should be one per PERIOD).
        val offsets = v.mapNotNull { t -> a.minByOrNull { kotlin.math.abs(it - t) }?.takeIf { kotlin.math.abs(it - t) < 0.5 }?.let { (it - t) * 1000 } }
        val dur = run(which("ffprobe")!!, "-v", "error", "-show_entries", "stream=codec_type,duration", "-of", "csv=p=0", path)
            .lines().filter { it.isNotBlank() }.associate { it.split(",").let { (k, d) -> k to (d.toDoubleOrNull() ?: -1.0) } }
        return Sync(offsets, dur["audio"] ?: -1.0, dur["video"] ?: -1.0)
    }

    private val report = StringBuilder()

    private fun check(label: String, out: String, reference: Sync, ok: Boolean, droppedSec: Double = 0.0) {
        assert(ok) { "$label: mux returned false" }
        val s = measure(out)
        val line = "%-28s %s".format(label, s)
        println(line); report.appendLine(line)
        File(dir, "report.txt").appendText(line + "\n")
        assert(s.offsetsMs.size >= (DURATION - droppedSec) / PERIOD - 2) { "$label: only ${s.offsetsMs.size} markers matched" }
        assert(kotlin.math.abs(s.mean - reference.mean) < 5.0) {
            "$label: A/V offset ${"%.1f".format(s.mean)} ms vs source ${"%.1f".format(reference.mean)} ms"
        }
        assert(s.drift < reference.drift + 5.0) { "$label: drift ${"%.1f".format(s.drift)} ms" }
    }

    private val mux get() = TransmuxingMuxer("/nonexistent-appdir")

    private fun out(name: String) = File(dir, name).path

    private val source by lazy {
        measure(f("master.ts")).also { File(dir, "report.txt").appendText("%-28s %s\n".format("SOURCE master.ts", it)) }
    }

    @Test
    fun muxedTs() {
        requireFfmpeg()
        for (ext in listOf("mp4", "mkv")) {
            val o = out("mux_ts.$ext")
            check("muxed TS -> $ext", o, source, mux.mux(segments("mux_ts"), o, {}, dir.path, false, false))
        }
    }

    @Test
    fun separateTs() {
        requireFfmpeg()
        for (ext in listOf("mp4", "mkv")) {
            val o = out("sep_ts.$ext")
            check("separate TS -> $ext", o, source, mux.mux(segments("a_ts"), segments("v_ts"), o, {}, dir.path, false, false))
        }
    }

    @Test
    fun separateTsAudioJoinsLate() {
        requireFfmpeg()
        // Live-window style: the audio list starts two segments after the video list.
        val o = out("sep_ts_alate.mp4")
        check("separate TS, audio late", o, source,
            mux.mux(segments("a_ts").drop(2), segments("v_ts"), o, {}, dir.path, false, false), 8.0)
    }

    @Test
    fun separateTsVideoJoinsLate() {
        requireFfmpeg()
        val o = out("sep_ts_vlate.mp4")
        check("separate TS, video late", o, source,
            mux.mux(segments("a_ts"), segments("v_ts").drop(2), o, {}, dir.path, false, false), 8.0)
    }

    @Test
    fun muxedFmp4() {
        requireFfmpeg()
        for (ext in listOf("mp4", "mkv")) {
            val o = out("mux_fmp4.$ext")
            check("muxed fMP4 -> $ext", o, source, mux.mux(segments("mux_fmp4"), o, {}, dir.path, false, true))
        }
    }

    @Test
    fun separateFmp4() {
        requireFfmpeg()
        for (ext in listOf("mp4", "mkv")) {
            val o = out("sep_fmp4.$ext")
            check("separate fMP4 -> $ext", o, source,
                mux.mux(segments("a_fmp4"), segments("v_fmp4"), o, {}, dir.path, false, true))
        }
    }

    @Test
    fun separateFmp4AudioJoinsLate() {
        requireFfmpeg()
        val a = segments("a_fmp4"); val v = segments("v_fmp4")
        val o = out("sep_fmp4_alate.mp4")
        check("separate fMP4, audio late", o, source,
            mux.mux(listOf(a.first()) + a.drop(3), v, o, {}, dir.path, false, true), 8.0)
    }

    @Test
    fun progressiveMp4() {
        requireFfmpeg()
        val o = out("whole_out.mp4")
        check("progressive MP4", o, source, mux.mux(listOf(f("whole.mp4")), o, {}, dir.path, true, true))
    }

    @Test
    fun separateProgressiveFiles() {
        requireFfmpeg()
        // Each file was cut from the master on its own timeline (both start at 0, dropping the master's
        // ~23 ms A/V start offset), so the reference is ffmpeg's own merge of the same two files.
        val ref = out("files_ffmpeg.mp4")
        run(ffmpeg!!, "-y", "-loglevel", "error", "-i", f("video.mp4"), "-i", f("audio.m4a"), "-c", "copy", ref)
        val reference = measure(ref)
        for (ext in listOf("mp4", "mkv")) {
            val o = out("files.$ext")
            check("video.mp4 + audio.m4a -> $ext", o, reference, mux.mux(f("video.mp4"), f("audio.m4a"), o, {}, dir.path))
        }
    }
}
