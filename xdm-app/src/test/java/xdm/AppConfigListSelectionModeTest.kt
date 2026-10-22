package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.app.ListSelectionMode
import java.io.File
import java.nio.file.Files

/** The download list's selection mode defaults to Classic and round-trips. */
class AppConfigListSelectionModeTest {
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
    fun defaultsToClassic() {
        assertEquals(ListSelectionMode.CLASSIC, AppConfig(dir.absolutePath).listSelectionMode, "Classic by default")
    }

    @Test
    fun eachMode_isSavedAndLoaded() {
        for (mode in ListSelectionMode.entries) {
            AppConfig(dir.absolutePath).apply { listSelectionMode = mode }.save()
            assertEquals(mode, load().listSelectionMode, "round trip of $mode")
        }
    }
}
