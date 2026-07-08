import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import xdm.core.CoreConfig
import xdm.core.downloaders.CommitResult
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.PauseEvent
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.network.http.impl.HttpClientImpl
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Shared harness for the streaming (HLS/DASH) end-to-end tests. Each test:
 *   1. generates real media with ffmpeg into a per-case dir under [root],
 *   2. serves that dir over a tiny Range-capable HTTP server ([FileServer]),
 *   3. drives the *real* HlsDownloaderTask / DashDownloaderTask + TransmuxingMuxer to completion,
 *   4. verifies the committed output file with ffprobe (stream codecs + non-zero duration).
 *
 * Fixtures are generated in [setup] lazily per test; a case Assume-skips if a required encoder
 * (x265/ac3/vp9/opus) is missing, so the build stays green on a minimal ffmpeg — same policy as
 * TestTransmuxer.
 */
abstract class StreamingE2EBase {

    protected lateinit var root: File
    protected lateinit var server: FileServer
    private val clients = CopyOnWriteArrayList<HttpClientImpl>()
    protected val hosts = CopyOnWriteArrayList<StreamingTestHost>()

    @Before
    fun setup() {
        root = java.nio.file.Files.createTempDirectory("xdm-stream-e2e").toFile()
        server = FileServer(root)
    }

    @After
    fun tearDown() {
        hosts.forEach { runCatching { it.stopFlag.set(true) } }
        Thread.sleep(150)
        clients.forEach { runCatching { it.close() } }
        server.stop()
        root.deleteRecursively()
    }

    protected fun newClient(): HttpClientImpl = HttpClientImpl(8).also { clients.add(it) }

    protected fun host(caseName: String): StreamingTestHost {
        val tmp = File(root, "$caseName/tmp").apply { mkdirs() }
        val out = File(root, "$caseName/out").apply { mkdirs() }
        return StreamingTestHost(root.absolutePath, tmp.absolutePath, out.absolutePath).also { hosts.add(it) }
    }

    protected fun muxer(): TransmuxingMuxer = TransmuxingMuxer(root.absolutePath)

    protected fun config(maxSegments: Int = 4): CoreConfig = E2EConfig(maxSegments)

    /** Directory a case's fixtures live in (also the temp/out parent). Created on demand. */
    protected fun caseDir(name: String): File = File(root, name).apply { mkdirs() }

    /** URL of a fixture file, relative to [root] (e.g. "h1/index.m3u8"). */
    protected fun url(relPath: String): String = server.url(relPath)

    protected fun awaitSuccess(host: StreamingTestHost, timeoutSec: Long = 60) {
        assertTrue("download did not finish within ${timeoutSec}s", host.latch.await(timeoutSec, TimeUnit.SECONDS))
        assertNull("unexpected failure: ${host.failure}", host.failure)
        assertNotNull("expected success info", host.success)
        assertNotNull("no committed file", host.finalFile)
        assertTrue("output too small: ${host.finalFile?.length()}", (host.finalFile?.length() ?: 0) > 2_000)
    }

    protected fun awaitFailure(host: StreamingTestHost, timeoutSec: Long = 60): DownloadError {
        assertTrue("download did not settle within ${timeoutSec}s", host.latch.await(timeoutSec, TimeUnit.SECONDS))
        assertNotNull("expected a failure", host.failure)
        return host.failure!!
    }

    // ------------------------------------------------------------------ ffmpeg / ffprobe

    companion object {
        val ffmpeg: String? by lazy { which("ffmpeg") }
        val ffprobe: String? by lazy { which("ffprobe") }

        fun which(cmd: String): String? {
            val path = System.getenv("PATH") ?: return null
            for (dir in path.split(File.pathSeparator)) {
                val f = File(dir, cmd)
                if (f.canExecute()) return f.absolutePath
            }
            return null
        }

        /** Runs a command, returns (exit, combined-output). */
        fun exec(vararg cmd: String): Pair<Int, String> {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            return p.waitFor() to out
        }
    }

    protected fun requireFfmpeg() {
        Assume.assumeTrue("ffmpeg not on PATH; skipping", ffmpeg != null)
    }

    /** Runs ffmpeg; fails the test on non-zero exit. */
    protected fun ffmpeg(vararg args: String) {
        val (code, out) = exec(ffmpeg!!, "-y", "-loglevel", "error", *args)
        check(code == 0) { "ffmpeg failed (${args.joinToString(" ")}):\n$out" }
    }

    /** Runs ffmpeg tolerantly (missing encoder etc.); returns true on success. */
    protected fun ffmpegSoft(vararg args: String): Boolean =
        runCatching { exec(ffmpeg!!, "-y", "-loglevel", "error", *args).first == 0 }.getOrDefault(false)

    /** True when ffmpeg lists [encoder] among its encoders. */
    protected fun hasEncoder(encoder: String): Boolean {
        if (ffmpeg == null) return false
        val (_, out) = exec(ffmpeg!!, "-hide_banner", "-encoders")
        return out.lineSequence().any { it.trim().split(Regex("\\s+")).getOrNull(1) == encoder }
    }

    /** codec_type list (e.g. ["video","audio"]) of a media file, via ffprobe; null if no ffprobe. */
    protected fun probeStreamTypes(path: String): List<String>? {
        ffprobe ?: return null
        val (_, out) = exec(ffprobe!!, "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", path)
        return out.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** codec_name list of a media file, via ffprobe. */
    protected fun probeCodecs(path: String): List<String>? {
        ffprobe ?: return null
        val (_, out) = exec(ffprobe!!, "-v", "error", "-show_entries", "stream=codec_name", "-of", "csv=p=0", path)
        return out.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Container-level duration in seconds, via ffprobe; -1 if unavailable. */
    protected fun probeDuration(path: String): Double {
        ffprobe ?: return -1.0
        val (_, out) = exec(
            ffprobe!!, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path
        )
        return out.trim().toDoubleOrNull() ?: -1.0
    }

    /**
     * Assert the committed output plays back with the expected number of streams and codec types.
     * Skips the codec checks (but not the file check) when ffprobe is absent.
     */
    protected fun verifyOutput(
        host: StreamingTestHost,
        expectVideo: Boolean,
        expectAudio: Boolean,
        minDuration: Double = 0.5,
    ) {
        val f = host.finalFile!!
        val types = probeStreamTypes(f.absolutePath) ?: return
        if (expectVideo) assertTrue("expected a video stream, got $types", types.contains("video"))
        if (expectAudio) assertTrue("expected an audio stream, got $types", types.contains("audio"))
        if (!expectVideo) assertTrue("unexpected video stream in $types", !types.contains("video"))
        val dur = probeDuration(f.absolutePath)
        assertTrue("duration too short ($dur < $minDuration)", dur < 0 || dur >= minDuration)
    }
}

/** Minimal [CoreConfig] for the streaming e2e tests. */
class E2EConfig(
    override var maxSegments: Int,
    override var maxRetries: Int = 3,
) : CoreConfig {
    override var speedLimit: Int = 0
    override var speedLimiterEnabled: Boolean = false
    override var useProxy: Boolean = false
    override var socksProxy: Boolean = false
    override var proxyHost: String = ""
    override var proxyPort: Int = 0
    override var proxyUser: String = ""
    override var proxyPass: String = ""
    override fun toProxy(): Proxy? = null
}

/** Captures streaming-downloader callbacks and performs the temp -> final move (keeping extension). */
class StreamingTestHost(
    override val appDir: String,
    private val tempDir: String,
    private val outDir: String,
) : DownloadHost {
    val latch = CountDownLatch(1)
    val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile var success: DownloadStatusInfo.FinalInfo? = null
    @Volatile var failure: DownloadError? = null
    @Volatile var initInfo: DownloadStatusInfo.InitInfo? = null
    @Volatile var finalFile: File? = null

    override fun getTempDir(id: Long, url: String, contentType: String?, contentDisposition: String?): String = tempDir

    override fun commitOutputFile(id: Long, tmpFilePath: String, downloadType: DownloadType): CommitResult {
        val src = File(tmpFilePath)
        val ext = src.name.substringAfterLast('.', "mp4")
        val dst = File(outDir, "out-$id.$ext")
        if (dst.exists()) dst.delete()
        val moved = src.renameTo(dst) || runCatching {
            src.copyTo(dst, overwrite = true); src.delete()
        }.isSuccess
        if (!moved || !dst.exists()) return CommitResult.Failed
        finalFile = dst
        return CommitResult.Success(dst.name, outDir)
    }

    override fun onDownloadActivated(id: Long) {}
    override fun onDownloadInit(data: DownloadStatusInfo.InitInfo, downloadType: DownloadType) { initInfo = data }
    override fun onDownloadProgress(event: DownloadStatusInfo.ProgressInfo) {}
    override fun onAssembleStart(id: Long) {}
    override fun onAssembleProgress(event: DownloadStatusInfo.AssembleInfo) {}
    override fun onDownloadSuccess(event: DownloadStatusInfo.FinalInfo) { success = event; latch.countDown() }
    override fun onDownloadFailed(id: Long, error: DownloadError) { failure = error; latch.countDown() }
    override fun onDownloadPaused(id: Long, event: PauseEvent) { latch.countDown() }

    override val applySpeedLimit: Boolean = false
    override val speedLimit: Int = 0
}

/**
 * A tiny static-file HTTP server (JDK [HttpServer]) rooted at a directory, with byte-range (206)
 * support so the byte-range HLS / SegmentBase DASH paths are exercised for real. One "/" context
 * serves any relative path, so arbitrary ffmpeg segment names work without per-file registration.
 */
class FileServer(private val root: File) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()

    init {
        server.executor = executor
        server.createContext("/") { ex -> serve(ex) }
        server.start()
    }

    val port: Int get() = server.address.port
    fun url(relPath: String): String = "http://127.0.0.1:$port/${relPath.trimStart('/')}"

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun serve(ex: HttpExchange) {
        try {
            val rel = ex.requestURI.path.trimStart('/').substringBefore('?')
            val file = File(root, rel)
            if (!file.exists() || !file.isFile || !file.canonicalPath.startsWith(root.canonicalPath)) {
                ex.sendResponseHeaders(404, -1); return
            }
            val total = file.length()
            val rangeHeader = ex.requestHeaders.getFirst("Range")
            var start = 0L
            var end = total - 1
            var partial = false
            if (rangeHeader != null) {
                val m = Regex("""bytes=(\d+)-(\d*)""").find(rangeHeader)
                if (m != null) {
                    partial = true
                    start = m.groupValues[1].toLong()
                    if (m.groupValues[2].isNotEmpty()) end = m.groupValues[2].toLong()
                }
            }
            if (end >= total) end = total - 1
            val length = end - start + 1
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            if (partial) {
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
                ex.sendResponseHeaders(206, length)
            } else {
                ex.sendResponseHeaders(200, total)
            }
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var remaining = length
                ex.responseBody.use { os ->
                    while (remaining > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        os.write(buf, 0, n)
                        remaining -= n
                    }
                }
            }
        } catch (_: Exception) {
            // broken pipe / client gone — ignore
        } finally {
            ex.close()
        }
    }
}
