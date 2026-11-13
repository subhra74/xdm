package xdm.manager.torrent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Extra trackers go around the info dictionary, whose bytes (and so the info-hash) stay the same. */
class TorrentTrackersTest {
    // Unsorted keys inside info on purpose: re-encoding it would change the bytes.
    private val info = "d4:name4:pack6:lengthi5e12:piece lengthi16384e6:pieces0:e"

    @Test
    fun `settings text becomes tiers`() {
        assertEquals(listOf(listOf("udp://a:1", "udp://b:2"), listOf("https://c/announce")),
            TorrentTrackers.tiers("udp://a:1\n udp://b:2 \n\nhttps://c/announce\nnot a url\n"), "two tiers")
    }

    @Test
    fun `trackers merge after the torrent's own and the info dictionary keeps its bytes`() {
        val torrent = "d8:announce9:udp://a:113:announce-listll9:udp://a:1ee4:info${info}e".toByteArray()
        val out = String(TorrentTrackers.addToTorrent(torrent, listOf(listOf("udp://a:1", "udp://b:2")))!!)
        assertTrue(out.contains("4:info$info"), "info untouched: $out")
        assertTrue(out.contains("13:announce-listll9:udp://a:1el9:udp://b:2ee"), "own tier, then the new one without duplicates: $out")
        assertTrue(out.indexOf("8:announce") < out.indexOf("13:announce-list") && out.indexOf("13:announce-list") < out.indexOf("4:info"),
            "keys sorted")
    }

    @Test
    fun `a torrent without trackers gets them, and nothing new leaves it as it was`() {
        val bare = "d4:info${info}e".toByteArray()
        val out = String(TorrentTrackers.addToTorrent(bare, listOf(listOf("udp://x:9")))!!)
        assertTrue(out.startsWith("d8:announce9:udp://x:913:announce-listll9:udp://x:9ee4:info"), out)
        val same = "d8:announce9:udp://x:94:info${info}e".toByteArray()
        assertSame(same, TorrentTrackers.addToTorrent(same, listOf(listOf("udp://x:9"))), "already there")
        assertNull(TorrentTrackers.addToTorrent("not bencode".toByteArray(), listOf(listOf("udp://x:9"))), "not a torrent")
    }

    @Test
    fun `magnet links get tr parameters they do not have`() {
        val m = "magnet:?xt=urn:btih:abc&tr=udp%3A%2F%2Fa%3A1"
        assertEquals("$m&tr=udp%3A%2F%2Fb%3A2", TorrentTrackers.addToMagnet(m, listOf(listOf("udp://a:1", "udp://b:2"))), "only b added")
    }
}
