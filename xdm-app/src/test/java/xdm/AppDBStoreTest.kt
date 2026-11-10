package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppDB
import xdm.core.downloaders.DownloadType
import xdm.manager.DbRecord
import xdm.manager.RecordStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [AppDB] as a [xdm.manager.DownloadStore]: a record whose status changes leaves the list file it was in. */
class AppDBStoreTest {
    private lateinit var dir: File

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-appdb-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun record(id: Long, status: RecordStatus) = DbRecord(
        id = id, size = 10, downloaded = 0, progress = 0, date = 1, fileName = "f$id.bin", eta = 0,
        speed = 0f, selected = false, status = status, downloadType = DownloadType.Http,
    )

    private fun reload() = AppDB(dir.absolutePath).apply { loadRecords() }

    @Test
    fun `a record moves between list files without being duplicated`() {
        val db = AppDB(dir.absolutePath)
        val rec = record(1, RecordStatus.PAUSED)
        db.add(rec)
        assertEquals(RecordStatus.PAUSED, reload().getById(1)?.status, "saved in the paused list")

        rec.status = RecordStatus.READY
        db.update(rec)
        assertEquals(1, reload().size, "left the paused list for the active one")

        rec.status = RecordStatus.FINISHED
        db.update(rec)
        val loaded = reload()
        assertEquals(1, loaded.size, "left the active list for the finished one")
        assertEquals(RecordStatus.FINISHED, loaded.getById(1)?.status, "comes back finished")
    }

    @Test
    fun `a loaded record resumed and removed is gone from every list`() {
        AppDB(dir.absolutePath).add(record(2, RecordStatus.PAUSED))
        val db = reload()
        val rec = db.getById(2)!!
        rec.status = RecordStatus.READY
        db.update(rec)
        assertEquals(1, reload().size, "the paused list no longer has it")

        // Changed again without saving, then removed: the active file it is still in loses it too.
        rec.status = RecordStatus.PAUSED
        assertEquals(0, db.remove(2), "its position in the list")
        assertNull(reload().getById(2), "removed from every list file")
        assertEquals(0, reload().size, "nothing left")
    }
}
