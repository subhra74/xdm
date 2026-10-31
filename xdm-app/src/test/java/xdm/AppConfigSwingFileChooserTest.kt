package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** "Always use the built-in file chooser" (Linux) is off by default and round-trips. */
class AppConfigSwingFileChooserTest {
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
    fun defaultsToOff() {
        assertFalse(AppConfig(dir.absolutePath).swingFileChooser, "off by default")
    }

    @Test
    fun eachValue_isSavedAndLoaded() {
        for (value in listOf(false, true)) {
            AppConfig(dir.absolutePath).apply { swingFileChooser = value }.save()
            assertEquals(value, load().swingFileChooser, "round trip of $value")
        }
    }
}
