package xdm.integration


import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import xdm.app.AppContext
import xdm.app.models.BrowserDownloadInfo
import xdm.core.CONTENT_TYPE
import xdm.core.REFERER
import xdm.core.USER_AGENT
import xdm.core.downloaders.http.HttpSource
import xdm.core.net.getContentLength
import xdm.core.net.getHeader
import xdm.core.net.getModifiedDate
import xdm.core.network.http.HeaderCollection
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.UniqueID
import java.nio.charset.StandardCharsets
import java.util.*

object BrowserIntegration {
    private lateinit var server: HttpServer
    private val json = Json { ignoreUnknownKeys = true }
    private val blockedHeaders = setOf(
        "accept",
        "if",
        "authorization",
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

    fun start(onSuccess: Runnable?, onFailure: Runnable?) {
        server = HttpServer(
            "127.0.0.1", 8597,
            ::handleRequest,
            { onSuccess?.run() },
            { onFailure?.run() })
        server.start()
    }

    private fun handleRequest(context: RequestContext) {
        //Logger.info(BrowserIntegration.javaClass.name, context.requestPath)
        when (context.requestPath) {
            "/download" -> onDownloadMessage(context)
            "/media" -> onMediaMessage(context)
            "/vid" -> onVideoDownloadMessage(context)
        }
        onSyncMessage(context)
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
                AppContext.app.addDownload(toBrowserDownloadInfo(extMsg))
            }
        }
    }

    private fun onSyncMessage(context: RequestContext) {
        val data = ConfigDto(
            enabled = true,
            fileExts = AppContext.appConfig.fileExtList,
            blockedHosts = AppContext.appConfig.blockedHosts,
            requestFileExts = AppContext.appConfig.videoExtList,
            mediaTypes = listOf("audio/", "video/", "mpeg", "dash"),
            matchingHosts = emptyList(),
            videoList = AppContext.videoTracker.videoList.map {
                VideoItem(
                    id = "${it.id}",
                    text = it.name,
                    info = it.description
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

    @JvmStatic
    fun toHttpSource(msg: BrowserDownloadInfo?): HttpSource? {
        if (msg == null) return null
        val source = HttpSource()
        with(source) {
            id = UniqueID.get()
            url = msg.url
            fileName = FileUtils.sanitizeFileName(msg.fileName ?: FileUtils.getFileName(msg.url))
            isAutoSelectFolder = true
            isKeepFileName = true
            cookies = msg.cookie
            headers = HeaderCollection(msg.requestHeaders)
            fileSize = msg.fileSize ?: -1
        }
        return source
    }

    private fun toBrowserDownloadInfo(msg: ExtensionMessage): BrowserDownloadInfo {
        return BrowserDownloadInfo(UniqueID.get(), msg.url!!).apply {
            fileName = FileUtils.sanitizeFileName(msg.file ?: FileUtils.getFileName(msg.url))
            requestHeaders = msg.requestHeaders
            responseHeaders =
                msg.responseHeaders?.map { entry -> entry.key to entry.value.map { it.value } }?.associate { it }
            fileName = msg.file
            cookie = msg.cookie
            fileSize = msg.fileSize ?: getContentLength(responseHeaders)
            httpMethod = msg.method
            userAgent = msg.userAgent ?: getHeader(USER_AGENT, requestHeaders)
            tabUrl = msg.tabUrl
            referer = msg.referer ?: getHeader(REFERER, requestHeaders)
            mimeType = msg.mimeType ?: getHeader(CONTENT_TYPE, responseHeaders)
            modifiedDate = getModifiedDate(responseHeaders)
        }
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
)

@Serializable
data class VideoItem(val id: String, val text: String, val info: String)

@Serializable
data class ExtensionMessage(
    val url: String? = null,
    val cookie: String? = null,
    val requestHeaders: MutableMap<String, List<String>>? = null,
    val responseHeaders: MutableMap<String, List<SafeStr>>? = null,
    val file: String? = null,
    val method: String? = null,
    val userAgent: String? = null,
    val tabUrl: String? = null,
    val tabId: String? = null,
    val tabTile: String? = null,
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


