package xdm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.integration.VideoHelper

/** Media responses that arrive without a Content-Type: taken by path extension, size or manifest URL. */
class UntypedMediaTest {
    private val min = 1024L * 1024

    @Test
    fun mediaExtension_atLeastMinSize_isTaken() {
        assertTrue(VideoHelper.isUntypedMedia("https://cdn.example.com/v/clip.mp4?sig=x", min, min), "exactly the minimum")
        assertTrue(VideoHelper.isUntypedMedia("https://cdn.example.com/a/song.MP3", min * 20, min), "extension case ignored")
    }

    @Test
    fun mediaExtension_belowMinSize_isSkipped() {
        assertFalse(VideoHelper.isUntypedMedia("https://cdn.example.com/v/clip.mp4", min - 1, min), "one byte short")
    }

    @Test
    fun mediaExtension_withUnknownSize_isSkipped() {
        assertFalse(VideoHelper.isUntypedMedia("https://cdn.example.com/v/clip.mp4", null, min), "no Content-Length")
    }

    @Test
    fun otherExtension_isSkipped_whateverItsSize() {
        assertFalse(VideoHelper.isUntypedMedia("https://cdn.example.com/app.js", min * 10, min), "script")
        assertFalse(VideoHelper.isUntypedMedia("https://cdn.example.com/stream?id=clip.mp4", min * 10, min), "extension only in the query")
    }

    @Test
    fun manifestUrl_isTaken_withoutSize() {
        assertTrue(VideoHelper.isUntypedMedia("https://cdn.example.com/hls/master.m3u8", null, min), "HLS")
        assertTrue(VideoHelper.isUntypedMedia("https://cdn.example.com/dash/manifest.mpd", 900, min), "DASH")
    }
}
