package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.core.CoreConfig
import java.io.File
import java.nio.file.Files

/** CODE_REVIEW B11: the read timeout setting is saved with the config and clamped on load. */
class AppConfigReadTimeoutTest {
    private lateinit var dir: File

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-config").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun readTimeout_isSavedAndLoaded() {
        AppConfig(dir.absolutePath).apply { readTimeoutSeconds = 90 }.save()
        assertEquals(90, AppConfig(dir.absolutePath).apply { load() }.readTimeoutSeconds)
    }

    @Test
    fun outOfRangeValue_isClampedOnLoad() {
        AppConfig(dir.absolutePath).apply { readTimeoutSeconds = 100_000 }.save()
        assertEquals(AppConfig.MAX_READ_TIMEOUT_SECONDS, AppConfig(dir.absolutePath).apply { load() }.readTimeoutSeconds)
    }
}
