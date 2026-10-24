// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.core.storage.LocalStorage
import xdm.core.storage.uniqueFileName
import xdm.core.util.PlatformUtils
import java.io.File
import java.io.FileNotFoundException
import xdm.core.storage.TargetExistsException

/** [LocalStorage]: the desktop backend behind [xdm.core.downloaders.DownloadHost.storage]. */
class TestLocalStorage {
    @TempDir
    lateinit var root: File

    @Test
    fun childFolderIsCreatedOnlyByCreate() {
        val folder = LocalStorage.folder(root.path).child("a").child("b")
        assertFalse(File(root, "a").exists(), "child() must not touch storage")
        assertTrue(folder.create(), "create() makes missing parents")
        assertEquals(File(root, "a/b").path, folder.location, "location is the absolute path")
        assertEquals(folder.location, LocalStorage.folder(folder.location).location, "location round-trips")
    }

    @Test
    fun randomAccessWritesAndLength() {
        val folder = LocalStorage.folder(root.path)
        assertEquals(-1, folder.length("p"), "missing file has length -1")
        assertThrows(FileNotFoundException::class.java, { folder.open("p", create = false) }, "open without create")
        folder.open("p", create = true).use {
            it.setLength(10)
            it.seek(8)
            it.write(byteArrayOf(1, 2), 0, 2)
            it.seek(0)
            it.write(byteArrayOf(9), 0, 1)
            assertEquals(1, it.position(), "position after write")
            it.force()
        }
        assertArrayEquals(byteArrayOf(9, 0, 0, 0, 0, 0, 0, 0, 1, 2), File(root, "p").readBytes(), "bytes at offsets")
        folder.open("p", create = true).use { assertEquals(10, it.length(), "reopening keeps content") }
    }

    @Test
    fun presizedFileHasLengthAndReadsZeros() {
        val folder = LocalStorage.folder(root.path)
        folder.openPresized("p", 1000).use {
            assertEquals(1000, it.length(), "new file presized")
            assertEquals(0, it.position(), "positioned at 0")
            it.seek(500)
            it.write(byteArrayOf(7), 0, 1)
        }
        val bytes = File(root, "p").readBytes()
        assertEquals(7, bytes[500].toInt(), "write lands at its offset")
        assertTrue(bytes.withIndex().all { (i, b) -> i == 500 || b == 0.toByte() }, "the gap reads as zeros")
        folder.openPresized("empty", 0).use { assertEquals(0, it.length(), "zero length is fine") }
    }

    @Test
    fun presizeSetsLengthOfExistingFile() {
        val folder = LocalStorage.folder(root.path)
        File(root, "short").writeBytes(byteArrayOf(1, 2, 3))
        folder.openPresized("short", 10).use { assertEquals(10, it.length(), "existing file grown") }
        assertArrayEquals(byteArrayOf(1, 2, 3, 0, 0, 0, 0, 0, 0, 0), File(root, "short").readBytes(), "content kept")
        File(root, "long").writeBytes(ByteArray(20) { 5 })
        folder.openPresized("long", 4).use { assertEquals(4, it.length(), "existing file shrunk") }
    }

    @Test
    fun presizedFileIsSparse() {
        // `du` reports allocated blocks; Windows has no `du` (NTFS was checked by hand with fsutil).
        assumeTrue(!PlatformUtils.isWindows, "needs du")
        val size = 256L shl 20
        LocalStorage.folder(root.path).openPresized("big", size).close()
        val p = ProcessBuilder("du", "-k", File(root, "big").path).start()
        val allocatedKb = p.inputStream.bufferedReader().readText().trim().split(Regex("\\s+"))[0].toLong()
        p.waitFor()
        assertEquals(size, File(root, "big").length(), "full length")
        assertTrue(allocatedKb < 1024, "presize must not allocate the gap, got $allocatedKb KB")
    }

    @Test
    fun renameNeverReplacesUnlessAsked() {
        val folder = LocalStorage.folder(root.path)
        File(root, "part").writeText("new")
        File(root, "taken").writeText("old")
        assertThrows(TargetExistsException::class.java, { folder.rename("part", "taken", replace = false) }, "no replace")
        assertEquals("old", File(root, "taken").readText(), "existing file untouched")
        assertTrue(File(root, "part").exists(), "source untouched")
        folder.rename("part", "taken", replace = true)
        assertEquals("new", File(root, "taken").readText(), "replaced")
        folder.rename("taken", "free", replace = false)
        assertEquals(listOf("free"), folder.listFiles(), "renamed")
    }

    @Test
    fun deleteIfEmptyKeepsFolderWithFiles() {
        val folder = LocalStorage.folder(root.path).child("batch")
        folder.create()
        folder.open("x", create = true).close()
        folder.deleteIfEmpty()
        assertTrue(folder.exists(), "folder with a file stays")
        assertTrue(folder.delete("x"), "delete")
        assertTrue(folder.delete("x"), "deleting a missing file counts as gone")
        folder.deleteIfEmpty()
        assertFalse(folder.exists(), "empty folder removed")
    }

    @Test
    fun uniqueNameSkipsTakenNames() {
        val taken = setOf("a.mp4", "a_1.mp4")
        assertEquals("b.mp4", uniqueFileName("b.mp4") { it in taken }, "free name kept")
        assertEquals("a_2.mp4", uniqueFileName("a.mp4") { it in taken }, "first free suffix")
        assertEquals("noext_1", uniqueFileName("noext") { it == "noext" }, "no extension")
    }
}
