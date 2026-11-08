package xdm.capture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The extension's isMatchingRequest, as the Android browser applies it. */
class MediaFilterTest {
    private val config = FakeCaptureConfig()

    @Test
    fun mediaByExtensionOrType() {
        assertTrue(MediaFilter.isMedia("https://cdn.example.com/a/master.M3U8?t=1", 200, "text/plain", config), "extension, any case")
        assertTrue(MediaFilter.isMedia("https://cdn.example.com/stream", 206, "Video/MP4", config), "type, any case")
        assertTrue(MediaFilter.isMedia("https://cdn.example.com/m", 200, "application/dash+xml", config), "dash")
        assertTrue(MediaFilter.isMedia("https://cdn.example.com/p", 200, "application/vnd.apple.mpegurl", config), "mpegurl")
    }

    @Test
    fun notMedia() {
        assertFalse(MediaFilter.isMedia("https://cdn.example.com/seg.bin", 200, "application/octet-stream", config), "octet-stream alone")
        assertFalse(MediaFilter.isMedia("https://cdn.example.com/clip.mp4", 403, "video/mp4", config), "failed response")
        assertFalse(MediaFilter.isMedia("https://dl.update.microsoft.com/x.mp4", 200, "video/mp4", config), "blocked host")
        assertFalse(MediaFilter.isMedia("https://cdn.example.com/api?f=clip.mp4", 200, "application/json", config), "extension only in the query")
    }

    @Test
    fun subtitlesByExtensionOrType() {
        assertTrue(MediaFilter.isMedia("https://cdn.example.com/s/en.vtt?t=1", 200, "text/plain", config), "vtt extension")
        assertTrue(MediaFilter.isMedia("https://cdn.example.com/s/1", 200, "text/vtt; charset=utf-8", config), "vtt type")
        assertTrue(MediaFilter.isMedia("https://cdn.example.com/s/1", 200, "application/ttml+xml", config), "ttml type")
        assertTrue(MediaFilter.isMedia("https://cdn.example.com/s/ep1.SRT", 200, null, config), "srt extension, any case")
        assertFalse(MediaFilter.isMedia("https://cdn.example.com/insert", 200, "text/html", config), "srt only as a path's last letters")
        assertFalse(MediaFilter.isSubtitle("https://cdn.example.com/api?f=en.vtt", "application/json"), "extension only in the query")
    }

    @Test
    fun subtitlesOff() {
        val off = FakeCaptureConfig().apply { detectSubtitles = false }
        assertFalse(MediaFilter.isMedia("https://cdn.example.com/s/en.vtt", 200, "text/vtt", off))
    }
}
