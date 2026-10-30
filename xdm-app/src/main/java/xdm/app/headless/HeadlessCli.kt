package xdm.app.headless

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xdm.app.utils.AppDirs
import xdm.app.utils.win.Win32Console
import xdm.core.CoreConfig
import xdm.core.downloaders.CommitResult
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.DownloaderTask
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.PauseEvent
import xdm.core.downloaders.partFileName
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.dash.DashDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.network.http.BasicCredentials
import xdm.core.network.http.HttpAuth
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.CoreUtils
import xdm.core.storage.LocalStorage
import xdm.core.storage.StorageFile
import xdm.core.storage.uniqueFileName
import xdm.core.util.FileUtils
import xdm.core.util.FormatHelper
import xdm.core.util.Logger
import xdm.core.util.PlatformUtils
import xdm.core.util.getFileName
import xdm.integration.AudioChoice
import xdm.integration.FormatChoice
import xdm.integration.StreamChoices
import xdm.integration.StreamPick
import xdm.integration.VideoHelper
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `xdm-app --headless` / `--no-gui`: downloads the URLs on the command line and exits.
 *
 * Talks to xdm-core directly with its own [DownloadHost]: no window, no AWT, no browser-integration
 * port, no download list, no categories (the target folder is always given). It never reads or writes
 * the GUI's state: per-run engine state lives in a scratch folder under the data folder that is
 * removed when the run ends, so an interrupted download starts over next time.
 */
object HeadlessCli {
    const val EXIT_OK = 0
    const val EXIT_FAILED = 1
    const val EXIT_USAGE = 2
    /** Normally not seen: a signal ends the JVM with its own status (130 for Ctrl+C, 143 for SIGTERM). */
    const val EXIT_INTERRUPTED = 130

    private val out: PrintStream get() = System.out
    private val err: PrintStream get() = System.err

    fun run(args: Array<String>): Int {
        System.setProperty("java.awt.headless", "true")
        if (PlatformUtils.isWindows) runCatching { Win32Console.attachToParent() }
        Logger.consoleEnabled = false

        val options = try {
            when (val parsed = CliParser.parse(args)) {
                ParsedArgs.Help -> {
                    out.println(CliParser.USAGE)
                    return EXIT_OK
                }

                is ParsedArgs.Run -> parsed.options
            }
        } catch (e: CliUsageException) {
            err.println("xdm-app: ${e.message}")
            err.println("Try 'xdm-app --headless --help' for more information.")
            return EXIT_USAGE
        }
        Logger.consoleEnabled = options.verbose

        val targetFolder = if (options.listFormats) null else File(options.outputDir).absoluteFile.also {
            if (!it.isDirectory && !it.mkdirs()) {
                err.println("xdm-app: cannot create folder ${it.path}")
                return EXIT_FAILED
            }
        }
        return Session(options, targetFolder).runAll()
    }

    private class Session(private val o: CliOptions, private val targetFolder: File?) {
        private val config = HeadlessConfig(o)
        private val auth = HttpAuth(ConsoleCredentialPrompt(o))
        private val tty = Terminal.stderrInteractive
        private val workRoot = File(AppDirs.root, "headless-${ProcessHandle.current().pid()}")
        private val current = AtomicReference<Job?>(null)
        private val interrupted = AtomicBoolean(false)

        fun runAll(): Int {
            val hook = Thread({ onInterrupt() }, "headless-stop")
            Runtime.getRuntime().addShutdownHook(hook)
            var failures = 0
            try {
                for ((index, url) in o.urls.withIndex()) {
                    if (interrupted.get()) break
                    val label = if (o.urls.size > 1) "[${index + 1}/${o.urls.size}] " else ""
                    if (!runOne(url, label)) failures++
                }
            } finally {
                workRoot.deleteRecursively()
                runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
            return when {
                interrupted.get() -> EXIT_INTERRUPTED
                failures > 0 -> EXIT_FAILED
                else -> EXIT_OK
            }
        }

        /** Ctrl+C: stop the running download, wait for it to let go of its files, then drop the partial output. */
        private fun onInterrupt() {
            interrupted.set(true)
            val job = current.get() ?: return
            job.task?.stop()
            job.done.await(10, TimeUnit.SECONDS)
            job.task?.deleteTemp()
            workRoot.deleteRecursively()
            job.progress.clear()
            err.println("Interrupted")
        }

        private fun runOne(url: String, label: String): Boolean {
            val type = when (o.type) {
                StreamType.Auto -> detectType(url)
                else -> o.type
            }
            val id = CoreUtils.uniqueId()
            val job = try {
                when (type) {
                    StreamType.Hls, StreamType.Dash -> streamJob(id, url, type == StreamType.Dash, label)
                    else -> httpJob(id, url, o.fileName, label)
                }
            } catch (e: VideoHelper.ManifestLoadException) {
                err.println("${label}Cannot load $url: ${e.message}")
                return false
            } catch (e: CliUsageException) {
                err.println("$label${e.message}")
                return false
            } ?: return !o.listFormats || type != StreamType.Http

            current.set(job)
            if (interrupted.get()) return false
            job.task!!.start()
            job.done.await()
            current.set(null)
            job.progress.clear()
            val result = job.result
            return when {
                result is Outcome.Saved -> {
                    if (!o.quiet) err.println("${label}Saved ${FormatHelper.formatSize(result.size.toDouble())}: ${result.path}")
                    out.println(result.path)
                    true
                }

                result is Outcome.Failed && !interrupted.get() -> {
                    err.println("${label}Failed: ${describe(result.error)} ($url)")
                    false
                }

                else -> false
            }
        }

        // ---- building downloads -----------------------------------------------------------

        private fun httpJob(id: Long, url: String, name: String?, label: String, ext: String? = null): Job? {
            if (o.listFormats) {
                err.println("${label}$url is not an HLS or DASH stream: nothing to list (use --type to force one)")
                return null
            }
            val folder = targetFolder!!.path
            val info = HttpDownloadTaskInfo(
                id = id, url = url, fileName = name ?: "", respectFileName = name != null,
                cookie = o.cookie, headers = o.headers, origin = referer(), categoryId = null,
                targetFolder = folder, maxPiece = o.segments, authInfo = null, knownFileSize = null,
            )
            val job = Job(id, DownloadType.Http, name, ext, label)
            job.httpInfo = info
            job.task = HttpDownloaderTask(info, job, newClient(CoreConfig.MAX_SEGMENTS), workDir(id).path, config)
            return job
        }

        private fun streamJob(id: Long, url: String, dash: Boolean, label: String): Job? {
            val client = newClient(10)
            val manifest = try {
                if (dash) VideoHelper.loadDash(url, o.headers, o.cookie, interrupted, client)
                else VideoHelper.loadHls(url, o.headers, o.cookie, interrupted, client)
            } finally {
                client.close()
            }
            val formats = StreamChoices.of(manifest)
            if (formats.isEmpty()) throw VideoHelper.ManifestLoadException("No playable stream")
            if (o.listFormats) {
                printFormats(label, url, formats)
                return null
            }
            val (format, note) = FormatSelector.select(formats, o.quality)
            val audio = FormatSelector.selectAudio(format, o.audio)
            if (!o.quiet) {
                val audioText = audio.label.takeIf { it.isNotBlank() }?.let { " + audio: ${plain(it)}" } ?: ""
                err.println("${label}Format: ${plain(format.label)}$audioText${note?.let { " ($it)" } ?: ""}")
            }
            val folder = targetFolder!!.path
            return when (val pick = audio.pick) {
                is StreamPick.Http -> httpJob(id, pick.url, o.fileName ?: suggestStreamName(url, pick.extension), label)
                is StreamPick.Hls -> {
                    val name = o.fileName ?: suggestStreamName(url, "mp4")
                    val info = HlsDownloadTaskInfo(
                        id = id, fileName = name, tempDir = workDir(id).path, respectFileName = true,
                        cookie = o.cookie, headers = o.headers, origin = referer(), categoryId = null,
                        targetFolder = folder, maxPiece = o.segments, authInfo = null,
                        url = pick.url, audioUrl = pick.audioUrl, audioOnly = pick.audioOnly, independent = pick.independent,
                    )
                    Job(id, DownloadType.Hls, o.fileName, null, label).also {
                        it.streamName = name
                        it.task = HlsDownloaderTask(info, newClient(CoreConfig.MAX_SEGMENTS), TransmuxingMuxer(workRoot.path), it, workDir(id).path, config)
                    }
                }

                is StreamPick.Dash -> {
                    val name = o.fileName ?: suggestStreamName(url, pick.extension)
                    val info = DashDownloadTaskInfo(
                        id = id, fileName = name, tempDir = workDir(id).path, respectFileName = true,
                        cookie = o.cookie, headers = o.headers, origin = referer(), categoryId = null,
                        targetFolder = folder, maxPiece = o.segments, authInfo = null,
                        videoSegments = pick.video.segments, audioSegments = pick.audio.segments,
                        url = manifest.url, audioMime = pick.audio.mimeType, videoMime = pick.video.mimeType,
                    )
                    Job(id, DownloadType.Dash, o.fileName, null, label).also {
                        it.streamName = name
                        it.task = DashDownloaderTask(info, newClient(CoreConfig.MAX_SEGMENTS), TransmuxingMuxer(workRoot.path), it, workDir(id).path, config)
                    }
                }
            }
        }

        private fun printFormats(label: String, url: String, formats: List<FormatChoice>) {
            out.println("${label}Formats of $url:")
            formats.forEachIndexed { i, f ->
                out.println(String.format(Locale.ROOT, "  %3d  %s", i + 1, plain(f.label)))
                if (f.audio.size > 1) f.audio.forEach { a -> out.println("         audio: ${plain(a.label)}") }
            }
        }

        private fun referer(): String? =
            o.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value?.firstOrNull()

        private fun workDir(id: Long) = File(workRoot, id.toString()).apply { mkdirs() }

        private fun newClient(poolSize: Int): PoolingHttpClient = HttpClientImpl(
            poolSize, config.toProxy(), o.insecure, config.readTimeoutSeconds,
            o.proxyUser.orEmpty(), o.proxyPassword.orEmpty(), auth = auth, serverAuth = true,
        )

        // ---- one download -----------------------------------------------------------------

        /** One download and the engine's view of where it goes. */
        private inner class Job(
            val id: Long,
            val type: DownloadType,
            /** The name from --file-name, used as given. */
            val userName: String?,
            /** Extension to add to the server's name (a single-file stream from a manifest). */
            val ext: String?,
            label: String,
        ) : DownloadHost {
            var task: DownloaderTask? = null
            var httpInfo: HttpDownloadTaskInfo? = null

            /** HLS/DASH: the name chosen before the download starts. */
            var streamName: String? = null

            @Volatile
            var httpName: String? = null

            @Volatile
            var size: Long = -1

            @Volatile
            var result: Outcome? = null
            val done = CountDownLatch(1)
            val progress = ProgressLine(label, o.quiet, tty)

            private fun finish(outcome: Outcome) {
                if (result != null) return
                result = outcome
                done.countDown()
            }

            override fun onDownloadActivated(id: Long) {}

            override fun onDownloadInit(data: DownloadStatusInfo.InitInfo, downloadType: DownloadType) {
                data.fileSize?.let { size = it }
                if (downloadType != DownloadType.Http) return
                var name = when {
                    userName != null -> getFileName(userName, true, data.url, data.contentDisposition, data.contentType)
                    else -> getFileName(data.url, data.contentDisposition, data.contentType)
                }
                ext?.let { e -> if (!name.endsWith(".$e", true)) name = "$name.$e" }
                data.videoExt?.let { name += it }
                httpName = FileUtils.sanitizeFileName(name)?.takeIf { it.isNotBlank() } ?: "download"
                progress.name = httpName!!
            }

            override fun onDownloadProgress(event: DownloadStatusInfo.ProgressInfo) {
                if (progress.name.isEmpty()) progress.name = streamName.orEmpty()
                progress.update(event.downloaded, size, event.progress, event.speed, event.eta)
            }

            override fun onAssembleStart(id: Long) = progress.assembling(0)
            override fun onAssembleProgress(event: DownloadStatusInfo.AssembleInfo) = progress.assembling(event.progress)

            override fun onDownloadSuccess(event: DownloadStatusInfo.FinalInfo) {
                workDir(id).deleteRecursively()
                finish(Outcome.Saved(File(event.finalOutputFolder, event.finalFileName).path, event.fileSize))
            }

            override fun onDownloadFailed(id: Long, error: DownloadError) = finish(Outcome.Failed(error))
            override fun onDownloadPaused(id: Long, event: PauseEvent) = finish(Outcome.Stopped)

            override val appDir: String get() = workRoot.path
            override val applySpeedLimit: Boolean get() = o.speedLimit > 0
            override val speedLimit: Int get() = if (o.speedLimit > 0) o.speedLimit else -1

            override fun getTempDir(id: Long, url: String, contentType: String?, contentDisposition: String?): String =
                workDir(id).path

            override fun outputFile(id: Long, downloadType: DownloadType, ext: String): StorageFile? =
                LocalStorage.file(File(targetFolder, partFileName(id) + ext).path)

            override fun commitOutputFile(id: Long, part: StorageFile, downloadType: DownloadType): CommitResult {
                val name = when (downloadType) {
                    DownloadType.Http -> httpName ?: getFileName(httpInfo?.url.orEmpty())
                    // The muxer picks the container: keep a suggested name's extension in step with it.
                    else -> {
                        val actual = part.name.substringAfterLast(partFileName(id), "")
                        val base = streamName ?: "stream"
                        if (userName != null || actual.isEmpty() || base.endsWith(actual, true)) base
                        else base.substringBeforeLast('.') + actual
                    }
                }
                return publish(name, part)
            }

            /** Renames the part file to [fileName] in its own folder: never copies. */
            private fun publish(fileName: String, part: StorageFile): CommitResult {
                val folder = part.folder
                val exists = folder.exists(fileName)
                val finalName = if (exists && !o.overwrite) uniqueFileName(fileName) { folder.exists(it) } else fileName
                return try {
                    folder.rename(part.name, finalName, replace = exists && o.overwrite)
                    CommitResult.Success(fileName = finalName, outputDir = folder.location)
                } catch (e: Exception) {
                    Logger.error("XDM", "Unable to publish $part as $finalName", e)
                    CommitResult.Failed(DownloadError.OutputWriteError)
                }
            }
        }
    }

    private sealed interface Outcome {
        data class Saved(val path: String, val size: Long) : Outcome
        data class Failed(val error: DownloadError) : Outcome
        data object Stopped : Outcome
    }

    /** `.m3u8` is HLS and `.mpd` DASH (by path, then anywhere in the URL for proxied links); anything else HTTP. */
    fun detectType(url: String): StreamType {
        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase(Locale.ROOT)
        val all = url.lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> StreamType.Hls
            path.endsWith(".mpd") -> StreamType.Dash
            ".m3u8" in all -> StreamType.Hls
            ".mpd" in all -> StreamType.Dash
            else -> StreamType.Http
        }
    }

    /** A name from the stream URL; generic manifest names ("master", "index") give way to the folder or host. */
    fun suggestStreamName(url: String, ext: String): String {
        val uri = url.toHttpUrlOrNull()
        val segments = uri?.pathSegments?.filter { it.isNotBlank() }.orEmpty()
        val last = segments.lastOrNull()?.substringBeforeLast('.')
        val generic = setOf("master", "index", "playlist", "manifest", "chunklist", "prog_index", "stream", "main", "video")
        val base = when {
            last != null && last.lowercase(Locale.ROOT) !in generic -> last
            segments.size >= 2 -> segments[segments.size - 2]
            else -> uri?.host ?: "stream"
        }
        return FileUtils.sanitizeFileName("$base.$ext")?.takeIf { it.isNotBlank() } ?: "stream.$ext"
    }

    /** The GUI's format labels use " · "; a console code page may not have it (Windows OEM pages). */
    private fun plain(label: String) = label.replace(" · ", " | ")

    private fun describe(error: DownloadError): String = when (error) {
        DownloadError.NetworkError -> "network error"
        DownloadError.InvalidResponse -> "the server refused the request or sent an invalid response"
        DownloadError.SessionExpired, DownloadError.LinkExpired -> "the link has expired"
        DownloadError.DiskSpaceError -> "not enough disk space, or the file could not be written"
        DownloadError.InternalError -> "internal error (run with --verbose for details)"
        DownloadError.ResumeNotSupported -> "the server does not support resuming"
        DownloadError.MuxError -> "the stream could not be merged into one file"
        DownloadError.TlsError -> "the server's TLS certificate could not be verified (--insecure skips the check)"
        DownloadError.DecryptionError -> "an encrypted segment could not be decrypted"
        DownloadError.OutputWriteError -> "the file could not be saved in the target folder"
        DownloadError.Cancelled -> "cancelled"
    }

    /** Engine settings from the command line; nothing comes from the GUI's config. */
    private class HeadlessConfig(o: CliOptions) : CoreConfig {
        override var speedLimit: Int = o.speedLimit
        override var speedLimiterEnabled: Boolean = o.speedLimit > 0
        override var maxSegments: Int = if (o.segments > 0) o.segments else 8
        override var maxRetries: Int = 5
        override var useProxy: Boolean = o.proxy != null
        override var socksProxy: Boolean = false
        override var proxyHost: String = ""
        override var proxyPort: Int = 0
        override var proxyUser: String = o.proxyUser.orEmpty()
        override var proxyPass: String = o.proxyPassword.orEmpty()

        init {
            o.proxy?.let { CliParser.parseProxy(it) }?.let { (socks, host, port) ->
                socksProxy = socks
                proxyHost = host
                proxyPort = port
            }
        }

        override fun toProxy(): Proxy? = if (!useProxy) null else Proxy(
            if (socksProxy) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)
        )
    }

    /** Answers 401/407 with the command-line credentials, then by asking on the terminal if there is one. */
    private class ConsoleCredentialPrompt(private val o: CliOptions) : xdm.core.network.http.CredentialPrompt {
        override fun ask(scope: xdm.core.network.http.AuthScope, rejected: Boolean): BasicCredentials? {
            val (user, pass) = if (scope.proxy) o.proxyUser to o.proxyPassword else o.user to o.password
            if (!rejected && user != null) return BasicCredentials(user, pass.orEmpty())
            // Only a person at a terminal can answer; in a pipe, cron or CI the login just fails.
            if (!Terminal.stdinInteractive || !Terminal.stderrInteractive) return null
            val console = System.console() ?: return null
            val what = if (scope.proxy) "Proxy ${scope.host}:${scope.port}" else "${scope.host}:${scope.port}"
            val realm = scope.realm?.let { " ($it)" } ?: ""
            System.err.println()
            if (rejected) System.err.println("Login refused by $what")
            val name = console.readLine("User name for $what$realm (empty to give up): ")?.takeIf { it.isNotEmpty() } ?: return null
            val password = console.readPassword("Password: ") ?: return null
            return BasicCredentials(name, String(password))
        }
    }
}

/** Picks a format and audio track from [StreamChoices] output, which lists formats best first. */
object FormatSelector {
    /** The chosen format and, when it is not what was asked for exactly, why. */
    fun select(formats: List<FormatChoice>, quality: Quality): Pair<FormatChoice, String?> {
        val videos = formats.filter { !it.audioOnly }.ifEmpty { formats }
        return when (quality) {
            Quality.Best -> videos.first() to null
            Quality.Worst -> videos.last() to null
            Quality.AudioOnly -> formats.firstOrNull { it.audioOnly }?.let { it to null }
                ?: (formats.last() to "no audio-only format, took the lowest quality")

            is Quality.Index -> formats.getOrNull(quality.position - 1)?.let { it to null }
                ?: throw CliUsageException("--format ${quality.position}: there are ${formats.size} formats")

            is Quality.Height -> {
                val known = videos.filter { it.height > 0 }
                if (known.isEmpty()) return videos.first() to "heights unknown, took the best"
                known.firstOrNull { it.height == quality.pixels }?.let { return it to null }
                val below = known.filter { it.height < quality.pixels }.maxByOrNull { it.height }
                val chosen = below?.let { h -> known.first { it.height == h.height } } ?: known.minBy { it.height }
                chosen to "no ${quality.pixels}p, took ${chosen.height}p"
            }
        }
    }

    /** The track matching [wanted] (part of its label), else the format's default (first) one. */
    fun selectAudio(format: FormatChoice, wanted: String?): AudioChoice {
        if (wanted == null) return format.audio.first()
        return format.audio.firstOrNull { it.label.contains(wanted, ignoreCase = true) } ?: format.audio.first()
    }
}

/**
 * Progress on stderr. On a terminal, one line redrawn in place with `\r` four times a second. Anywhere
 * else (a pipe, `2> log`, cron, CI) plain lines a log can keep: one per 10% step, or every 30 s when
 * the size is unknown, and one when merging starts.
 */
class ProgressLine(private val prefix: String, private val quiet: Boolean, private val tty: Boolean) {
    @Volatile
    var name: String = ""
    private var lastPrint = 0L
    private var lastLength = 0
    private var lastStep = -1
    private var mergeShown = false

    @Synchronized
    fun update(downloaded: Long, size: Long, percent: Int, speed: Float, eta: Long) {
        if (quiet) return
        val known = size > 0 || percent > 0
        if (tty) {
            if (!due(250)) return
        } else if (known) {
            val step = percent.coerceIn(0, 100) / 10
            if (step == lastStep) return
            lastStep = step
        } else if (!due(30_000)) {
            return
        }
        val parts = ArrayList<String>()
        if (known) parts += "${percent.coerceIn(0, 100)}%"
        parts += if (size > 0) "${fmt(downloaded)} / ${fmt(size)}" else fmt(downloaded)
        if (speed >= 1) parts += "${fmt(speed.toLong())}/s"
        if (eta > 0) parts += "ETA ${FormatHelper.toLongEta(eta)}"
        show("${shortName()}  ${parts.joinToString("  ")}")
    }

    @Synchronized
    fun assembling(percent: Int) {
        if (quiet) return
        if (tty) {
            if (due(250) || percent == 0) show("${shortName()}  merging ${percent.coerceIn(0, 100)}%")
        } else if (!mergeShown) {
            mergeShown = true
            show("${shortName()}  merging")
        }
    }

    /** Erases the redrawn line so the next message starts clean. */
    @Synchronized
    fun clear() {
        if (quiet || !tty || lastLength == 0) return
        System.err.print("\r" + " ".repeat(lastLength) + "\r")
        System.err.flush()
        lastLength = 0
    }

    private fun due(intervalMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPrint < intervalMs) return false
        lastPrint = now
        return true
    }

    private fun show(text: String) {
        val line = prefix + text
        if (tty) {
            System.err.print("\r" + line + " ".repeat((lastLength - line.length).coerceAtLeast(0)))
            System.err.flush()
            lastLength = line.length
        } else {
            System.err.println(line)
        }
    }

    private fun shortName(): String = name.ifEmpty { "connecting" }.let { if (it.length > 40) it.take(37) + "..." else it }

    private fun fmt(bytes: Long) = FormatHelper.formatSize(bytes.toDouble())
}
