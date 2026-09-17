import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xdm.core.util.AtomicIO
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Regression for CODE_REVIEW B9: [AtomicIO] must read the newest *completely written* copy of a file,
 * never a half-written temp file, must not report a failed save as success, and must still read
 * files written before the completion footer existed.
 */
class TestAtomicIO {
    private lateinit var dir: File
    private val name = "data.dat"
    private val final get() = File(dir, name)
    private val bak1 get() = File(dir, "$name.bak1")
    private val bak2 get() = File(dir, "$name.bak2")

    @Before
    fun setup() {
        dir = Files.createTempDirectory("xdm-atomicio").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Writes a small record list: count, then that many strings. */
    private fun save(vararg items: String) =
        AtomicIO.writeTransacted(name, dir.absolutePath) { out ->
            out.writeInt(items.size)
            items.forEach { out.writeUTF(it) }
        }

    private fun load(): Result<List<String>> =
        AtomicIO.readTransacted(name, dir.absolutePath) { r -> List(r.readInt()) { r.readUTF() } }

    private fun truncate(file: File, keepBytes: Int) = file.writeBytes(file.readBytes().copyOf(keepBytes))

    @Test
    fun roundTrip_andFileEndsWithCompletionFooter() {
        assertTrue(save("a", "b").isSuccess)
        assertEquals(listOf("a", "b"), load().getOrThrow())
        assertEquals("XDM-END!", String(final.readBytes().takeLast(8).toByteArray(), Charsets.US_ASCII))
    }

    @Test
    fun truncatedTempFile_isIgnoredInFavourOfIntactFile() {
        save("good", "data")
        // Crash while writing the next version: a partial .bak1 is left behind.
        bak1.writeBytes(final.readBytes().copyOf(6))
        assertEquals(listOf("good", "data"), load().getOrThrow())
    }

    @Test
    fun writerThatThrows_reportsFailureAndLeavesPreviousDataReadable() {
        save("previous")
        val result = AtomicIO.writeTransacted(name, dir.absolutePath) { out ->
            out.writeInt(2)
            out.writeUTF("half")
            throw IOException("writer failed")
        }
        assertTrue(result.isFailure)
        assertFalse("partial temp file left behind", bak1.exists())
        assertEquals(listOf("previous"), load().getOrThrow())
    }

    @Test
    fun incompleteFinalFile_fallsBackToBackup() {
        save("v1")
        save("v2", "more")
        truncate(final, final.length().toInt() - 3) // lost its tail
        assertEquals(listOf("v1"), load().getOrThrow())
    }

    @Test
    fun readerRunsOnlyOnCompleteData() {
        save("v1")
        save("v2", "v2b", "v2c")
        truncate(final, 12) // count + first string only
        val seen = ArrayList<String>()
        val result = AtomicIO.readTransacted(name, dir.absolutePath) { r ->
            repeat(r.readInt()) { seen += r.readUTF() } // adds as it reads, like AppDB
        }
        assertTrue(result.isSuccess)
        assertEquals("reader must not see the incomplete file", listOf("v1"), seen)
    }

    @Test
    fun crashBetweenRenames_readsCompleteTempFile() {
        save("v1")
        save("v2")
        // final -> .bak2 done, .bak1 (complete) -> final not yet: simulate by moving final to .bak1.
        Files.move(final.toPath(), bak1.toPath())
        assertEquals(listOf("v2"), load().getOrThrow())
    }

    @Test
    fun legacyFileWithoutFooter_isStillReadable() {
        DataOutputStream(final.outputStream()).use { out ->
            out.writeInt(1)
            out.writeUTF("old format")
        }
        assertEquals(listOf("old format"), load().getOrThrow())
    }

    @Test
    fun failedCommit_isReportedAsFailure() {
        // Non-empty directories where the backup and the final file must go: the save cannot complete.
        File(final, "blocker").apply { parentFile.mkdirs(); writeText("x") }
        File(bak2, "blocker").apply { parentFile.mkdirs(); writeText("x") }
        assertTrue("a save that did not reach the final file must fail", save("lost").isFailure)
    }
}
