package xdm.integration


import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*
import xdm.app.AppContext
import xdm.app.BatchRequest
import xdm.app.BatchRequestItem
import xdm.app.utils.rememberedAutoCategorize
import xdm.app.utils.rememberedBaseFolder
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.util.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.*

object BrowserIntegration {
    /** The port the extension talks to, and the process-wide lock on "being XDM" (see [acquire]). */
    const val PORT = 8597

    private const val LOOPBACK = "127.0.0.1"

    /** Loopback only, so these are generous. */
    private const val PROBE_CONNECT_TIMEOUT_MS = 500
    private const val PROBE_READ_TIMEOUT_MS = 1500

    private lateinit var server: HttpServer
    private var serverSocket: ServerSocket? = null
    /**
     * The one Json for the extension protocol. [ignoreUnknownKeys] so a newer extension may add
     * fields without breaking an older app, and [encodeDefaults] because the extension reads fields
     * whose Kotlin-side value happens to be the default (`bye`, `version`) - omitting those makes a
     * protocol message unreadable on the other side.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val blockedHeaders = setOf(
        "accept",
//        "authorization",
        "connection",
        "expect",
        "te",
        "upgrade",
        "range",
        "cookie",
        "transfer-encoding",
        "content-type",
        "content-length",
        "content-encoding",
        "accept-encoding"
    )

    /**
     * Whole header families that must not be replayed: conditionals (`If-None-Match`,
     * `If-Modified-Since`, ...) would turn the fetch into a 304 with no body, and `Proxy-*` belongs
     * to the browser's proxy hop, not ours.
     */
    private val blockedHeaderPrefixes = listOf("if-", "proxy-")

    private val extensionOriginSchemes = listOf(
        "chrome-extension://",
        "moz-extension://",
        "extension://",
        "safari-web-extension://"
    )
    private val stateChangingPaths =
        setOf("/download", "/media", "/vid", "/batch", "/clear", "/clear-tab", "/tab-update", "/show", "/poll")

    /** What [acquire] found when it tried to take ownership of [PORT]. */
    sealed interface Acquired {
        /** This process owns the port: it is the one XDM. Carry on starting up, then call [serve]. */
        object Primary : Acquired

        /** Another XDM already owns it and has been asked to show itself. This process should exit. */
        object AnotherInstance : Acquired

        /** Something that is not XDM holds the port. XDM cannot run; tell the user and exit. */
        object PortTaken : Acquired
    }

    /**
     * Takes ownership of the local port, which doubles as XDM's single-instance lock.
     *
     * The port is the lock because it is the one the OS releases on a crash - there is no stale
     * lock file to reason about - and because it is already the channel for handing work to the
     * instance that holds it. A second launch therefore does not fight for the port: it asks the
     * running XDM to show its window ([postShow]) and exits.
     *
     * A busy port is not assumed to be XDM: an unrelated process can be sitting on it, and in that
     * case XDM must say so rather than silently exit and look broken.
     */
    fun acquire(args: Array<String>, port: Int = PORT): Acquired {
        bind(port)?.let {
            serverSocket = it
            return Acquired.Primary
        }
        if (!looksLikeXdm(port)) {
            Logger.error("INTEGRATION", "Port $port is held by something that is not XDM")
            return Acquired.PortTaken
        }
        if (postShow(args, port)) {
            return Acquired.AnotherInstance
        }
        // The instance we just probed went away between the probe and the request, so the port may
        // be free again: one more try before giving up, otherwise this launch ends with no XDM at
        // all even though the user asked for one.
        Logger.info("INTEGRATION", "The running instance did not answer; retrying the bind")
        bind(port)?.let {
            serverSocket = it
            return Acquired.Primary
        }
        return if (looksLikeXdm(port)) Acquired.AnotherInstance else Acquired.PortTaken
    }

    /** Starts serving the socket taken by [acquire]. Call once the app's services are up. */
    fun serve() {
        val socket = serverSocket ?: throw IllegalStateException("serve() without a successful acquire()")
        server = HttpServer(socket, ::handleRequest)
        server.start()
        // Release parked polls on the way out (quit, SIGTERM, exitProcess) so the extension is told
        // XDM is going away instead of having to infer it from the dropped connection.
        // EventChannel is loaded here, not first in the hook: if the jar was replaced while XDM ran
        // (a package upgrade), the hook could no longer load it and the polls stayed parked.
        val events = EventChannel
        Runtime.getRuntime().addShutdownHook(Thread {
            events.shutdown()
            server.stop()
        })
    }

    /** Binds the port, or returns null if someone else already holds it. */
    private fun bind(port: Int): ServerSocket? = runCatching {
        ServerSocket().apply { bind(InetSocketAddress(LOOPBACK, port)) }
    }.getOrNull()

    /**
     * Asks whoever holds the port whether they are XDM. Deliberately lenient about which fields it
     * finds: during an upgrade a new binary can meet a running older one whose `/sync` predates
     * some of them.
     */
    private fun looksLikeXdm(port: Int): Boolean = runCatching {
        val body = open("http://$LOOPBACK:$port/sync", port).run {
            requestMethod = "GET"
            if (responseCode != 200) return false
            inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        }
        listOf("\"instanceId\"", "\"enabled\"", "\"fileExts\"").any { it in body }
    }.getOrDefault(false)

    /**
     * Tells the running instance to come to the front. The arguments this launch was given travel
     * with it: nothing consumes them yet, but a launch is only ever handed off this way, so this is
     * where a future `xdm-app://...` URL would arrive.
     */
    private fun postShow(args: Array<String>, port: Int): Boolean = runCatching {
        val payload = synchronized(json) { json.encodeToString(ShowRequest(args.toList())) }
            .toByteArray(StandardCharsets.UTF_8)
        open("http://$LOOPBACK:$port/show", port).run {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(payload) }
            val code = responseCode
            runCatching { inputStream.use { it.readBytes() } }
            code == 200
        }
    }.getOrElse {
        Logger.info("INTEGRATION", "Could not reach the running instance: ${it.message}")
        false
    }

    /**
     * A connection to the running instance. [Proxy.NO_PROXY] matters: the user's configured proxy
     * must never be consulted for a loopback handoff between two XDM processes.
     */
    private fun open(url: String, port: Int): HttpURLConnection =
        (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = PROBE_CONNECT_TIMEOUT_MS
            readTimeout = PROBE_READ_TIMEOUT_MS
            useCaches = false
        }

    private fun handleRequest(context: RequestContext) {
        // Every reply but the long poll's is the last one on its connection: the handler thread ends
        // with the response instead of parking in the keep-alive loop. The poll re-connects each time.
        context.keepAlive = false
        rejectionStatus(context)?.let { (code, message) ->
            Logger.info(
                "INTEGRATION",
                "Rejected ${context.requestMethod} ${context.requestPath} from origin=${context.getRequestHeader("Origin")}: $code $message"
            )
            context.apply {
                statusCode = code
                statusMessage = message
                responseBody = ByteArray(0)
            }.sendResponse()
            return
        }
        if (context.requestPath == "/poll") {
            onPollMessage(context)
            return
        }
        when (context.requestPath) {
            "/download" -> onDownloadMessage(context)
            "/batch" -> onBatchMessage(context)
            "/media" -> onMediaMessage(context)
            "/vid" -> onVideoDownloadMessage(context)
            "/clear" -> AppContext.videoTracker.clear()
            "/clear-tab" -> onClearTabMessage(context)
            "/tab-update" -> onTabUpdateMessage(context)
            "/show" -> onShowMessage(context)
        }
        onSyncMessage(context)
    }

    /**
     * Blocks requests made by web pages, which share the extension's loopback address.
     *
     * - A page's requests carry its own `Origin` (`http(s)://...`, or `null` from sandboxed frames),
     *   so any `Origin` that isn't a browser-extension scheme is refused. Requests with no `Origin`
     *   (the extension's GET /sync, local tools) are allowed.
     * - Pages can send simple GETs without an `Origin` (e.g. `<img src>`), so paths that change
     *   state only accept POST.
     *
     * Returns the (status code, message) to reply with, or null if the request is allowed.
     */
    private fun rejectionStatus(context: RequestContext): Pair<Int, String>? {
        val origin = context.getRequestHeader("Origin")
        if (origin != null && extensionOriginSchemes.none { origin.startsWith(it, ignoreCase = true) }) {
            return Pair(403, "Forbidden")
        }
        if (context.requestPath.substringBefore('?') in stateChangingPaths && context.requestMethod != "POST") {
            return Pair(405, "Method Not Allowed")
        }
        return null
    }

    /**
     * The long poll. Parks the request until something the extension cares about changes, the wait
     * times out, or XDM exits; the reply body is the same [ConfigDto] `/sync` returns, so the
     * extension applies it through one code path.
     */
    private fun onPollMessage(context: RequestContext) {
        val request = context.requestBody?.let { body ->
            runCatching {
                synchronized(json) { json.decodeFromString<PollRequest>(body.toString(StandardCharsets.UTF_8)) }
            }.getOrNull()
        } ?: PollRequest()

        when (EventChannel.await(request.clientId ?: "default", request.version)) {
            EventChannel.Outcome.CHANGED -> onSyncMessage(context)
            EventChannel.Outcome.BYE -> context.apply {
                statusCode = 200
                statusMessage = "OK"
                addResponseHeader("Content-Type", "application/json")
                responseBody = synchronized(json) { json.encodeToString(ByeDto()) }.toByteArray(StandardCharsets.UTF_8)
            }.sendResponse()
            // Nothing to report (or this poll was replaced / turned away): the extension polls again.
            else -> context.apply {
                statusCode = 204
                statusMessage = "No Content"
                responseBody = ByteArray(0)
            }.sendResponse()
        }
    }

    /**
     * A second launch handing over to this one: bring the window up. Reachable only by a local
     * process - it is POST-only and every request carrying a web page's `Origin` is refused before
     * this point - and it does nothing but raise a window, which is also what the tray icon does.
     */
    private fun onShowMessage(context: RequestContext) {
        Logger.info("INTEGRATION", "Another launch asked this instance to show itself")
        // The arguments are read (and logged) but not acted on yet; see ShowRequest.
        context.requestBody?.let { content ->
            runCatching {
                val str = content.toString(StandardCharsets.UTF_8)
                synchronized(json) { json.decodeFromString<ShowRequest>(str) }
            }.onSuccess { Logger.info("INTEGRATION", "Launch arguments: ${it.args}") }
        }
        AppContext.app.showAppWindow()
    }

    /**
     * Logs what a message is about. Never the message itself: its cookie and request header values
     * carry the user's credentials, and the log folder is not the place for them.
     */
    private fun logMessage(m: ExtensionMessage) {
        Logger.info(
            "INTEGRATION",
            "url=${m.url} tab=${m.tabId} mime=${m.mimeType} size=${m.fileSize} vid=${m.vid} " +
                    "cookie=${if (m.cookie.isNullOrEmpty()) "no" else "yes"} headers=${m.requestHeaders?.keys}"
        )
    }

    /**
     * A tab started loading a different document (reload, or navigation to another page), so
     * everything detected in it is stale.
     */
    private fun onClearTabMessage(context: RequestContext) {
        Logger.info("Received clear-tab message..")
        val extMsg = context.requestBody?.let { content ->
            val str = content.toString(StandardCharsets.UTF_8)
            synchronized(json) { json.decodeFromString<ExtensionMessage>(str) }.also { logMessage(it) }
        } ?: return
        val tabId = extMsg.tabId ?: return
        VideoHelper.onTabNavigated(tabId)
        AppContext.videoTracker.clearTab(tabId)
    }

    /**
     * The tab's title arrived (usually after the media request that was captured there), so the
     * videos detected on that page can be named after it.
     */
    private fun onTabUpdateMessage(context: RequestContext) {
        Logger.info("Received tab update message..")
        val extMsg = context.requestBody?.let { content ->
            val str = content.toString(StandardCharsets.UTF_8)
            synchronized(json) { json.decodeFromString<ExtensionMessage>(str) }.also { logMessage(it) }
        } ?: return
        val tabUrl = extMsg.tabUrl ?: return
        val tabTitle = extMsg.tabTitle?.takeIf { it.isNotBlank() } ?: return
        AppContext.videoTracker.updateMediaTitle(tabUrl, tabTitle)
    }

    private fun onVideoDownloadMessage(context: RequestContext) {
        Logger.info("Received video download message..")
        context.requestBody?.let { content ->
            val str = content.toString(StandardCharsets.UTF_8)
            val extMsg: ExtensionMessage
            synchronized(json) {
                extMsg = json.decodeFromString<ExtensionMessage>(str)
            }
            logMessage(extMsg)
            removeBlockedHeaders(extMsg)
            extMsg.vid?.let { AppContext.videoTracker.addVideoDownload(it) }
        }
    }

    private fun onMediaMessage(context: RequestContext) {
        Logger.info("Received media message..")
        context.requestBody?.let { content ->
            val str = content.toString(StandardCharsets.UTF_8)
            val extMsg: ExtensionMessage
            synchronized(json) {
                extMsg = json.decodeFromString<ExtensionMessage>(str)
            }
            logMessage(extMsg)
            removeBlockedHeaders(extMsg)
            VideoHelper.processMediaMessage(extMsg)
        }
    }

    private fun onDownloadMessage(context: RequestContext) {
        Logger.info("Received download message..")
        context.requestBody?.let {
            val str = it.toString(StandardCharsets.UTF_8)
            val extMsg: ExtensionMessage
            synchronized(json) {
                extMsg = json.decodeFromString<ExtensionMessage>(str)
            }
            logMessage(extMsg)
            removeBlockedHeaders(extMsg)
            extMsg.url?.let {
                AppContext.app.addDownload(toHttpSource(extMsg))
            }
        }
    }

    /** The extension's "Download all": opens the batch dialog. Replies at once, with the usual sync. */
    private fun onBatchMessage(context: RequestContext) {
        val body = context.requestBody ?: return
        val msg = runCatching {
            synchronized(json) { json.decodeFromString<BatchMessage>(body.toString(StandardCharsets.UTF_8)) }
        }.onFailure { Logger.error("INTEGRATION", "Invalid batch message", it) }.getOrNull() ?: return
        val request = toBatchRequest(msg) ?: return
        // Counts only: never log cookies or the links themselves.
        Logger.info(
            "INTEGRATION",
            "Batch of ${request.items.size} links, ${request.cookies.size} cookie groups, from ${msg.tabUrl}"
        )
        AppContext.app.showBatchDialog(request)
    }

    private fun onSyncMessage(context: RequestContext) {
        val data = ConfigDto(
            enabled = true,
            fileExts = AppContext.config.fileExtensions,
            blockedHosts = AppContext.config.blockedHosts,
            requestFileExts = AppContext.config.videoExtensions,
            mediaTypes = listOf("audio/", "video/", "mpeg", "dash"),
            matchingHosts = emptyList(),
            version = EventChannel.currentVersion,
            instanceId = EventChannel.instanceId,
            videoList = AppContext.videoTracker.videoList.map {
                VideoItem(
                    id = "${it.id}",
                    text = it.name,
                    info = it.description,
                    tabId = it.tabId
                )
            },
        )
        context.apply {
            statusCode = 200
            statusMessage = "OK"
            addResponseHeader("Content-Type", "application/json")
            addResponseHeader("Cache-Control", "max-age=0, no-cache, must-revalidate")
            synchronized(json) {
                responseBody = json.encodeToString(data).toByteArray(StandardCharsets.UTF_8)
            }
        }.sendResponse()
    }

    private fun removeBlockedHeaders(msg: ExtensionMessage) {
        val filteredHeaders = mutableMapOf<String, List<String>>()
        filteredHeaders.putAll(msg.requestHeaders?.filter {
            val name = it.key.lowercase(Locale.ENGLISH)
            !blockedHeaders.contains(name) && blockedHeaderPrefixes.none { prefix -> name.startsWith(prefix) }
        } ?: return)
        msg.requestHeaders.clear()
        msg.requestHeaders.putAll(filteredHeaders)
    }

    private fun toHttpSource(msg: ExtensionMessage): HttpDownloadTaskInfo {
        val respHeaders =
            msg.responseHeaders?.map { entry -> entry.key to entry.value.map { it.value } }?.associate { it }
        return HttpDownloadTaskInfo(
            id = CoreUtils.uniqueId(),
            url = msg.url!!,
            fileName = FileUtils.sanitizeFileName(
                // Chrome's suggested name may include sub-directories (e.g. "sub/file.zip");
                // reduce it to the base name so separators aren't mangled into underscores.
                msg.filename?.let { FileUtils.getFileName(it) } ?: FileUtils.getFileName(msg.url)
            )!!,
            respectFileName = false,
            cookie = msg.cookie,
            headers = msg.requestHeaders,
            origin = msg.referer ?: msg.tabUrl,
            // Follow the user's last "Save in" choice, so a browser download honours both their
            // download folder and the "Automatic (by file type)" option like a dialog download does.
            autoCategorize = rememberedAutoCategorize(),
            defaultDownloadFolder = rememberedBaseFolder(),
            userSelectedDownloadFolder = null,
            maxPiece = AppContext.config.maxSegments,
            authInfo = null,
            knownFileSize = msg.fileSize ?: getContentLength(respHeaders),
            etag = getHeader("etag", respHeaders),
        )
    }
}

@Serializable
data class ConfigDto(
    val enabled: Boolean,
    val fileExts: List<String>,
    val blockedHosts: List<String>,
    val requestFileExts: List<String>,
    val mediaTypes: List<String>,
    val matchingHosts: List<String>,
    val videoList: List<VideoItem>,
    /**
     * State version this snapshot was taken at. A poll reply and a piggybacked `/sync` reply travel
     * on different connections, so the extension uses this to drop one that arrives out of order.
     */
    val version: Long = 0,
    /**
     * Which run of XDM [version] belongs to. Versions from different runs are not comparable, so the
     * extension starts over whenever this changes.
     */
    val instanceId: String = "",
)

/** Body of a `/poll` request: who is asking, and what they have already seen. */
@Serializable
data class PollRequest(val clientId: String? = null, val version: Long = 0)

/**
 * Body of a `/show` request: the command line of the launch that handed over to this instance.
 * Nothing consumes [args] yet - it is the seam for a future `xdm-app://...` URL handler, and
 * `IAppInstance.run` ignores its own arguments today too.
 */
@Serializable
data class ShowRequest(val args: List<String> = emptyList())

/** Sent to parked polls when XDM is shutting down. */
@Serializable
data class ByeDto(val bye: Boolean = true)

@Serializable
data class VideoItem(val id: String, val text: String, val info: String, val tabId: String? = null)

@Serializable
data class ExtensionMessage(
    val url: String? = null,
    val cookie: String? = null,
    val requestHeaders: MutableMap<String, List<String>>? = null,
    val responseHeaders: MutableMap<String, List<SafeStr>>? = null,
    val filename: String? = null,
    val file: String? = null,
    val method: String? = null,
    val userAgent: String? = null,
    val tabUrl: String? = null,
    val tabId: String? = null,
    val tabTitle: String? = null,
    val referer: String? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null,
    val vid: Long? = null,
)

@Serializable
@JvmInline // Required for inline classes in JVM
value class SafeStr(@Serializable(with = IntAsStringSerializer::class) val value: String)

object IntAsStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IntAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        return when (val value = decoder.decodeSerializableValue(JsonElement.serializer())) {
            is JsonPrimitive -> value.content  // Handles both int and string input
            else -> throw SerializationException("Expected primitive for string value")
        }
    }
}



/** `POST /batch` body: see docs/bulk-download.md. */
@Serializable
data class BatchMessage(
    val tabUrl: String? = null,
    val tabTitle: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val groups: List<BatchMessageGroup> = emptyList(),
)

@Serializable
data class BatchMessageGroup(
    val cookie: String? = null,
    val items: List<BatchMessageItem> = emptyList(),
)

@Serializable
data class BatchMessageItem(
    val url: String,
    val filename: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
)

/** The most links one batch takes, as the extension also enforces. */
const val MAX_BATCH_ITEMS = 5000

/**
 * The dialog's view of a [BatchMessage], checked again rather than trusting the sender: http(s) links
 * only, each once, at most [MAX_BATCH_ITEMS]. Identical cookie strings share one entry. Null when no
 * link is left.
 */
fun toBatchRequest(msg: BatchMessage): BatchRequest? {
    val cookies = ArrayList<String>()
    val cookieIndex = HashMap<String, Int>()
    val seen = HashSet<String>()
    val items = ArrayList<BatchRequestItem>()
    loop@ for (group in msg.groups) {
        val cookie = group.cookie?.takeIf { it.isNotBlank() }
        for (item in group.items) {
            if (items.size >= MAX_BATCH_ITEMS) break@loop
            val url = item.url.trim()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) continue
            if (!seen.add(url)) continue
            val cookieGroup = cookie?.let { c -> cookieIndex.getOrPut(c) { cookies.add(c); cookies.size - 1 } } ?: -1
            items.add(
                BatchRequestItem(
                    url = url,
                    fileName = item.filename?.takeIf { it.isNotBlank() },
                    cookieGroup = cookieGroup,
                    knownSize = item.fileSize?.takeIf { it > 0 },
                )
            )
        }
    }
    if (items.isEmpty()) return null
    val headers = buildMap<String, List<String>> {
        msg.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", listOf(it)) }
        msg.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", listOf(it)) }
    }
    return BatchRequest(
        items = items,
        fromBrowser = true,
        pageUrl = msg.tabUrl?.takeIf { it.isNotBlank() },
        pageTitle = msg.tabTitle,
        headers = headers.ifEmpty { null },
        cookies = cookies,
    )
}
