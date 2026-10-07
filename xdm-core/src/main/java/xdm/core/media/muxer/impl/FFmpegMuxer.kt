package xdm.core.media.muxer.impl

//import xdm.core.Config
import xdm.core.media.muxer.Muxer
import xdm.core.util.Logger
import xdm.core.util.PlatformUtils
import xdm.core.util.StringUtils
import java.io.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.IntConsumer
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.min

class FFmpegMuxer(private val appDir: String) : Muxer {
    private var duration: Long = 0
    private var time: Long = 0
    private var lastTick: Long = 0
    private val ffmpegProcess = AtomicReference<Process?>()
    private val stopFlag = AtomicBoolean(false)

    private fun shouldAppend(segments: List<String>, independentSegement: Boolean, isMp4: Boolean): Boolean {
        if (independentSegement) {
            return false
        }

        if (isMp4 || segments.any { it.endsWith(".mp4") || it.endsWith(".m4s") }) {
            if (segments.size > 2 && segments[0].endsWith(".mp4") && segments[1].endsWith(".m4s")) {
                return true
            }
            return false
        }

        return true
    }

    private fun concatM4sFiles(
        segments: List<String>, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String
    ): Boolean {
        val buffer = ByteArray(1024 * 256)
        FileOutputStream(outputFile).use { output ->
            for (seg in segments) {
                FileInputStream(seg).use { input ->
                    while (!stopFlag.get()) {
                        val x = input.read(buffer)
                        if (x != -1) {
                            output.write(buffer, 0, x)
                        } else {
                            break
                        }
                    }
                }
            }
        }
        if (stopFlag.get()) {
            File(outputFile).delete()
        }
        return true
    }

    override fun mux(
        segments: List<String>,
        outputFile: String,
        progressCallback: (Int) -> Unit,
        tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
        discontinuous: Boolean,
    ): Boolean {
        if (shouldAppend(segments, independentSegement, isMp4)) {
            Logger.info("Concat fmp4 segments")
            return concatM4sFiles(segments, outputFile, progressCallback, tempDir)
        }
        Logger.info("Merging mp4 segments")
        val concatFile = File(tempDir, UUID.randomUUID().toString() + ".txt")
        try {
            FileWriter(concatFile).use { writer ->
                for (s in segments) {
                    writer.write("file '$s'\n")
                }
            }
            val pathToFFmpeg = findFFmpegBinary(appDir)
            spawnFFmpeg(
                progressCallback,
                pathToFFmpeg,
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                concatFile.absolutePath,
                "-auto_convert",
                "1",
                "-acodec",
                "copy",
                "-vcodec",
                "copy",
                outputFile,
                "-y"
            )
            Logger.info("Merging success, file: $outputFile")
            return true
        } catch (ex: Exception) {
            Logger.error("XDM", "FFmpeg merging failed", ex)
        } finally {
            concatFile.delete();
        }
        return false
    }

    override fun mux(
        file1: String, file2: String, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String
    ): Boolean {
        try {
            val pathToFFmpeg = findFFmpegBinary(appDir)
            spawnFFmpeg(
                progressCallback,
                pathToFFmpeg,
                "-i",
                file1,
                "-i",
                file2,
                "-acodec",
                "copy",
                "-vcodec",
                "copy",
                "-map",
                "0?",
                "-map",
                "1?",
                outputFile,
                "-y"
            )
            Logger.info("FFmpeg merge success!")
            return true
        } catch (ex: Exception) {
            Logger.error("FFmpeg merging failed", ex)
        }
        if (stopFlag.get()) {
            File(file1).delete()
            File(file2).delete()
            File(outputFile).delete()
        }
        return false
    }

    override fun mux(
        audioSegments: List<String>,
        videoSegments: List<String>,
        outputFile: String,
        progressCallback: (Int) -> Unit,
        tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
        discontinuous: Boolean,
    ): Boolean {
        val totalProgress = AtomicInteger(0)
        val callback = { prg: Int ->
            val p = totalProgress.addAndGet(prg)
            progressCallback(min((p / 3).toDouble(), 100.0).toInt())
        }
        val audioPart = File(tempDir, UUID.randomUUID().toString() + ".mp4")
        val videoPart = File(tempDir, UUID.randomUUID().toString() + ".mp4")
        try {
            if (!this.mux(audioSegments, audioPart.absolutePath, callback, tempDir, independentSegement, isMp4)) {
                return false
            }
            if (!this.mux(videoSegments, videoPart.absolutePath, callback, tempDir, independentSegement, isMp4)) {
                return false
            }
            return this.mux(
                videoPart.absolutePath, audioPart.absolutePath, outputFile, callback, tempDir
            )
        } finally {
            audioPart.delete()
            videoPart.delete()
            if (stopFlag.get()) {
                File(outputFile).delete()
            }
        }
    }

    override fun stop() {
        stopFlag.set(true)
        val proc = ffmpegProcess.get()
        if (proc != null && proc.isAlive) proc.destroy()
    }

    private fun spawnFFmpeg(progressCallback: IntConsumer, vararg args: String): Result<Unit> {
        return runCatching {
            Logger.info(listOf(*args))
            val pb = ProcessBuilder(*args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            ffmpegProcess.set(proc)
            try {
                BufferedReader(InputStreamReader(proc.inputStream), 1024).use { br ->
                    while (true) {
                        val ln = br.readLine() ?: break
                        val text = ln.trim { it <= ' ' }
                        Logger.info(text)
                        processOutput(text, progressCallback)
                    }
                    val exitCode = proc.waitFor()
                    if (exitCode != 0) {
                        throw IOException("FFmpeg non zero exit code: $exitCode")
                    }
                }
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(ex)
            } finally {
                ffmpegProcess.set(null)
            }
        }
    }

    private fun processOutput(text: String, progressCallback: IntConsumer) {
        if (StringUtils.isNullOrEmpty(text)) {
            return
        }
        try {
            if (duration == 0L) {
                val md = rxDuration.matcher(text)
                duration = parseDuration(md)
            }
            val mt = rxTime.matcher(text)
            val t = parseDuration(mt)
            if (t > 0) {
                time += t
                val tick = System.currentTimeMillis()
                if (duration > 0 && tick - lastTick > 1000) {
                    lastTick = tick
                    progressCallback.accept(((time * 100) / duration).toInt())
                }
            }
        } catch (e: Exception) {
            Logger.info("Unable to parse ffmpeg output: {}", text)
        }
    }

    private fun parseDuration(matcher: Matcher): Long {
        if (matcher.matches()) {
            val count = matcher.groupCount()
            if (count == 4) {
                val h = matcher.group(1).trim { it <= ' ' }.toLong(10) * 3600
                val m = matcher.group(2).trim { it <= ' ' }.toLong(10) * 60
                val s = matcher.group(3).trim { it <= ' ' }.toLong(10)
                return h + m + s
            }
        }
        return 0
    }

    companion object {
        private val rxDuration: Pattern = Pattern.compile("Duration:\\s+(\\d\\d):(\\d\\d):(\\d\\d)\\.\\d\\d,\\s")
        private val rxTime: Pattern = Pattern.compile("frame=.*?time=(\\d\\d):(\\d\\d):(\\d\\d)\\.\\d\\d.*?bitrate=")
        private val execNames: List<String>
            get() = if (PlatformUtils.isWindows) mutableListOf(
                "ffmpeg-x86.exe", "ffmpeg.exe"
            ) else listOf("ffmpeg")


        private fun findFFmpegBinary(appDir: String): String {
            val exeNames = execNames
            for (exe in exeNames) {
                var file = File(appDir, exe)
                if (file.exists()) {
                    return file.absolutePath
                }
                val baseDirectory = PlatformUtils.baseDirectory
                if (baseDirectory != null) {
                    file = File(baseDirectory, exe)
                    if (file.exists()) {
                        return file.absolutePath
                    }
                }
                val ffMpegHome = System.getenv("FFMPEG_HOME")
                if (ffMpegHome != null) {
                    file = File(ffMpegHome, exe)
                    if (file.exists()) {
                        return file.absolutePath
                    }
                }
                val systemPath = PlatformUtils.getExecutableFromSystemPath(exe)
                if (systemPath != null) {
                    return systemPath
                }
            }
            throw FileNotFoundException("FFmpeg executable not found!")
        }
    }
}
