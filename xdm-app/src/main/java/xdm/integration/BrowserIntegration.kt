package xdm.integration


import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import xdm.app.AppContext
import xdm.app.utils.rememberedAutoCategorize
import xdm.app.utils.rememberedBaseFolder
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.util.*
import java.nio.charset.StandardCharsets
import java.util.*

object BrowserIntegration {
    private lateinit var server: HttpServer
    private val json = Json { ignoreUnknownKeys = true }
    private val blockedHeaders = setOf(
        "accept",
        "if",
//        "authorization",
        "proxy",
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

    private val extensionOriginSchemes = listOf(
        "chrome-extension://",
        "moz-extension://",
        "extension://",
        "safari-web-extension://"
    )
    private val stateChangingPaths = setOf("/download", "/media", "/vid", "/clear", "/tab-update", "/poll")

    fun start(onSuccess: Runnable?, onFailure: Runnable?) {
        server = HttpServer(
            "127.0.0.1", 8597,
            ::handleRequest,
            { onSuccess?.run() },
            { onFailure?.run() })
        server.start()
        // Release parked polls on the way out (quit, SIGTERM, exitProcess) so the extension is told
        // XDM is going away instead of having to infer it from the dropped connection.
        Runtime.getRuntime().addShutdownHook(Thread {
            EventChannel.shutdown()
            server.stop()
        })
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
            "/media" -> onMediaMessage(context)
            "/vid" -> onVideoDownloadMessage(context)
            "/clear" -> AppContext.videoTracker.clear()
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
                responseBody = synchronized(json) { Json.encodeToString(ByeDto()) }.toByteArray(StandardCharsets.UTF_8)
            }.sendResponse()
            // Nothing to report (or this poll was replaced / turned away): the extension polls again.
            else -> context.apply {
                statusCode = 204
                statusMessage = "No Content"
                responseBody = ByteArray(0)
            }.sendResponse()
        }
    }

    private fun onVideoDownloadMessage(context: RequestContext) {
        Logger.info("Received video download message..")
        context.requestBody?.let { content ->
            val str = content.toString(StandardCharsets.UTF_8)
            Logger.info(str)
            val extMsg: ExtensionMessage
            synchronized(json) {
                extMsg = json.decodeFromString<ExtensionMessage>(str)
            }
            removeBlockedHeaders(extMsg)
            extMsg.vid?.let { AppContext.videoTracker.addVideoDownload(it) }
        }
    }

    private fun onMediaMessage(context: RequestContext) {
        Logger.info("Received media message..")
        context.requestBody?.let { content ->
            val str = content.toString(StandardCharsets.UTF_8)
            Logger.info(str)
            val extMsg: ExtensionMessage
            synchronized(json) {
                extMsg = json.decodeFromString<ExtensionMessage>(str)
            }
            removeBlockedHeaders(extMsg)
            VideoHelper.processMediaMessage(extMsg)
        }
    }

    private fun onDownloadMessage(context: RequestContext) {
        Logger.info("Received download message..")
        context.requestBody?.let {
            val str = it.toString(StandardCharsets.UTF_8)
            Logger.info(str)
            val extMsg: ExtensionMessage
            synchronized(json) {
                extMsg = json.decodeFromString<ExtensionMessage>(str)
            }
            removeBlockedHeaders(extMsg)
            extMsg.url?.let {
                AppContext.app.addDownload(toHttpSource(extMsg))
            }
        }
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
                responseBody = Json.encodeToString(data).toByteArray(StandardCharsets.UTF_8)
            }
        }.sendResponse()
    }

    private fun removeBlockedHeaders(msg: ExtensionMessage) {
        val filteredHeaders = mutableMapOf<String, List<String>>()
        filteredHeaders.putAll(msg.requestHeaders?.filter {
            !blockedHeaders.contains(
                it.key.lowercase(Locale.ENGLISH)
            )
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
            maxPiece = 8,
            authInfo = null,
            knownFileSize = msg.fileSize ?: getContentLength(respHeaders)
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
)

/** Body of a `/poll` request: who is asking, and what they have already seen. */
@Serializable
data class PollRequest(val clientId: String? = null, val version: Long = 0)

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


