package xdm.manager.torrent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.manager.EngineConfig

/** The BitTorrent page's options as libtorrent settings. */
class TorrentSettingsTest {
    @Test
    fun `options map to libtorrent settings and a fixed port rebinds the listen sockets`() {
        val on = TorrentSettings.options(true, true, true, EngineConfig.TORRENT_ENCRYPTION_PREFER, 0)
        assertEquals("true", on["enable_upnp"], "UPnP")
        assertEquals("true", on["enable_natpmp"], "NAT-PMP follows UPnP")
        assertEquals("1", on["in_enc_policy"], "pe_enabled")
        assertFalse("listen_interfaces" in on, "random port: the saved one is kept")

        val off = TorrentSettings.options(false, false, false, EngineConfig.TORRENT_ENCRYPTION_REQUIRE, 6881)
        assertEquals("false", off["enable_dht"], "DHT off")
        assertEquals("false", off["enable_lsd"], "LSD off")
        assertEquals("0", off["out_enc_policy"], "pe_forced")
        assertEquals("0.0.0.0:6881,[::]:6881", off["listen_interfaces"], "fixed port")
        assertEquals("2", TorrentSettings.options(true, true, true, EngineConfig.TORRENT_ENCRYPTION_OFF, 0)["in_enc_policy"], "pe_disabled")
    }

    @Test
    fun `session settings carry the options and a fixed port wins`() {
        val s = TorrentSettings.session(null, 1024, 2048, null,
            TorrentSettings.options(true, false, true, 0, 7000))
        assertTrue("enable_dht=false" in s, "DHT off in the session")
        assertTrue("listen_interfaces=0.0.0.0:7000,[::]:7000" in s, "fixed port")
        assertTrue("upload_rate_limit=2048" in s, "upload limit")
    }
}
