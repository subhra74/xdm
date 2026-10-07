package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** "Mark downloaded files" is on by default, round-trips, and older configs load it as on. */
class AppConfigMarkDownloadedFilesTest {
    private lateinit var dir: File

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-config").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun load() = AppConfig(dir.absolutePath).apply { load() }

    @Test
    fun defaultsToOn() {
        assertTrue(AppConfig(dir.absolutePath).markDownloadedFiles, "on by default")
    }

    @Test
    fun eachValue_isSavedAndLoaded() {
        for (value in listOf(false, true)) {
            AppConfig(dir.absolutePath).apply { markDownloadedFiles = value }.save()
            assertEquals(value, load().markDownloadedFiles, "round trip of $value")
        }
    }

    @Test
    fun configWrittenBeforeTheField_loadsAsOn_andKeepsEarlierFields() {
        AppConfig(dir.absolutePath).apply {
            skipDuplicateManifests = true
            markDownloadedFiles = false
        }.save()
        // Drop this boolean and the three written after it (the batch dialog modes and
        // nativeWayland), keeping AtomicIO's 8-byte end marker.
        val file = File(dir, AppConfig.CONFIG_FILE)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 12) + "XDM-END!".toByteArray(Charsets.US_ASCII))

        val config = load()
        assertTrue(config.markDownloadedFiles, "missing field keeps the default")
        assertTrue(config.skipDuplicateManifests, "field before it still read")
    }
}
