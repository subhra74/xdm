package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.app.DownloadCompleteNotification
import java.io.File
import java.nio.file.Files

/** "Skip repeated playlist requests" is off by default, round-trips, and older configs load it as off. */
class AppConfigSkipDuplicateManifestsTest {
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
        assertFalse(AppConfig(dir.absolutePath).skipDuplicateManifests, "off by default")
    }

    @Test
    fun eachValue_isSavedAndLoaded() {
        for (value in listOf(true, false)) {
            AppConfig(dir.absolutePath).apply { skipDuplicateManifests = value }.save()
            assertEquals(value, load().skipDuplicateManifests, "round trip of $value")
        }
    }

    @Test
    fun configWrittenBeforeTheField_loadsAsOff_andKeepsEarlierFields() {
        AppConfig(dir.absolutePath).apply {
            skipDuplicateManifests = true
            downloadCompleteNotification = DownloadCompleteNotification.NOTIFICATION
        }.save()
        // Drop this boolean and the four written after it (markDownloadedFiles, the batch dialog
        // modes and nativeWayland), keeping AtomicIO's 8-byte end marker.
        val file = File(dir, AppConfig.CONFIG_FILE)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 13) + "XDM-END!".toByteArray(Charsets.US_ASCII))

        val config = load()
        assertFalse(config.skipDuplicateManifests, "missing field keeps the default")
        assertEquals(DownloadCompleteNotification.NOTIFICATION, config.downloadCompleteNotification, "field before it still read")
    }
}
