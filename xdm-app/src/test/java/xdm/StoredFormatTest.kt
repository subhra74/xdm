package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.app.AppDB
import xdm.app.DbRecord
import xdm.app.RecordStatus
import xdm.core.downloaders.DownloadType
import xdm.core.util.AtomicIO
import xdm.core.util.TagWriter
import xdm.core.util.TaggedFile
import java.io.File
import java.nio.file.Files

/** The config and download lists are numbered-field files: unknown fields and records do not break them. */
class StoredFormatTest {
    private lateinit var dir: File

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-format").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val footer = "XDM-END!".toByteArray(Charsets.US_ASCII)

    private fun File.contains(text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        val bytes = readBytes()
        return (0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } }
    }

    @Test
    fun config_keepsFieldsOfANewerBuild() {
        AppConfig(dir.absolutePath).apply { lang = "de" }.save()
        // Add a field this build does not know, as a newer build would have.
        val file = File(dir, AppConfig.CONFIG_FILE)
        val bytes = file.readBytes()
        val extra = TagWriter().apply { string(900, "from-a-newer-build") }.toByteArray()
        file.writeBytes(bytes.copyOf(bytes.size - footer.size) + extra + footer)

        val config = AppConfig(dir.absolutePath).apply { load() }
        assertEquals("de", config.lang, "known field")
        config.theme = "light"
        config.save()

        assertTrue(file.contains("from-a-newer-build"), "unknown field dropped on save")
        assertEquals("light", AppConfig(dir.absolutePath).apply { load() }.theme, "file still readable")
    }

    @Test
    fun config_emptyListsStayEmpty() {
        AppConfig(dir.absolutePath).apply {
            blockedHosts = emptyList()
            fileExtensions = emptyList()
            categories = emptyList()
        }.save()
        val config = AppConfig(dir.absolutePath).apply { load() }
        assertEquals(emptyList<String>(), config.blockedHosts, "blocked hosts back to defaults")
        assertEquals(emptyList<String>(), config.fileExtensions, "extensions back to defaults")
        assertEquals(emptyList<Any>(), config.categories, "categories back to defaults")
    }

    @Test
    fun config_categoriesRoundTrip() {
        val saved = AppConfig(dir.absolutePath).apply {
            categories = categories.mapIndexed { i, c -> c.copy(color = if (i == 0) 0x112233 else null) }
        }
        saved.save()
        assertEquals(saved.categories, AppConfig(dir.absolutePath).apply { load() }.categories, "categories")
    }

    private fun record(id: Long, status: RecordStatus, type: DownloadType = DownloadType.Http) = DbRecord(
        id = id, size = 100, downloaded = 50, progress = 50, date = 1_700_000_000_000 + id,
        fileName = "file$id.bin", eta = 0, speed = 0f, selected = false, status = status,
        downloadType = type, categoryId = if (id % 2 == 0L) "CAT_VIDEOS" else null,
    )

    @Test
    fun downloadLists_roundTrip() {
        val db = AppDB(dir.absolutePath)
        val records = listOf(
            record(1, RecordStatus.PAUSED),
            record(2, RecordStatus.ERROR, DownloadType.Hls),
            record(3, RecordStatus.FINISHED, DownloadType.Dash),
        )
        records.forEach { db.addActive(it) }
        db.savePausedRecords()
        db.saveFinishedRecords()

        val loaded = AppDB(dir.absolutePath).apply { loadRecords() }
        assertEquals(records.toSet(), (0 until loaded.size).map { loaded.getByIndex(it) }.toSet(), "records")
    }

    @Test
    fun downloadList_skipsARecordOfAnUnknownType() {
        AtomicIO.writeTransacted("paused-downloads.dat", dir.absolutePath) { out ->
            TaggedFile.write(out) {
                message(1) { long(1, 7); string(6, "new.bin"); int(7, 99) }
                message(1) { long(1, 8); string(6, "ok.bin"); int(7, DownloadType.Http.id); long(50, 1) }
            }
        }
        val db = AppDB(dir.absolutePath).apply { loadRecords() }
        assertEquals(1, db.size, "only the readable record")
        assertEquals("ok.bin", db.getByIndex(0).fileName, "readable record")
    }
}
