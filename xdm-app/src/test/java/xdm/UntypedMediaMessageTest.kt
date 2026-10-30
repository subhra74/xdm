package xdm

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.ICapturedVideoTracker
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.integration.ExtensionMessage
import xdm.integration.StreamingVideoDisplayInfo
import xdm.integration.VideoHelper
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

/** A media message with no Content-Type, as the extension sends it, reaches the video list. */
class UntypedMediaMessageTest {
    private lateinit var dir: File
    private val captured = mutableListOf<Pair<HttpDownloadTaskInfo, StreamingVideoDisplayInfo>>()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        I8N.loadTexts("en")
        dir = Files.createTempDirectory("xdm-untyped").toFile()
        AppContext.config = AppConfig(dir.absolutePath)
        AppContext.videoTracker = Proxy.newProxyInstance(
            ICapturedVideoTracker::class.java.classLoader, arrayOf(ICapturedVideoTracker::class.java)
        ) { _, method, args ->
            @Suppress("UNCHECKED_CAST")
            if (method.name == "addVideoHttp") captured += args[0] as List<Pair<HttpDownloadTaskInfo, StreamingVideoDisplayInfo>>
            null
        } as ICapturedVideoTracker
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val url = "https://d9jmtjs5r4cgq.cloudfront.net/Recordings%2Fmds%2FMaster%20of%20Data%20Science%20(Global)" +
        "%20-%20Jun%2026%2F20261004%2F1521927%2Fe8fe00cd-27fd-4cf0-87ad-8cc44527d593.mp4"

    /** An S3 object served without a Content-Type, answering a player's Range request. */
    private fun rangeMessage(range: String, length: Long, total: Long, url: String = this.url) = json.decodeFromString<ExtensionMessage>(
        """
        {"url":"$url","tabId":"237767163","method":"GET",
         "requestHeaders":{"Range":["bytes=$range"],"Referer":["https://olympus.example.com/"]},
         "responseHeaders":{"accept-ranges":["bytes"],"content-length":["$length"],
           "content-range":["bytes $range/$total"],"server":["AmazonS3"]}}
        """
    )

    @Test
    fun rangeResponseWithoutContentType_isCaptured_withTheWholeFileSize() {
        VideoHelper.processMediaMessage(rangeMessage("498794496-599306002", 100511507, 599306003))
        assertEquals(1, captured.size, "captured once")
        val (task, display) = captured[0]
        assertEquals(599306003L, task.knownFileSize, "file size from Content-Range")
        assertEquals(599306003L, display.size, "listed size from Content-Range")
    }

    @Test
    fun smallTailRangeOfALargeFile_passesTheMinimumSize() {
        VideoHelper.processMediaMessage(rangeMessage("599305003-599306002", 1000, 599306003))
        assertEquals(1, captured.size, "a 1000-byte range of a 572 MB file is still captured")
    }

    /** `[` `]` in the file name: Chrome sends them as is, and java.net.URI used to throw on them here. */
    @Test
    fun bracketsInTheFileName_areCaptured() {
        val url = "https://21.static.example.com/80cf/Show.S01E06.540p.[Group.Com].mp4?download_token=d754"
        VideoHelper.processMediaMessage(rangeMessage("0-999", 1000, 102818495, url))
        assertEquals(1, captured.size, "captured once")
        assertEquals(url, captured[0].first.url, "URL kept as sent")
    }
}
