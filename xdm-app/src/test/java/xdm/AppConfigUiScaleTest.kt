package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** Interface size: 100% by default, each choice round-trips, anything else loads as 100%. */
class AppConfigUiScaleTest {
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
    fun defaultsTo100() {
        assertEquals(100, AppConfig(dir.absolutePath).uiScale, "100% by default")
    }

    @Test
    fun eachChoice_isSavedAndLoaded() {
        for (value in AppConfig.UI_SCALES) {
            AppConfig(dir.absolutePath).apply { uiScale = value }.save()
            assertEquals(value, load().uiScale, "round trip of $value")
        }
    }

    @Test
    fun unknownValue_loadsAs100() {
        AppConfig(dir.absolutePath).apply { uiScale = 333 }.save()
        assertEquals(100, load().uiScale, "a value that isn't a choice")
    }

    @Test
    fun systemTheme_isSavedAndLoaded() {
        AppConfig(dir.absolutePath).apply { theme = "system" }.save()
        assertEquals("system", load().theme, "theme round trip")
    }
}
