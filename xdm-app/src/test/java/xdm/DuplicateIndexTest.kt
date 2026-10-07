package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppDB
import xdm.app.DbRecord
import xdm.app.DuplicateIndex
import xdm.app.DuplicateKind
import xdm.app.RecordStatus
import xdm.core.downloaders.DownloadType
import java.io.File
import java.nio.file.Files

class DuplicateIndexTest {
    private lateinit var dir: File
    private lateinit var appDB: AppDB
    private lateinit var index: DuplicateIndex

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-dup-test").toFile()
        appDB = AppDB(dir.absolutePath)
        index = DuplicateIndex(dir.absolutePath, appDB)
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun addRecord(id: Long, fileName: String, size: Long, date: Long = id) {
        appDB.addActive(
            DbRecord(
                id = id, size = size, downloaded = 0, progress = 0, date = date, fileName = fileName,
                eta = 0, speed = 0f, selected = false, status = RecordStatus.FINISHED,
                downloadType = DownloadType.Http
            )
        )
    }

    @Test
    fun sameUrlMatchesAfterNormalizing() {
        addRecord(1, "a.zip", 0)
        index.add(1, "https://Example.COM/files/a.zip?v=2#top", null)
        val match = index.find("  https://example.com/files/a.zip?v=2  ", "other.zip", null, null)
        assertEquals(1L, match?.record?.id, "matched record")
        assertEquals(DuplicateKind.SAME_URL, match?.kind, "match kind")
        assertNull(index.find("https://example.com/files/a.zip?v=3", "other.zip", null, null), "query differs")
    }

    @Test
    fun nameAndKnownSizeMatch() {
        addRecord(1, "Setup.exe", 1000)
        index.add(1, "https://cdn.example.com/x?sig=1", null)
        val match = index.find("https://cdn.example.com/x?sig=2", "setup.exe", 1000, null)
        assertEquals(DuplicateKind.SAME_NAME_AND_SIZE, match?.kind, "match kind")
        assertNull(index.find("https://cdn.example.com/x?sig=2", "setup.exe", 999, null), "size differs")
        assertNull(index.find("https://cdn.example.com/x?sig=2", "setup.exe", null, null), "size unknown")
    }

    @Test
    fun unknownRecordSizeNeverMatchesByName() {
        addRecord(1, "setup.exe", 0)
        assertNull(index.find("https://example.com/other", "setup.exe", 0, null), "zero size")
    }

    @Test
    fun strongEtagMatchesOnSameHostOnly() {
        addRecord(1, "a.bin", 0)
        index.add(1, "https://example.com/a?t=1", "\"abc\"")
        assertEquals(
            DuplicateKind.SAME_ETAG,
            index.find("https://example.com/b?t=2", "b.bin", null, "\"abc\"")?.kind,
            "same host and etag"
        )
        assertNull(index.find("https://other.com/b", "b.bin", null, "\"abc\""), "other host")
    }

    @Test
    fun weakEtagIsIgnored() {
        addRecord(1, "a.bin", 0)
        index.add(1, "https://example.com/a", "W/\"abc\"")
        assertNull(index.find("https://example.com/b", "b.bin", null, "W/\"abc\""), "weak etag")
    }

    @Test
    fun urlBeatsNameAndSize() {
        addRecord(1, "a.zip", 500)
        addRecord(2, "b.zip", 0)
        index.add(1, "https://example.com/1", null)
        index.add(2, "https://example.com/2", null)
        val match = index.find("https://example.com/2", "a.zip", 500, null)
        assertEquals(2L, match?.record?.id, "url match wins")
    }

    @Test
    fun removedOrMissingRecordsAreIgnored() {
        addRecord(1, "a.zip", 0)
        index.add(1, "https://example.com/a", null)
        index.add(2, "https://example.com/b", null) // no record
        assertNull(index.find("https://example.com/b", "b.zip", null, null), "no record")
        appDB.removeItem(1)
        assertNull(index.find("https://example.com/a", "a.zip", null, null), "removed")
    }

    @Test
    fun partialTrailingRecordIsDroppedAndAppendsContinue() {
        addRecord(1, "a.zip", 0)
        addRecord(2, "b.zip", 0)
        index.add(1, "https://example.com/a", null)
        File(dir, DuplicateIndex.FILE_NAME).appendBytes(ByteArray(7)) // a crash mid-append
        assertEquals(1L, index.find("https://example.com/a", "x", null, null)?.record?.id, "before append")
        index.add(2, "https://example.com/b", null)
        assertEquals(1L, index.find("https://example.com/a", "x", null, null)?.record?.id, "old entry")
        assertEquals(2L, index.find("https://example.com/b", "x", null, null)?.record?.id, "new entry")
    }

    @Test
    fun compactsWhenMostEntriesAreDead() {
        val file = File(dir, DuplicateIndex.FILE_NAME)
        addRecord(1, "keep.zip", 0)
        index.add(1, "https://example.com/keep", "\"k\"")
        for (id in 2L..1501L) index.add(id, "https://example.com/$id", null) // no records: dead
        val before = file.length()
        assertEquals(1L, index.find("https://example.com/keep", "x", null, null)?.record?.id, "live entry")
        assertEquals(8L + 25, file.length(), "compacted from $before bytes")
        assertEquals(
            DuplicateKind.SAME_ETAG,
            index.find("https://example.com/other", "x", null, "\"k\"")?.kind,
            "etag survives compaction"
        )
    }

    @Test
    fun survivesReloadAndFollowsRefreshedUrl() {
        addRecord(1, "a.zip", 0)
        index.add(1, "https://example.com/a", "\"e1\"")
        index.updateUrl(1, "https://example.com/new")
        val reloaded = DuplicateIndex(dir.absolutePath, appDB)
        assertEquals(1L, reloaded.find("https://example.com/new", "x", null, null)?.record?.id, "new url")
        assertNull(reloaded.find("https://example.com/a", "x", null, null), "old url")
        assertEquals(
            DuplicateKind.SAME_ETAG,
            reloaded.find("https://example.com/z", "x", null, "\"e1\"")?.kind,
            "etag kept across refresh"
        )
    }
}
