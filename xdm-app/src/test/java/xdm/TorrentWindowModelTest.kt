package xdm

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.app.torrent.PeerText
import xdm.app.torrent.PieceBar
import xdm.app.torrent.TorrentFiles
import xdm.manager.torrent.FilePriority
import xdm.manager.torrent.PieceMap
import xdm.manager.torrent.TorrentFile
import xdm.manager.torrent.TorrentInfo
import xdm.manager.torrent.TorrentPeer

/** The torrent window's file priorities and progress, piece bar and peer texts. */
class TorrentWindowModelTest {
    private fun file(path: String, size: Long, done: Long = 0, pad: Boolean = false) =
        TorrentFile(size, done, FilePriority.NORMAL, if (pad) TorrentFile.ATTR_PAD else 0, path)

    private fun info(vararg files: TorrentFile) =
        TorrentInfo("a".repeat(40), "", "pack", files.sumOf { it.size }, 16384, 4, 0, "", "", 0, files.toList())

    private val pack = info(file("pack/a.bin", 100), file("pack/.pad/1", 1, pad = true), file("pack/sub/b.bin", 300))

    @Test
    fun `priorities start from the saved ones and a folder shows Mixed until its files agree`() {
        val f = TorrentFiles(pack, byteArrayOf(FilePriority.HIGH, FilePriority.NORMAL, FilePriority.SKIP))
        val sub = f.rows.first { it.isFolder }
        assertEquals(FilePriority.SKIP, f.priority(sub), "one file: its priority")
        f.setPriority(f.rows.first { it.name == "a.bin" }, FilePriority.LOW)
        assertArrayEquals(byteArrayOf(FilePriority.LOW, FilePriority.NORMAL, FilePriority.SKIP), f.priorities(), "native order")
        f.setPriority(sub, FilePriority.NORMAL)
        f.setPriority(f.rows.first { it.name == "a.bin" }, FilePriority.NORMAL)
        assertArrayEquals(ByteArray(0), f.priorities(), "all normal saves as empty")
    }

    @Test
    fun `progress is unknown until live files arrive, and full for wanted files once finished`() {
        val f = TorrentFiles(pack, byteArrayOf(FilePriority.NORMAL, FilePriority.NORMAL, FilePriority.SKIP))
        val a = f.rows.first { it.name == "a.bin" }
        val sub = f.rows.first { it.isFolder }
        assertNull(f.progress(a), "not running")
        f.updateProgress(info(file("pack/a.bin", 100, done = 50), file("pack/.pad/1", 1, pad = true), file("pack/sub/b.bin", 300, done = 30)))
        assertEquals(50, f.progress(a), "half")
        assertEquals(10, f.progress(sub), "a skipped file counts what it has")
        f.markComplete()
        assertEquals(100, f.progress(a), "wanted: complete")
        assertEquals(0, f.progress(sub), "skipped: nothing")
    }

    @Test
    fun `the piece bar averages many pieces per column and stretches few`() {
        // 8 pieces, the first four had: 1111 0000
        val map = PieceMap(8, byteArrayOf(0xF0.toByte()))
        assertArrayEquals(floatArrayOf(1f, 1f, 0f, 0f), PieceBar.columns(map, 4), 0.001f, "two pieces a column")
        assertArrayEquals(floatArrayOf(1f, 0f), PieceBar.columns(map, 2), 0.001f, "four a column")
        val half = PieceMap(2, byteArrayOf(0x80.toByte()))
        assertArrayEquals(floatArrayOf(1f, 1f, 0f, 0f), PieceBar.columns(half, 4), 0.001f, "a piece over two columns")
        assertArrayEquals(floatArrayOf(0.5f), PieceBar.columns(half, 1), 0.001f, "mixed column")
    }

    @Test
    fun `peer flags and sources read as short texts`() {
        val p = TorrentPeer("1.2.3.4", 6881, "qB 5.0", TorrentPeer.SEED or TorrentPeer.ENCRYPTED or TorrentPeer.UTP,
            TorrentPeer.SRC_DHT or TorrentPeer.SRC_PEX, 0, 0, 0, 0, 1_000_000)
        assertEquals("SEU", PeerText.flags(p), "seed, encrypted, uTP")
        assertEquals("DHT, PeX", PeerText.sources(p), "found by")
        assertEquals(1, PeerText.countBySource(listOf(p), TorrentPeer.SRC_DHT), "DHT count")
    }
}
