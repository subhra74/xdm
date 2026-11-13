package xdm.app.media

import xdm.app.AppContext
import xdm.core.media.muxer.Muxer
import xdm.core.storage.StorageFile
import xdm.core.util.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Merges with an ffmpeg the user chose in Settings ("Use ffmpeg for muxing"), in place of the built-in
 * transmuxer, for every merge: HLS, DASH, batch video+audio items and Video download (yt-dlp).
 *
 * Each track's pieces are consecutive parts of one resource (byte ranges of a file, or the fragments
 * or segments of a stream), so joined in order they are that resource again. They are handed to ffmpeg
 * joined with its `concatf:` protocol, which reads them one after another from a list file without
 * copying; an ffmpeg older than 6.0 lacks it, and then the bytes are joined into one file first. Then
 * `ffmpeg -i video -i audio -map 0 -map 1 -c copy`, into [container], or else the format the output
 * name's extension stands for, or MP4.
 */
class FFmpegMuxer(
    private val container: String?,
    private val ffmpeg: () -> String? = { AppContext.config.ffmpegPath.trim().ifEmpty { null } },
) : Muxer {
    private val process = AtomicReference<Process?>()
    private val stopped = AtomicBoolean(false)
    private var error: Throwable? = null

    override val lastError: Throwable? get() = error

    override fun mux(
        segments: List<String>, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String,
        independentSegement: Boolean, isMp4: Boolean, discontinuous: Boolean,
    ): Boolean = merge(listOf(segments), output, progressCallback, tempDir)

    override fun mux(
        file1: String, file2: String, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String,
    ): Boolean = merge(listOf(listOf(file1), listOf(file2)), output, progressCallback, tempDir)

    override fun mux(
        audioSegments: List<String>, videoSegments: List<String>, output: StorageFile,
        progressCallback: (Int) -> Unit, tempDir: String, independentSegement: Boolean, isMp4: Boolean,
        discontinuous: Boolean,
    ): Boolean = merge(listOf(videoSegments, audioSegments), output, progressCallback, tempDir)

    override fun stop() {
        stopped.set(true)
        process.get()?.let { if (it.isAlive) it.destroy() }
    }

    private fun merge(tracks: List<List<String>>, output: StorageFile, progress: (Int) -> Unit, tempDir: String): Boolean {
        error = null
        val inputs = tracks.filter { it.isNotEmpty() }
        if (inputs.isEmpty()) return false
        // ffmpeg writes to a path: an output outside the local file system cannot be merged here.
        val out = output.localPath ?: return false
        val exe = ffmpeg()?.takeIf { File(it).isFile } ?: run {
            error = FileNotFoundException("ffmpeg not found: ${ffmpeg() ?: "no path set"}")
            Logger.error("ffmpeg", "ffmpeg not found (${ffmpeg() ?: "no path set"}); set it in Settings > Advanced")
            return false
        }
        val format = formatName(container) ?: formatName(out.substringAfterLast('.', "")) ?: "mp4"
        val made = ArrayList<File>()
        try {
            val lists = inputs.map { track -> writeList(track, tempDir).also { made.add(it) } }
            var result = runFfmpeg(exe, lists.map { "concatf:${it.absolutePath}" }, out, format, progress)
            if (result is RunResult.NoConcatf && !stopped.get()) {
                Logger.info("ffmpeg", "ffmpeg has no concatf: protocol; joining the pieces first")
                val joined = inputs.map { track ->
                    if (track.size == 1) track[0] else joinBytes(track, tempDir).also { made.add(it) }.absolutePath
                }
                result = runFfmpeg(exe, joined, out, format, progress)
            }
            if (result !is RunResult.Ok) {
                if (!stopped.get()) error = (result as? RunResult.Failed)?.cause ?: IOException("ffmpeg failed")
                File(out).delete()
                return false
            }
            return true
        } catch (e: Exception) {
            Logger.error("ffmpeg", "Merge failed", e)
            error = e
            File(out).delete()
            return false
        } finally {
            made.forEach { it.delete() }
        }
    }

    private sealed interface RunResult {
        data object Ok : RunResult
        data object NoConcatf : RunResult
        data class Failed(val cause: Throwable) : RunResult
    }

    /** One file per line, as `concatf:` reads them. */
    private fun writeList(track: List<String>, tempDir: String): File {
        val f = File(tempDir, "ffconcat-${UUID.randomUUID()}.txt")
        f.writeText(track.joinToString("\n", postfix = "\n") { File(it).absolutePath }, Charsets.UTF_8)
        return f
    }

    private fun joinBytes(track: List<String>, tempDir: String): File {
        val f = File(tempDir, "joined-${UUID.randomUUID()}.bin")
        f.outputStream().buffered(1 shl 20).use { o ->
            for (piece in track) {
                if (stopped.get()) throw IOException("stopped")
                File(piece).inputStream().use { it.copyTo(o, 1 shl 20) }
            }
        }
        return f
    }

    private fun runFfmpeg(exe: String, inputs: List<String>, out: String, format: String, progress: (Int) -> Unit): RunResult {
        if (stopped.get()) return RunResult.Failed(IOException("stopped"))
        val cmd = command(exe, inputs, out, format)
        Logger.info("ffmpeg", "Merging: $cmd")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process.set(p)
        p.outputStream.close()
        var duration = 0.0
        var noConcatf = false
        val tail = ArrayDeque<String>()
        try {
            p.inputStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    // ffmpeg ends progress lines with \r; split those too.
                    for (line in raw.split('\r')) {
                        val text = line.trim()
                        if (text.isEmpty()) continue
                        if (tail.size >= 20) tail.removeFirst()
                        tail.addLast(text)
                        if (text.contains("Protocol not found") || text.contains("concatf")) {
                            if (text.contains("not found") || text.contains("Unknown")) noConcatf = true
                        }
                        if (duration == 0.0) parseTime(DURATION, text)?.let { duration = it }
                        parseTime(TIME, text)?.let { t ->
                            if (duration > 0) progress(((t * 100) / duration).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            val code = p.waitFor()
            return when {
                code == 0 -> RunResult.Ok
                noConcatf -> RunResult.NoConcatf
                else -> {
                    Logger.error("ffmpeg", "ffmpeg exited $code: ${tail.joinToString(" | ")}")
                    RunResult.Failed(IOException("ffmpeg exited $code"))
                }
            }
        } finally {
            if (p.isAlive) p.destroyForcibly()
            process.set(null)
        }
    }

    companion object {
        private val DURATION = Regex("Duration:\\s*(\\d+):(\\d\\d):(\\d\\d(?:\\.\\d+)?)")
        private val TIME = Regex("time=\\s*(\\d+):(\\d\\d):(\\d\\d(?:\\.\\d+)?)")

        internal fun parseTime(rx: Regex, text: String): Double? = rx.find(text)?.destructured?.let { (h, m, s) ->
            h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble()
        }

        /** ffmpeg's muxer name for a container or extension; null for one it does not stand for. */
        internal fun formatName(container: String?): String? = when (container?.lowercase()) {
            "mp4", "m4a", "m4v" -> "mp4"
            "mov" -> "mov"
            "webm" -> "webm"
            "mkv", "mka" -> "matroska"
            "mp3" -> "mp3"
            "ogg", "opus" -> "ogg"
            "ts" -> "mpegts"
            "aac" -> "adts"
            else -> null
        }

        /** [format] is ffmpeg's muxer name ([formatName]). */
        internal fun command(exe: String, inputs: List<String>, out: String, format: String): List<String> {
            val cmd = arrayListOf(exe, "-hide_banner", "-nostdin", "-y")
            inputs.forEach { cmd.add("-i"); cmd.add(it) }
            inputs.indices.forEach { cmd.add("-map"); cmd.add("$it") }
            cmd.addAll(listOf("-c", "copy"))
            if (format == "mp4") {
                // moov at the start, so the file plays while still being read (and streams).
                cmd.addAll(listOf("-movflags", "+faststart"))
            }
            cmd.add("-f"); cmd.add(format)
            cmd.add(out)
            return cmd
        }
    }
}
