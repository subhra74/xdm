package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.media.Mp4InitProbe
import xdm.integration.ExtensionMessage
import xdm.integration.SplitTrackDetector
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/** Pairing, dedupe and upgrades in [SplitTrackDetector], with probes answered from a table. */
class SplitTrackDetectorTest {

    private class FakeEnv : SplitTrackDetector.Env {
        val heads = HashMap<String, SplitTrackDetector.Probe>()
        val probed = ArrayList<String>()
        val offers = ArrayList<SplitTrackDetector.Offer>()

        override fun execute(task: () -> Unit) = task()
        override fun probe(url: String, msg: ExtensionMessage): SplitTrackDetector.Probe? {
            probed.add(url)
            return heads[url]
        }

        override fun offer(offer: SplitTrackDetector.Offer) {
            offers.add(offer)
        }
    }

    private val ids = AtomicLong(100)
    private val env = FakeEnv()
    private val detector = SplitTrackDetector(env)

    private fun head(handler: String, codec: String, height: Int, size: Long, seconds: Double = 60.0) =
        SplitTrackDetector.Probe(Mp4InitProbe.Info(handler, codec, height * 16 / 9, height, seconds, null, 0), size)

    private fun send(url: String, tab: String = "7"): Boolean =
        detector.onMediaMessage(
            ExtensionMessage(
                url = url, tabId = tab, tabUrl = "https://example/page", tabTitle = "Clip",
                requestHeaders = mutableMapOf(),
            ),
            ids::incrementAndGet,
        )

    // ---- Meta ----

    private fun efg(tag: String, bitrate: Long, asset: Long = 42) = Base64.getUrlEncoder()
        .encodeToString("""{"vencode_tag":"$tag","xpv_asset_id":$asset,"bitrate":$bitrate}""".toByteArray())

    private fun meta(file: String, tag: String, bitrate: Long, start: Long = 0, asset: Long = 42) =
        "https://a.fbcdn.net/v/$file.mp4?efg=${efg(tag, bitrate, asset)}&oe=1&bytestart=$start&byteend=${start + 99}"

    private fun whole(url: String) = url.substringBefore("&bytestart")

    @Test
    fun meta_audioAndVideo_offeredOnceAsOnePair() {
        val audio = meta("a", "dash_ln_heaac_vbr3_audio", 64000)
        val video = meta("v", "dash_vp9_q90", 2_000_000)
        env.heads[whole(audio)] = head("soun", "mp4a", 0, 500_000)
        env.heads[whole(video)] = head("vide", "vp09", 1080, 15_000_000)
        assertTrue(send(audio), "claimed")
        assertTrue(env.offers.isEmpty(), "no pair yet")
        send(video)
        // The player keeps reading both files in pieces.
        for (start in listOf(100L, 200L, 300L)) {
            send(meta("a", "dash_ln_heaac_vbr3_audio", 64000, start))
            send(meta("v", "dash_vp9_q90", 2_000_000, start))
        }
        assertEquals(2, env.probed.size, "each file probed once")
        assertEquals(1, env.offers.size, "one capture")
        assertEquals(whole(video), env.offers[0].video.url, "video track")
        assertEquals(whole(audio), env.offers[0].audio.url, "audio track")
    }

    @Test
    fun meta_lowerRankedLadder_notProbed_higherReplacesSameId() {
        val audio = meta("a", "dash_ln_heaac_vbr3_audio", 64000)
        val q50 = meta("q50", "dash_vp9_q50", 1_000_000)
        val q30 = meta("q30", "dash_vp9_q30", 500_000)
        val q90 = meta("q90", "dash_vp9_q90", 4_000_000)
        env.heads[whole(audio)] = head("soun", "mp4a", 0, 500_000)
        env.heads[whole(q50)] = head("vide", "vp09", 720, 5_000_000)
        env.heads[whole(q90)] = head("vide", "vp09", 1080, 20_000_000)
        send(audio); send(q50); send(q30)
        assertFalse(env.probed.contains(whole(q30)), "lower rank than one already probed")
        send(q90)
        assertEquals(2, env.offers.size, "first pair, then the upgrade")
        assertEquals(env.offers[0].id, env.offers[1].id, "upgrade replaces the same capture")
        assertEquals(whole(q90), env.offers[1].video.url, "best video now")
    }

    @Test
    fun meta_failedProbe_nextBestSkippedTrackTried() {
        val audio = meta("a", "dash_ln_heaac_vbr3_audio", 64000)
        val q90 = meta("q90", "dash_vp9_q90", 4_000_000)
        val q50 = meta("q50", "dash_vp9_q50", 1_000_000)
        env.heads[whole(audio)] = head("soun", "mp4a", 0, 500_000)
        env.heads[whole(q50)] = head("vide", "vp09", 720, 5_000_000)
        send(audio); send(q90); send(q50)
        assertTrue(env.probed.contains(whole(q50)), "q90 failed, so q50 is tried")
        assertEquals(whole(q50), env.offers.single().video.url, "offered with what worked")
    }

    @Test
    fun meta_ads_claimedButNeverProbed() {
        assertTrue(send(meta("ad", "ig-xpvds.ad.igwww-C3.dash_vp9-basic-gen2_1080p", 900_000)), "claimed")
        assertTrue(env.probed.isEmpty(), "not probed")
    }

    @Test
    fun otherUrls_notClaimed() {
        assertFalse(send("https://cdn.example.com/video.mp4"), "left to the usual handling")
    }

    @Test
    fun tabNavigation_forgetsTracks() {
        val audio = meta("a", "dash_ln_heaac_vbr3_audio", 64000)
        env.heads[whole(audio)] = head("soun", "mp4a", 0, 500_000)
        send(audio)
        detector.onTabNavigated("7")
        send(audio)
        assertEquals(2, env.probed.size, "seen again after the tab navigated")
    }

    @Test
    fun sameAssetInTwoTabs_keptApart() {
        val audio = meta("a", "dash_ln_heaac_vbr3_audio", 64000)
        val video = meta("v", "dash_vp9_q90", 2_000_000)
        env.heads[whole(audio)] = head("soun", "mp4a", 0, 500_000)
        env.heads[whole(video)] = head("vide", "vp09", 1080, 15_000_000)
        send(audio, "1"); send(video, "1"); send(audio, "2"); send(video, "2")
        assertEquals(2, env.offers.size, "one capture per tab")
        assertTrue(env.offers[0].id != env.offers[1].id, "separate captures")
    }

    // ---- bilibili ----

    private fun bili(n: String) =
        "https://cdn.example/upgcxcode/01/02/1001/1001-1-$n.m4s?deadline=1"

    @Test
    fun bilibili_pairedByCid_tallerVideoWins() {
        env.heads[bili("30216")] = head("soun", "mp4a", 0, 668_026)
        env.heads[bili("30011")] = head("vide", "hvc1", 360, 2_599_813)
        env.heads[bili("30033")] = head("vide", "hvc1", 480, 3_558_806)

        send(bili("30011"))
        send(bili("30216"))
        send(bili("30033"))

        assertEquals(3, env.probed.size, "no rank in the URL: every file probed")
        val last = env.offers.last()
        assertEquals(bili("30033"), last.video.url, "480p over 360p")
        assertEquals(bili("30216"), last.audio.url, "audio paired by cid")
        assertTrue(env.offers.all { it.id == last.id }, "the upgrade replaces the same capture")
    }

    @Test
    fun bilibili_sameHeight_moreCompatibleCodecWins() {
        env.heads[bili("30216")] = head("soun", "mp4a", 0, 600_000)
        env.heads[bili("100023")] = head("vide", "av01", 480, 3_000_000)
        env.heads[bili("30033")] = head("vide", "hvc1", 480, 3_500_000)
        send(bili("30216")); send(bili("100023")); send(bili("30033"))
        assertEquals(bili("30033"), env.offers.last().video.url, "HEVC over AV1 at the same height")
    }
}
