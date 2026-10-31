package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** A config saved before the switch moves Dark to System once; Light and later choices are kept. */
class AppConfigThemeMigrationTest {
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
    fun oldDark_becomesSystem() {
        AppConfig(dir.absolutePath).apply { theme = "dark" }.save()
        val config = load()
        assertTrue(config.adoptSystemTheme(), "first start after the upgrade changes the config")
        assertEquals("system", config.theme, "old default moved to system")
    }

    @Test
    fun oldLight_isKept() {
        AppConfig(dir.absolutePath).apply { theme = "light" }.save()
        val config = load()
        config.adoptSystemTheme()
        assertEquals("light", config.theme, "a picked light theme stays")
    }

    @Test
    fun runsOnlyOnce() {
        AppConfig(dir.absolutePath).apply { theme = "dark" }.save()
        load().apply { adoptSystemTheme() }.apply { theme = "dark" }.save()
        val config = load()
        assertFalse(config.adoptSystemTheme(), "already migrated")
        assertEquals("dark", config.theme, "dark picked after the switch stays")
    }
}
