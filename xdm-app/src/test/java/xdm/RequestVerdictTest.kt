package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.integration.VideoHelper

/** What the request alone says about a captured response, before the size and URL heuristics. */
class RequestVerdictTest {
    private val clip = "https://cdn.example.com/v/clip.mp4"

    @Test
    fun mediaElementVideoIsWholeFile() {
        assertEquals(true, VideoHelper.requestVerdict(clip, "media", "video/mp4"), "typed video")
        assertEquals(true, VideoHelper.requestVerdict(clip, "MEDIA", null), "untyped, video path")
        assertEquals(true, VideoHelper.requestVerdict("https://cdn.example.com/stream", "media", "application/octet-stream"),
            "octet-stream without a path extension")
    }

    @Test
    fun segmentTypeIsFragmentWhoeverAsked() {
        assertEquals(false, VideoHelper.requestVerdict(clip, "xmlhttprequest", "video/iso.segment"), "XHR")
        assertEquals(false, VideoHelper.requestVerdict(clip, "media", "audio/iso.segment"), "media element")
        assertEquals(false, VideoHelper.requestVerdict(clip, null, "video/iso.segment"), "old extension")
    }

    @Test
    fun scriptFetchesAndOldExtensionsAreUndecided() {
        assertNull(VideoHelper.requestVerdict(clip, "xmlhttprequest", "video/mp4"), "MediaSource feed")
        assertNull(VideoHelper.requestVerdict(clip, "other", "video/mp4"), "fetch in Firefox")
        assertNull(VideoHelper.requestVerdict(clip, null, "video/mp4"), "no type sent")
    }

    @Test
    fun mediaElementAudioKeepsSizeFloor() {
        assertNull(VideoHelper.requestVerdict("https://example.com/ding.mp3", "media", "audio/mpeg"), "typed audio")
        assertNull(VideoHelper.requestVerdict("https://example.com/ding.mp3", "media", null), "untyped audio path")
        assertNull(VideoHelper.requestVerdict("https://example.com/beep.OGA?v=2", "media", null), "extension case ignored")
    }
}
