package xdm

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.torrent.TorrentSelection
import xdm.app.torrent.TorrentSource
import xdm.manager.torrent.FilePriority
import xdm.manager.torrent.TorrentFile
import xdm.manager.torrent.TorrentInfo

/** The add-torrent dialog's input parsing and file list. */
class TorrentSelectionTest {
    private val hash = "b17fc5bc7c813b248811c4fce49c9b766fb8d097"

    private fun info(name: String, vararg files: TorrentFile) =
        TorrentInfo(hash, "", name, files.sumOf { it.size }, 16384, 1, 0, "", "", 0, files.toList())

    private fun file(path: String, size: Long, pad: Boolean = false) =
        TorrentFile(size, 0, FilePriority.NORMAL, if (pad) TorrentFile.ATTR_PAD else 0, path)

    private val multi = info(
        "pack",
        file("pack/b.txt", 10),
        file("pack/.pad/6", 6, pad = true),
        file("pack/Sub/z.bin", 100),
        file("pack/A.txt", 20),
        file("pack/Sub/deeper/y.bin", 1000),
    )

    @Test
    fun `sources are magnets, bare hashes and existing files`() {
        val magnet = "magnet:?xt=urn:btih:$hash&dn=Ubuntu%2024.04"
        assertEquals(TorrentSource.Magnet(magnet), TorrentSource.of("  $magnet ") { false }, "magnet, trimmed")
        assertEquals(TorrentSource.Magnet("magnet:?xt=urn:btih:$hash"), TorrentSource.of(hash) { false }, "40 hex")
        assertEquals(TorrentSource.TorrentFile("/t/a.torrent"), TorrentSource.of("\"/t/a.torrent\"") { it == "/t/a.torrent" },
            "a quoted path to a file")
        assertNull(TorrentSource.of("/t/missing.torrent") { false }, "a path that is not a file")
        assertEquals(TorrentSource.Link("https://example.com/a.torrent"), TorrentSource.of("https://example.com/a.torrent") { false },
            "an http(s) link")
        assertNull(TorrentSource.of("   ") { true }, "blank")
        assertEquals("Ubuntu 24.04", TorrentSource.displayName(magnet), "dn decoded")
        assertNull(TorrentSource.displayName("magnet:?xt=urn:btih:$hash"), "no dn")
    }

    @Test
    fun `rows put folders first then files by name, without the torrent's own folder or padding`() {
        val rows = TorrentSelection(multi).rows.map { "  ".repeat(it.depth) + it.name + if (it.isFolder) "/" else "" }
        assertEquals(listOf("Sub/", "  deeper/", "    y.bin", "  z.bin", "A.txt", "b.txt"), rows, "tree order")
        val sub = TorrentSelection(multi).rows.first()
        assertEquals(1100L, sub.size, "a folder's size is its files'")
    }

    @Test
    fun `a single-file torrent is one row`() {
        val sel = TorrentSelection(info("ubuntu.iso", file("ubuntu.iso", 4000)))
        assertEquals(listOf("ubuntu.iso"), sel.rows.map { it.name }, "the file itself")
        assertEquals(1, sel.fileCount, "one file")
    }

    @Test
    fun `everything wanted gives no priorities, unticking a folder skips its files only`() {
        val sel = TorrentSelection(multi)
        assertEquals(4, sel.fileCount, "padding is not a file")
        assertEquals(1130L, sel.selectedSize, "all files")
        assertArrayEquals(ByteArray(0), sel.priorities(), "the default: all normal")

        val sub = sel.rows.first { it.name == "Sub" }
        sel.set(sub, false)
        assertFalse(sel.isSelected(sub), "folder off")
        assertEquals(30L, sel.selectedSize, "only the two top files")
        assertArrayEquals(
            byteArrayOf(FilePriority.NORMAL, FilePriority.NORMAL, FilePriority.SKIP, FilePriority.NORMAL, FilePriority.SKIP),
            sel.priorities(), "native order, padding normal")

        sel.set(sel.rows.first { it.name == "y.bin" }, true)
        assertTrue(sel.isPartial(sub), "one file of the folder back")
        assertTrue(sel.isSelected(sel.rows.first { it.name == "deeper" }), "its own folder is complete")

        sel.setAll(false)
        assertEquals(0, sel.selectedCount, "none")
        sel.setAll(true)
        assertArrayEquals(ByteArray(0), sel.priorities(), "all again")
    }
}
