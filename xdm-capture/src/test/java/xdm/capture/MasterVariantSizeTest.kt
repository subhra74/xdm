package xdm.capture

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A captured master gets its sizes from the media playlist the player loads next, which is not
 * listed again on its own.
 */
class MasterVariantSizeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var tracker: CapturedVideoTracker

    @BeforeEach
    fun setup() {
        tracker = CapturedVideoTracker()
        FakeCaptureHost.install(tracker)
    }

    private fun message(url: String, tab: String) = json.decodeFromString<ExtensionMessage>(
        """
        {"url":"$url","tabId":"$tab","tabUrl":"https://site.example/watch","method":"GET",
         "responseHeaders":{"content-type":["application/vnd.apple.mpegurl"]}}
        """
    )

    private fun master(base: String) = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,AVERAGE-BANDWIDTH=1600000,RESOLUTION=1280x720
        $base/720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        $base/360.m3u8
        """.trimIndent()

    private val vod = """
        #EXTM3U
        #EXTINF:300,
        a.ts
        #EXTINF:300,
        b.ts
        #EXT-X-ENDLIST
        """.trimIndent()

    /** The media executor is asynchronous: wait for the list to settle. */
    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun sizes() = tracker.videoList.associate { it.id to tracker.getDisplayInfo(it.id)?.streamSize }

    @Test
    fun variantLoadedByThePlayer_sizesEveryEntry_andIsNotListedTwice() {
        val base = "https://cdn.example/a1"
        VideoHelper.processMediaMessage(message("$base/master.m3u8", "31"), master(base))
        waitFor("master entries") { tracker.videoList.size == 2 }
        assertEquals(listOf(null, null), sizes().values.toList(), "no duration yet")

        VideoHelper.processMediaMessage(message("$base/720.m3u8", "31"), vod)
        waitFor("sizes") { sizes().values.all { it != null } }
        assertEquals(2, tracker.videoList.size, "the variant is not a capture of its own")
        val byHeight = tracker.videoList.associate { tracker.getDisplayInfo(it.id)!!.height to tracker.getDisplayInfo(it.id)!! }
        assertEquals(120_000_000L, byHeight[720]?.streamSize?.bytes, "average bitrate × 600 s")
        assertEquals(StreamSize.Kind.AVERAGE, byHeight[720]?.streamSize?.kind, "≈")
        assertEquals(60_000_000L, byHeight[360]?.streamSize?.bytes, "shared duration × its own bitrate")
        assertEquals(StreamSize.Kind.PEAK, byHeight[360]?.streamSize?.kind, "~")
        assertEquals(600L, byHeight[360]?.duration, "duration filled in")
    }

    @Test
    fun liveVariant_marksTheMasterLive() {
        val base = "https://cdn.example/b2"
        VideoHelper.processMediaMessage(message("$base/master.m3u8", "32"), master(base))
        waitFor("master entries") { tracker.videoList.size == 2 }
        VideoHelper.processMediaMessage(message("$base/360.m3u8", "32"), "#EXTM3U\n#EXTINF:4,\nx.ts\n")
        waitFor("live") { sizes().values.all { it?.kind == StreamSize.Kind.LIVE } }
        assertNull(tracker.getDisplayInfo(tracker.videoList[0].id)?.duration, "no duration while live")
    }

    @Test
    fun unrelatedMediaPlaylist_stillListedOnItsOwn() {
        val base = "https://cdn.example/c3"
        VideoHelper.processMediaMessage(message("$base/master.m3u8", "33"), master(base))
        waitFor("master entries") { tracker.videoList.size == 2 }
        VideoHelper.processMediaMessage(message("https://other.example/x/index.m3u8", "33"), vod)
        waitFor("own entry") { tracker.videoList.size == 3 }
    }
}
