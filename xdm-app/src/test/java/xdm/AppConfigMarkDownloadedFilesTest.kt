package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** "Mark downloaded files" is on by default and round-trips. */
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
}
