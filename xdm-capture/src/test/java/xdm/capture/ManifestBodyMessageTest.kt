package xdm.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A `/media` message that carries the playlist text the page's player received (`manifestBody`):
 * the master is parsed from it, without a fetch, so a playlist link that works only once still gives
 * every resolution. Shaped after a real capture: variant links with a `#cell=` fragment, separate
 * audio groups, and media playlists served as `video/mp4`. The URLs do not resolve, so anything
 * that went to the network instead would leave the list empty.
 */
class ManifestBodyMessageTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var tracker: CapturedVideoTracker

    @BeforeEach
    fun setup() {
        tracker = CapturedVideoTracker()
        FakeCaptureHost.install(tracker)
    }

    private val base = "https://vod.invalid/sec(token)/video/fmp4/1"

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac_q1",NAME="(original)",AUTOSELECT=YES,DEFAULT=YES,URI="$base/aac_q1/manifest.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac_q2",NAME="(original)",AUTOSELECT=YES,DEFAULT=YES,URI="$base/aac_q2/manifest.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=2149280,CODECS="mp4a.40.2,avc1.64001f",RESOLUTION=1280x592,NAME="720",AUDIO="aac_q2"
        $base/h264_hd/manifest.m3u8#cell=cf3
        #EXT-X-STREAM-INF:BANDWIDTH=460560,CODECS="mp4a.40.2,avc1.42001e",RESOLUTION=512x240,NAME="380",AUDIO="aac_q1"
        $base/h264_sd/manifest.m3u8#cell=cf3
        #EXT-X-STREAM-INF:BANDWIDTH=836280,CODECS="mp4a.40.2,avc1.64001f",RESOLUTION=848x392,NAME="480",AUDIO="aac_q2"
        $base/h264_hq/manifest.m3u8#cell=cf3
        #EXT-X-STREAM-INF:BANDWIDTH=6221600,CODECS="mp4a.40.2,avc1.640028",RESOLUTION=1920x888,NAME="1080",AUDIO="aac_q2"
        $base/h264_fhd/manifest.m3u8#cell=cf3
        """.trimIndent()

    private val vod = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-TARGETDURATION:3
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:3.0,
        0.m4s
        #EXTINF:3.0,
        1.m4s
        #EXT-X-ENDLIST
        """.trimIndent()

    /** What the extension posts, the body as a JSON string. */
    private fun message(url: String, type: String, body: String?) = json.decodeFromString<ExtensionMessage>(
        """
        {"url":"$url","tabId":"41","tabUrl":"https://site.example/episode","method":"GET",
         "responseHeaders":{"content-type":["$type"]}${body?.let { ""","manifestBody":${JsonPrimitive(it)}""" } ?: ""}}
        """
    )

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun heights() = tracker.videoList.mapNotNull { tracker.getDisplayInfo(it.id)?.height }.sorted()

    @Test
    fun masterFromBody_listsEveryResolution_andFoldsInTheVariantsThePlayerLoads() {
        val msg = message("https://director.invalid/manifest/video/x1.m3u8?sec=once", "application/vnd.apple.mpegurl", master)
        assertEquals(master, msg.manifestBody, "decoded from the message")
        VideoHelper.processMediaMessage(msg, msg.manifestBody)
        waitFor("master entries") { tracker.videoList.size == 4 }
        assertEquals(listOf(240, 392, 592, 888), heights(), "every resolution of the master")

        // The player then loads one variant and its audio, both served as video/mp4.
        VideoHelper.processMediaMessage(message("$base/h264_hd/manifest.m3u8#cell=cf3", "video/mp4", vod), vod)
        VideoHelper.processMediaMessage(message("$base/aac_q2/manifest.m3u8", "video/mp4", vod), vod)
        waitFor("durations") { tracker.videoList.all { tracker.getDisplayInfo(it.id)?.duration == 6L } }
        assertEquals(4, tracker.videoList.size, "the variant and the audio are not listed on their own")
        assertTrue(tracker.videoList.all { tracker.getDisplayInfo(it.id)?.streamSize != null }, "sized from the variant")
    }
}
