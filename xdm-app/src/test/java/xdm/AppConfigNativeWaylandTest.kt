package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** "Native Wayland" is on by default and round-trips. */
class AppConfigNativeWaylandTest {
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
        assertTrue(AppConfig(dir.absolutePath).nativeWayland, "on by default")
    }

    @Test
    fun eachValue_isSavedAndLoaded() {
        for (value in listOf(false, true)) {
            AppConfig(dir.absolutePath).apply { nativeWayland = value }.save()
            assertEquals(value, load().nativeWayland, "round trip of $value")
        }
    }
}
