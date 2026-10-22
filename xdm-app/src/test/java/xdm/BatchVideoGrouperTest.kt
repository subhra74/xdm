package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.media.Mp4InitProbe
import xdm.integration.BatchVideoGrouper
import xdm.integration.ExtensionMessage
import xdm.integration.MetaCdnRule
import xdm.integration.SplitTrackDetector
import java.util.Base64

/** The batch dialog's view of split-track videos among its links (made-up hosts and ids). */
class BatchVideoGrouperTest {

    private fun efg(tag: String, bitrate: Long, asset: Long) = Base64.getUrlEncoder()
        .encodeToString("""{"vencode_tag":"$tag","xpv_asset_id":$asset,"bitrate":$bitrate}""".toByteArray())

    private fun meta(file: String, tag: String, bitrate: Long, start: Long = 0, asset: Long = 7) =
        "https://a.fbcdn.net/v/$file.mp4?efg=${efg(tag, bitrate, asset)}&oe=1&bytestart=$start&byteend=${start + 9}"

    private fun bili(n: String) = "https://cdn.example/upgcxcode/01/02/1001/1001-1-$n.m4s?deadline=1"

    private fun head(handler: String, height: Int, size: Long) =
        SplitTrackDetector.Probe(Mp4InitProbe.Info(handler, if (handler == "vide") "vp09" else "mp4a", 0, height, 10.0, null, 0), size)

    @Test
    fun chunksCollapse_tracksGroupByVideo_plainLinksAndAdsSeparate() {
        val urls = listOf(
            "https://site.example/photo.jpg",
            meta("v", "dash_vp9_q90", 2_000_000, 0),
            meta("v", "dash_vp9_q90", 2_000_000, 10),
            meta("a", "dash_ln_heaac_vbr3_audio", 64_000, 0),
            meta("a", "dash_ln_heaac_vbr3_audio", 64_000, 10),
            meta("ad", "ig-xpvds.ad.igwww-C3.dash_vp9-basic-gen2_1080p", 900_000, asset = 8),
            bili("30033"), bili("30216"),
            "https://site.example/notes.pdf",
        )
        val g = BatchVideoGrouper.group(urls, List(urls.size) { -1 })
        assertEquals(listOf(0, 8), g.plain, "ordinary links keep their places")
        assertEquals(listOf("meta:7", "bili:1001"), g.videos.map { it.assetKey }, "two videos, the ad dropped")
        assertEquals(2, g.videos[0].trackKeys.size, "four chunk links are two files")
        assertFalse(g.videos[0].trackUrls().any { it.contains("bytestart") }, "whole-file URLs")
    }

    @Test
    fun rankedTracks_onlyTheBestIsProbed_nextOneAfterAFailure() {
        val urls = listOf(
            meta("q30", "dash_vp9_q30", 500_000), meta("q90", "dash_vp9_q90", 4_000_000),
            meta("q50", "dash_vp9_q50", 1_000_000), meta("a", "dash_ln_heaac_vbr3_audio", 64_000),
        )
        val video = BatchVideoGrouper.group(urls, List(4) { -1 }).videos.single()
        val first = video.nextProbes()
        assertEquals(setOf("/v/q90.mp4", "/v/a.mp4"), first.map { it.url.trackKey }.toSet(), "top video and the audio")
        assertTrue(video.nextProbes().isEmpty(), "nothing more while those run")
        video.probed(first.first { !it.url.audio }, null)
        assertEquals(listOf("/v/q50.mp4"), video.nextProbes().map { it.url.trackKey }, "q90 failed: q50 next")
    }

    @Test
    fun pick_needsBothTracks() {
        val video = BatchVideoGrouper.group(listOf(bili("30033"), bili("30011")), listOf(-1, -1)).videos.single()
        val probes = video.nextProbes()
        assertEquals(2, probes.size, "unranked: every file probed")
        probes.forEach { video.probed(it, head("vide", if (it.url.trackKey.contains("30033")) 480 else 360, 1_000)) }
        val pick = video.pick()
        assertFalse(pick.complete, "no audio yet")
        assertTrue(pick.video!!.url.contains("30033"), "taller video")
        assertNull(pick.audio, "no audio")
    }

    @Test
    fun detectorLookup_fillsTheMissingAudio() {
        val detector = SplitTrackDetector(object : SplitTrackDetector.Env {
            override fun execute(task: () -> Unit) = task()
            override fun probe(url: String, msg: ExtensionMessage) =
                if (url.contains("/v/a.mp4")) head("soun", 0, 300_000) else head("vide", 1080, 9_000_000)
            override fun offer(offer: SplitTrackDetector.Offer) {}
        })
        val video = meta("v", "dash_vp9_q90", 2_000_000)
        val audio = meta("a", "dash_ln_heaac_vbr3_audio", 64_000)
        listOf(video, audio).forEach { detector.onMediaMessage(ExtensionMessage(url = it, tabId = "3"), { 1L }) }

        // The page's list only had the video track.
        val batch = BatchVideoGrouper.group(listOf(video), listOf(-1)).videos.single()
        val known = detector.lookup(batch.trackKeys)
        assertEquals(setOf("meta:7"), known.keys, "found by the video's key")
        known.getValue("meta:7").forEach { batch.addKnown(it.url, it.probe) }
        assertTrue(batch.nextProbes().isEmpty(), "both tracks already probed by the detector")
        val pick = batch.pick()
        assertTrue(pick.complete, "audio added")
        assertEquals(MetaCdnRule.match(audio)!!.url, pick.audio!!.url, "the detector's audio track")
        assertEquals(9_300_000L, pick.size, "size from the detector's probes")
    }
}
