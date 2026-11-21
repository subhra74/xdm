package xdm.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaylistTextCacheTest {
    @Test
    fun lookup_byTabAndUrl_ignoresTheFragment() {
        val cache = PlaylistTextCache()
        cache.put("1", "https://cdn.example/v/manifest.m3u8?sig=a", "#EXTM3U\nv")
        val found = cache.get("1", listOf("https://cdn.example/v/manifest.m3u8?sig=a#cell=cf3", "https://cdn.example/other.m3u8"))
        assertEquals(listOf("https://cdn.example/v/manifest.m3u8?sig=a#cell=cf3"), found.keys.toList(), "only the known one, keyed as asked")
        assertEquals("#EXTM3U\nv", String(found.values.first()), "the text")
        assertTrue(cache.get("2", listOf("https://cdn.example/v/manifest.m3u8?sig=a")).isEmpty(), "another tab's capture")
    }

    @Test
    fun bounded_byCountAndBytes_leastRecentlyUsedFirst() {
        val cache = PlaylistTextCache(maxEntries = 2, maxBytes = 10)
        cache.put("1", "https://a.example/1", "aaaa")
        cache.put("1", "https://a.example/2", "bbbb")
        cache.get("1", listOf("https://a.example/1"))
        cache.put("1", "https://a.example/3", "cc")
        assertEquals(
            setOf("https://a.example/1", "https://a.example/3"),
            cache.get("1", listOf("https://a.example/1", "https://a.example/2", "https://a.example/3")).keys,
            "the least recently used one went",
        )
        cache.put("1", "https://a.example/4", "dddddddd")
        // 4 + 2 + 8 bytes is over 10: the least recently used (1) goes, 2 + 8 fits.
        assertEquals(
            setOf("https://a.example/3", "https://a.example/4"),
            cache.get("1", listOf("https://a.example/1", "https://a.example/3", "https://a.example/4")).keys,
            "older ones dropped to stay under the byte cap",
        )
        cache.put("1", "https://a.example/5", "x".repeat(11))
        assertTrue(cache.get("1", listOf("https://a.example/5")).isEmpty(), "larger than the whole cache: not kept")
    }
}
