package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.integration.BilibiliCdnRule
import xdm.integration.MetaCdnRule
import java.util.Base64

/** The URL shapes the rules recognise, on made-up hosts and ids. */
class SplitTrackRulesTest {

    private fun efg(tag: String, asset: Long = 42, bitrate: Long = 4556129): String =
        Base64.getUrlEncoder().encodeToString(
            """{"vencode_tag":"$tag","video_id":null,"xpv_asset_id":$asset,"duration_s":69,"bitrate":$bitrate}""".toByteArray()
        )

    private fun meta(tag: String, range: String = "&bytestart=818&byteend=1017", host: String = "a.fbcdn.net") =
        "https://$host/v/video.mp4?n=1&efg=${efg(tag)}&oh=x&oe=6ADF7F1D$range"

    @Test
    fun meta_video_rangeParamsDropped() {
        val t = MetaCdnRule.match(meta("ig-xpvds.clips.c2-C3.dash_r2evevp9-r1gen2vp9_q90"))!!
        assertFalse(t.audio, "video")
        assertEquals("meta:42", t.assetKey, "asset")
        assertEquals(4556129L, t.rank, "bitrate as rank")
        assertFalse(t.url.contains("bytestart") || t.url.contains("byteend"), "whole file: ${t.url}")
        assertTrue(t.url.endsWith("&oe=6ADF7F1D"), "other params kept: ${t.url}")
        assertFalse(t.ad, "not an ad")
    }

    @Test
    fun meta_sameFileDifferentRanges_sameKey() {
        val a = MetaCdnRule.match(meta("dash_ln_heaac_vbr3_audio", "&bytestart=0&byteend=823"))!!
        val b = MetaCdnRule.match(meta("dash_ln_heaac_vbr3_audio", "&bytestart=824&byteend=1275"))!!
        assertTrue(a.audio, "audio")
        assertEquals(a.trackKey, b.trackKey, "one file")
        assertEquals(a.url, b.url, "one canonical URL")
    }

    @Test
    fun meta_adsFlagged() {
        assertTrue(MetaCdnRule.match(meta("ig-xpvds.ad.igwww-C3.dash_vp9-basic-gen2_1080p"))!!.ad, "Instagram ad")
        assertTrue(MetaCdnRule.match(meta("dash_r2av1-r1gen2vp9-ads_q90", host = "b.fbcdn.net"))!!.ad, "Facebook ad")
    }

    @Test
    fun meta_notMatched() {
        assertNull(MetaCdnRule.match(meta("dash_ln_heaac_vbr3_audio", range = "")), "no range params: not a player chunk")
        assertNull(MetaCdnRule.match(meta("xpv_progressive.FACEBOOK..C3.720.progressive_h264")), "not DASH")
        assertNull(MetaCdnRule.match(meta("dash_ln_heaac_vbr3_audio", host = "cdn.example.com")), "other host")
        assertNull(MetaCdnRule.match("https://a.fbcdn.net/v/p.jpg?bytestart=0&byteend=9"), "no efg")
        assertNull(MetaCdnRule.match("https://a.fbcdn.net/v/a.mp4?efg=%%%&bytestart=0"), "bad efg")
    }

    private val bili = "https://cdn.example/upgcxcode/01/02/1001/1001-1-%s.m4s?deadline=1&sig=x"

    @Test
    fun bilibili_videoAndAudioShareCid() {
        val v = BilibiliCdnRule.match(bili.format("30011"))!!
        val a = BilibiliCdnRule.match(bili.format("30216"))!!
        assertFalse(v.audio, "30011 is video")
        assertTrue(a.audio, "302xx is audio")
        assertEquals("bili:1001", v.assetKey, "asset from cid")
        assertEquals(v.assetKey, a.assetKey, "paired by cid")
        assertFalse(BilibiliCdnRule.match(bili.format("100023"))!!.audio, "AV1 suffix is video")
    }

    @Test
    fun bilibili_notMatched() {
        assertNull(BilibiliCdnRule.match("https://h/upgcxcode/02/48/411/412-1-30011.m4s"), "cid mismatch")
        assertNull(BilibiliCdnRule.match("https://h/other/1001-1-30011.m4s"), "other path")
    }
}
