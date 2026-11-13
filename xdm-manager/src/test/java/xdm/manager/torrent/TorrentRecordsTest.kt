// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The record layouts of xdm_torrent.h, written here by hand the way the native side writes them. */
class TorrentRecordsTest {
    private val key = "b17fc5bc7c813b248811c4fce49c9b766fb8d097"

    private class W {
        val out = ByteArrayOutputStream()
        private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        fun u8(v: Int) = apply { out.write(v) }
        fun u16(v: Int) = apply { out.write(le(2).putShort(v.toShort()).array()) }
        fun i32(v: Int) = apply { out.write(le(4).putInt(v).array()) }
        fun i64(v: Long) = apply { out.write(le(8).putLong(v).array()) }
        fun raw(b: ByteArray) = apply { out.write(b) }
        fun str(s: String) = apply { val b = s.toByteArray(); i32(b.size); raw(b) }
        fun header() = u8(1).u8(0).u16(0)
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun record(type: Int, payload: ByteArray) = W().u16(type).u16(0).i32(payload.size).raw(payload).bytes()

    private fun events(vararg records: ByteArray): ByteArray {
        val w = W().u8(1).u8(0).u16(records.size)
        records.forEach { w.raw(it) }
        return w.bytes()
    }

    @Test
    fun `events of every payload shape`() {
        val status = W().raw(key.toByteArray()).i32(TorrentState.DOWNLOADING).i32(TorrentStatus.SF_HAS_METADATA or TorrentStatus.SF_SEQUENTIAL)
            .i64(4000).i64(1000).i64(1200).i64(1500).i64(300)
            .i32(2048).i32(512).i32(12).i32(4).i32(40).i32(-1).i32(13).i32(250_000)
            .i64(1_700_000_000).i64(0).i64(600).i64(0)
            .i32(1530).i32(100).i32(25)
            .str("ubuntu.iso").str("/downloads/é").str("")
            .bytes()
        val buf = events(
            record(1, key.toByteArray()),
            record(3, W().raw(key.toByteArray()).i32(TorrentState.SEEDING).bytes()),
            record(7, W().raw(key.toByteArray()).i32(28).str("No space").bytes()),
            record(8, W().raw(key.toByteArray()).i32(3).raw(byteArrayOf(1, 2, 3)).bytes()),
            record(11, W().raw(key.toByteArray()).str("/new").bytes()),
            record(17, W().raw("0".repeat(40).toByteArray()).i32(6881).bytes()),
            record(19, status),
            record(21, W().raw("0".repeat(40).toByteArray()).i64(312).bytes()),
            record(99, W().raw(key.toByteArray()).bytes()), // a newer event type: skipped
        )
        val ev = TorrentRecords.events(buf)
        assertEquals(8, ev.size, "unknown types are skipped")
        assertEquals(TorrentEvent(TorrentEventType.METADATA, key), ev[0], "simple event")
        assertEquals(TorrentState.SEEDING, ev[1].state, "state")
        assertEquals(28 to "No space", ev[2].code to ev[2].message, "error code and message")
        assertContentEquals(byteArrayOf(1, 2, 3), ev[3].data, "resume data")
        assertEquals("/new", ev[4].path, "moved")
        assertEquals("", ev[5].key, "session events have no key")
        assertEquals(6881, ev[5].port, "listen port")
        val s = ev[6].status!!
        assertEquals(key, s.key, "status key")
        assertEquals(4000L to 1000L, s.totalWanted to s.totalWantedDone, "wanted")
        assertEquals(1500L to 300L, s.allTimeDownload to s.allTimeUpload, "all time")
        assertEquals(0.2f, s.ratio, "ratio over the larger of downloaded and done")
        assertEquals(listOf(2048, 512, 12, 4, 40, -1, 13, 250_000),
            listOf(s.downloadRate, s.uploadRate, s.peers, s.seeds, s.complete, s.incomplete, s.connections, s.progressPpm),
            "rates and counts")
        assertEquals(listOf(1530, 100, 25), listOf(s.availabilityMilli, s.numPieces, s.piecesDone), "pieces")
        assertEquals(Triple("ubuntu.iso", "/downloads/é", ""), Triple(s.name, s.savePath, s.error), "strings")
        assertTrue(s.hasMetadata && !s.finished && !s.paused, "flags")
        assertEquals(312L, ev[7].dhtNodes, "DHT nodes")
        assertEquals(emptyList(), TorrentRecords.events(ByteArray(0)), "a timeout is no events")
    }

    @Test
    fun `torrent info with files`() {
        val buf = W().header().str(key).str("").str("pack").i64(3_683_138).i32(32768).i32(114).i32(0x3)
            .str("comment").str("creator").i64(1_700_000_000).i32(2)
            .i64(2_109_497).i64(1000).u8(4).u8(0).u16(0).str("pack/a.bin")
            .i64(17_799).i64(0).u8(0).u8(TorrentFile.ATTR_PAD).u16(0).str(".pad/17799")
            .bytes()
        val info = TorrentRecords.info(buf)
        assertEquals(key, info.key, "v1 key")
        assertEquals("pack", info.name, "name")
        assertTrue(info.isPrivate, "private flag")
        assertEquals(2, info.files.size, "files")
        assertEquals(TorrentFile(2_109_497, 1000, 4, 0, "pack/a.bin"), info.files[0], "first file")
        assertTrue(info.files[1].isPad, "padding file")
        assertFalse(info.singleFile, "a file inside a folder")
        val v2only = info.copy(v1 = "", v2 = "ab".repeat(32))
        assertEquals("ab".repeat(20), v2only.key, "v2 hash truncated to 20 bytes")
    }

    @Test
    fun `piece map, peers and trackers`() {
        val pm = TorrentRecords.pieces(W().header().i32(10).raw(byteArrayOf(0b1010_0000.toByte(), 0b0100_0000)).bytes())
        assertEquals(listOf(0, 2, 9), (0 until 10).filter { pm.has(it) }, "bit 7 of byte 0 is piece 0")
        assertEquals(3, pm.count, "count")

        val peers = TorrentRecords.peers(
            W().header().i32(1).str("10.0.0.2").i32(51413).str("Client 1.0").i32(TorrentPeer.SEED or TorrentPeer.UTP)
                .i32(TorrentPeer.SRC_DHT).i32(100).i32(5).i64(1000).i64(50).i32(1_000_000).bytes()
        )
        assertEquals(TorrentPeer("10.0.0.2", 51413, "Client 1.0", TorrentPeer.SEED or TorrentPeer.UTP,
            TorrentPeer.SRC_DHT, 100, 5, 1000, 50, 1_000_000), peers.single(), "peer")

        val trackers = TorrentRecords.trackers(
            W().header().i32(1).str("udp://t:80").u8(0).u8(1).u8(TorrentTracker.ERROR).u8(0)
                .i32(-1).i32(-1).i32(-1).i32(2).i64(0).str("timed out").bytes()
        )
        assertEquals(TorrentTracker("udp://t:80", 0, 1, TorrentTracker.ERROR, -1, -1, -1, 2, 0, "timed out"),
            trackers.single(), "tracker")
    }

    @Test
    fun `truncated and wrong-version buffers are rejected`() {
        assertThrows<IllegalArgumentException> { TorrentRecords.peers(W().header().i32(1).str("10.0.0.2").bytes()) }
        assertThrows<IllegalArgumentException> { TorrentRecords.info(W().u8(2).u8(0).u16(0).bytes()) }
        assertThrows<IllegalArgumentException> { TorrentRecords.events(W().u8(1).u8(0).u16(1).u16(1).u16(0).i32(500).bytes()) }
    }

    @Test
    fun `settings carry the defaults and the port only on a first start, no client id of our own`() {
        val first = TorrentSettings.session(23456, downloadLimit = 1024)
        assertTrue("listen_interfaces=0.0.0.0:23456,[::]:23456" in first, "port")
        assertTrue("download_rate_limit=1024" in first, "limit")
        assertFalse("xt.fingerprint" in first || "user_agent" in first, "libtorrent identifies the client")
        assertFalse("listen_interfaces" in TorrentSettings.session(null), "kept from the saved state")
        val proxied = TorrentSettings.session(null, proxy = TorrentProxy(TorrentProxy.Type.SOCKS5, "h", 1080, "u", "p"))
        assertTrue("proxy_type=3" in proxied && "proxy_username=u" in proxied, "socks5 with a password")
        assertTrue(TorrentSettings.randomPort() in 10000 until 65000, "random port range")
    }
}
