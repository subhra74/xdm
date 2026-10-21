package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.app.ListSelectionMode
import java.io.File
import java.nio.file.Files

/** The download list's selection mode defaults to Classic, round-trips, and older configs load it as Classic. */
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

    @Test
    fun configWrittenBeforeTheField_loadsAsClassic_andKeepsEarlierFields() {
        AppConfig(dir.absolutePath).apply {
            nativeWayland = false
            listSelectionMode = ListSelectionMode.MODERN
        }.save()
        // Drop this int, keeping AtomicIO's 8-byte end marker.
        val file = File(dir, AppConfig.CONFIG_FILE)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 12) + "XDM-END!".toByteArray(Charsets.US_ASCII))

        val config = load()
        assertEquals(ListSelectionMode.CLASSIC, config.listSelectionMode, "missing field keeps the default")
        assertFalse(config.nativeWayland, "field before it still read")
    }
}
