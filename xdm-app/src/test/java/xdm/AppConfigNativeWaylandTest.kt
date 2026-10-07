package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** "Native Wayland" is on by default, round-trips, and older configs load it as on. */
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

    @Test
    fun configWrittenBeforeTheField_loadsAsOn_andKeepsEarlierFields() {
        AppConfig(dir.absolutePath).apply {
            batchAsOneFromClipboard = true
            nativeWayland = false
        }.save()
        // Drop this boolean, keeping AtomicIO's 8-byte end marker.
        val file = File(dir, AppConfig.CONFIG_FILE)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 9) + "XDM-END!".toByteArray(Charsets.US_ASCII))

        val config = load()
        assertTrue(config.nativeWayland, "missing field keeps the default")
        assertTrue(config.batchAsOneFromClipboard, "field before it still read")
    }
}
