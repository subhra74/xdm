package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.torrent.DuplicateAction
import xdm.app.torrent.TorrentClients
import xdm.app.torrent.TorrentLaunch
import xdm.manager.RecordStatus

/** Magnet links and .torrent files the OS opens XDM with. */
class TorrentLaunchTest {
    private val exists: (String) -> Boolean = { it == "/t/a.torrent" }

    @Test
    fun `the first magnet link or existing torrent file is taken`() {
        assertEquals("magnet:?xt=urn:btih:abc", TorrentLaunch.sourceIn(listOf("--minimized", "magnet:?xt=urn:btih:abc"), exists), "magnet")
        assertEquals("/t/a.torrent", TorrentLaunch.sourceIn(listOf("/t/a.torrent"), exists), "path")
        assertEquals("/t/a.torrent", TorrentLaunch.sourceIn(listOf("file:///t/a.torrent"), exists), "file URI")
        assertNull(TorrentLaunch.sourceIn(listOf("/t/missing.torrent", "xdm-app://show", "--minimized"), exists), "nothing")
    }

    @Test
    fun `a captured torrent file is known by name or type`() {
        assertTrue(TorrentLaunch.isTorrentFile("ubuntu.iso.torrent", null), "name")
        assertTrue(TorrentLaunch.isTorrentFile("download", "application/x-bittorrent; charset=binary"), "type")
        assertFalse(TorrentLaunch.isTorrentFile("ubuntu.iso", "application/octet-stream"), "other")
    }

    @Test
    fun `a torrent already in the list offers show, resume, seed or download again`() {
        assertEquals(DuplicateAction.SHOW, DuplicateAction.of(RecordStatus.DOWNLOADING, false, true), "running")
        assertEquals(DuplicateAction.SHOW, DuplicateAction.of(RecordStatus.READY, false, true), "queued")
        assertEquals(DuplicateAction.SHOW, DuplicateAction.of(RecordStatus.FINISHED, true, true), "seeding")
        assertEquals(DuplicateAction.RESUME, DuplicateAction.of(RecordStatus.PAUSED, false, true), "paused")
        assertEquals(DuplicateAction.RESUME, DuplicateAction.of(RecordStatus.ERROR, false, false), "failed")
        assertEquals(DuplicateAction.SEED, DuplicateAction.of(RecordStatus.FINISHED, false, true), "finished, files there")
        assertEquals(DuplicateAction.DOWNLOAD_AGAIN, DuplicateAction.of(RecordStatus.FINISHED, false, false), "finished, files gone")
    }

    @Test
    fun `client identities use libtorrent's peer id format and default to libtorrent`() {
        assertEquals("-XD9090-", TorrentClients.prefix("XD", "9.0.9"), "one character per number")
        assertEquals("-qB5A30-", TorrentClients.prefix("qB", "5.10.3"), "10 is A")
        val lt = TorrentClients.byId("libtorrent", "9.0.9")
        assertNull(lt.peerIdPrefix, "libtorrent keeps its own")
        assertEquals("libtorrent", TorrentClients.byId("gone", "9.0.9").id, "unknown falls back")
        assertEquals("XDM/9.0.9", TorrentClients.byId("xdm", "9.0.9").userAgent, "XDM")
    }
}
