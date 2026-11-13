package xdm.app.ytdlp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import xdm.app.AppContext
import xdm.app.PostDownloadCommand
import xdm.core.util.Logger
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One fragment of a `http_dash_segments` format: an absolute [url], or a [path] under the base URL;
 * [duration] in seconds when yt-dlp gives one (the init segment has none).
 */
data class YtFragment(val url: String?, val path: String?, val duration: Double? = null)

/** The fields of one yt-dlp format that a download needs. */
data class YtFormat(
    val id: String,
    val protocol: String,
    val url: String?,
    val manifestUrl: String?,
    val fragmentBaseUrl: String?,
    val fragments: List<YtFragment>,
    val headers: Map<String, String>,
    /** yt-dlp's own cookies for [url] (`name=value; Domain=…; Path=…`), not the browser's. */
    val cookies: String?,
    val fileSize: Long?,
    val fileSizeApprox: Long?,
    val vcodec: String?,
    val acodec: String?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val tbr: Double?,
    val abr: Double?,
    val ext: String?,
    val hasDrm: Boolean,
    /** `downloader_options.http_chunk_size`: the largest request the site serves at full speed. */
    val chunkSize: Long?,
    val formatNote: String?,
) {
    /** A missing codec is unknown, not absent: a plain file without codec info counts as both. */
    val hasVideo: Boolean get() = vcodec != "none"
    val hasAudio: Boolean get() = acodec != "none"
    val size: Long? get() = fileSize ?: fileSizeApprox
    val sizeExact: Boolean get() = fileSize != null

    /** Downloaded by XDM's engine: plain files, HLS media playlists and DASH fragments. */
    val supported: Boolean
        get() = !hasDrm && when (protocol) {
            "https", "http" -> url != null
            "m3u8_native", "m3u8" -> url != null
            "http_dash_segments" -> fragments.isNotEmpty()
            else -> false
        }

    val kind: Kind
        get() = when (protocol) {
            "m3u8_native", "m3u8" -> Kind.HLS
            "http_dash_segments" -> Kind.FRAGMENTS
            else -> Kind.FILE
        }

    enum class Kind { FILE, HLS, FRAGMENTS }

    /** The Cookie header for [url] from [cookies]: the name=value pairs, without their attributes. */
    val cookieHeader: String? get() = cookies?.let { cookieHeaderOf(it) }
}

data class YtInfo(
    val title: String,
    val webpageUrl: String,
    val extractor: String?,
    val duration: Double?,
    val isLive: Boolean,
    val formats: List<YtFormat>,
)

class YtDlpException(message: String, val stderr: String = "") : IOException(message) {
    /** yt-dlp's own words for the failure: its last `ERROR:` line. */
    val reason: String
        get() = stderr.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
            ?: message ?: "yt-dlp failed"

    /** A site change an older yt-dlp cannot handle: updating it is the fix, when a newer one exists. */
    val looksOutdated: Boolean
        get() = Regex("(?i)unable to extract|please report this issue|update to the latest|" +
            "nsig extraction failed|signature extraction failed|requested format is not available")
            .containsMatchIn(stderr)

    /**
     * No extractor took the URL and the generic one found no media on the page: the site is not one
     * yt-dlp knows. yt-dlp has no cheaper way to ask this (its generic extractor accepts any URL, and
     * the binary does not expose the per-site URL patterns), so its error is the check.
     */
    val unsupportedUrl: Boolean
        get() = stderr.lineSequence().any { it.startsWith("ERROR: Unsupported URL", ignoreCase = true) }
}

/**
 * Runs yt-dlp to resolve a page into formats (`-J`): nothing is downloaded by yt-dlp. XDM downloads the
 * formats with its own engine. No browser cookies are passed and the user's yt-dlp config is ignored,
 * so the output depends only on the page and the extra arguments set in the Video download dialog.
 */
object YtDlp {
    const val EXTRACTOR_ID = "yt-dlp"
    private val json = Json { ignoreUnknownKeys = true }

    fun command(
        ytDlp: String, deno: String?, extraArgs: String, pageUrl: String, formatIds: List<String> = emptyList(),
    ): List<String> {
        val cmd = arrayListOf(ytDlp, "-J", "--no-playlist", "--ignore-config", "--no-progress")
        // The deno from XDM's tools folder is not on PATH: name it. A system one is found by yt-dlp itself.
        deno?.let { cmd.add("--js-runtimes"); cmd.add("deno:$it") }
        if (formatIds.isNotEmpty()) {
            cmd.add("-f"); cmd.add(formatIds.joinToString("+"))
        }
        cmd.addAll(PostDownloadCommand.tokenize(extraArgs))
        // A pasted "URL" starting with '-' must not be read as an option.
        cmd.add("--")
        cmd.add(pageUrl)
        return cmd
    }

    /**
     * Resolves [pageUrl]. Blocking; [cancelled] or [processRef] stops it. Throws [YtDlpException] with
     * yt-dlp's error text on failure.
     */
    fun resolve(
        pageUrl: String, cancelled: AtomicBoolean, formatIds: List<String> = emptyList(),
        processRef: AtomicReference<Process?> = AtomicReference(),
    ): YtInfo {
        val ytDlp = ExternalTools.find(ExternalTool.YT_DLP) ?: throw YtDlpException("yt-dlp not found")
        val deno = ExternalTools.find(ExternalTool.DENO)?.takeIf { it.origin != ToolOrigin.SYSTEM }?.path
        val cmd = command(ytDlp.path, deno, AppContext.config.ytDlpExtraArgs, pageUrl, formatIds)
        Logger.info("ytdlp", "Running ${cmd.dropLast(1)} <url>")
        val lock = ToolInstaller.inUse.readLock()
        lock.lock()
        try {
            val out = run(cmd, cancelled, processRef)
            return parse(out)
        } finally {
            lock.unlock()
        }
    }

    private fun run(cmd: List<String>, cancelled: AtomicBoolean, processRef: AtomicReference<Process?>): String {
        val process = ProcessBuilder(cmd).start()
        processRef.set(process)
        process.outputStream.close()
        val stderr = StringBuilder()
        val errReader = Thread({
            runCatching { process.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it) } } }
        }, "yt-dlp-stderr").apply { isDaemon = true; start() }
        val stdout = AtomicReference<String>("")
        val outReader = Thread({
            runCatching { stdout.set(process.inputStream.bufferedReader().readText()) }
        }, "yt-dlp-stdout").apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.get()) throw YtDlpException("cancelled")
                if (System.currentTimeMillis() > deadline) throw YtDlpException("yt-dlp took too long")
            }
            outReader.join(5000)
            errReader.join(2000)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            processRef.set(null)
        }
        val err = synchronized(stderr) { stderr.toString() }
        if (process.exitValue() != 0) {
            Logger.info("ytdlp", "yt-dlp exited ${process.exitValue()}: ${err.trim().take(2000)}")
            throw YtDlpException("yt-dlp exited ${process.exitValue()}", err)
        }
        return stdout.get()
    }

    private const val TIMEOUT_MS = 120_000L

    /** `yt-dlp -J` output. A playlist (`--no-playlist` should prevent it) uses its first entry. */
    fun parse(body: String): YtInfo {
        val root = runCatching { json.parseToJsonElement(body) as JsonObject }
            .getOrElse { throw YtDlpException("yt-dlp gave no usable output") }
        val o = if (root.str("_type") == "playlist") {
            (root["entries"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw YtDlpException("The playlist is empty")
        } else root
        val formats = (o["formats"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::format) }
            // A page with one file has no formats list: the info itself is the format.
            ?: listOfNotNull(format(o))
        return YtInfo(
            title = o.str("title") ?: o.str("id") ?: "video",
            webpageUrl = o.str("webpage_url") ?: o.str("original_url") ?: "",
            extractor = o.str("extractor_key") ?: o.str("extractor"),
            duration = o.dbl("duration"),
            // Live streams (and ones about to start, or just ended and still being processed) are not
            // supported: they have no fixed length to download in pieces.
            isLive = o.bool("is_live") == true || o.str("live_status") in setOf("is_live", "is_upcoming", "post_live"),
            formats = formats,
        )
    }

    private fun format(f: JsonObject): YtFormat? {
        val id = f.str("format_id") ?: return null
        val headers = (f["http_headers"] as? JsonObject)?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
        }?.toMap().orEmpty()
        val fragments = (f["fragments"] as? JsonArray)?.mapNotNull { e ->
            val fo = e as? JsonObject ?: return@mapNotNull null
            YtFragment(fo.str("url"), fo.str("path"), fo.dbl("duration")).takeIf { it.url != null || it.path != null }
        }.orEmpty()
        return YtFormat(
            id = id,
            protocol = f.str("protocol") ?: "https",
            url = f.str("url"),
            manifestUrl = f.str("manifest_url"),
            fragmentBaseUrl = f.str("fragment_base_url"),
            fragments = fragments,
            headers = headers,
            cookies = f.str("cookies"),
            fileSize = f.lng("filesize"),
            fileSizeApprox = f.lng("filesize_approx"),
            vcodec = f.str("vcodec"),
            acodec = f.str("acodec"),
            width = f.lng("width")?.toInt(),
            height = f.lng("height")?.toInt(),
            fps = f.dbl("fps"),
            tbr = f.dbl("tbr"),
            abr = f.dbl("abr"),
            ext = f.str("ext"),
            hasDrm = f.bool("has_drm") == true,
            chunkSize = (f["downloader_options"] as? JsonObject)?.lng("http_chunk_size"),
            formatNote = f.str("format_note"),
        )
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.lng(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.dbl(key: String): Double? = prim(key)?.doubleOrNull
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
}

private val cookieAttributes = setOf("domain", "path", "secure", "expires", "version", "max-age", "httponly", "samesite")

/**
 * yt-dlp's `cookies` field is a Set-Cookie-like list already limited to the format's URL:
 * `a=1; Domain=.x.com; Path=/; Secure; b=2; …`. The Cookie header is the name=value pairs alone.
 */
internal fun cookieHeaderOf(cookies: String): String? = cookies.split(';')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .filter { part ->
        val name = part.substringBefore('=').trim().lowercase()
        part.contains('=') && name !in cookieAttributes
    }
    .joinToString("; ")
    .ifEmpty { null }
