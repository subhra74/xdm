package xdm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xdm.app.AppConfig
import xdm.app.DownloadCompleteNotification
import java.io.File
import java.nio.file.Files

/** The three-way "when a download finishes" setting round-trips, and older configs still load. */
class AppConfigCompleteNotificationTest {
    private lateinit var dir: File

    @Before
    fun setup() {
        dir = Files.createTempDirectory("xdm-config").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun load() = AppConfig(dir.absolutePath).apply { load() }

    /** Strips the trailing mode field, leaving the config an older build would have written. */
    private fun dropTrailingMode() {
        val file = File(dir, AppConfig.CONFIG_FILE)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 12) + "XDM-END!".toByteArray(Charsets.US_ASCII))
    }

    @Test
    fun eachMode_isSavedAndLoaded() {
        for (mode in DownloadCompleteNotification.entries) {
            AppConfig(dir.absolutePath).apply { downloadCompleteNotification = mode }.save()
            assertEquals(mode, load().downloadCompleteNotification)
        }
    }

    @Test
    fun legacyBoolean_tracksTheMode() {
        val config = AppConfig(dir.absolutePath)
        config.downloadCompleteNotification = DownloadCompleteNotification.DIALOG
        assertEquals(true, config.showDownloadCompleteWindow)
        config.downloadCompleteNotification = DownloadCompleteNotification.NOTIFICATION
        assertEquals(false, config.showDownloadCompleteWindow)
        config.showDownloadCompleteWindow = false
        assertEquals(DownloadCompleteNotification.NONE, config.downloadCompleteNotification)
        config.showDownloadCompleteWindow = true
        assertEquals(DownloadCompleteNotification.DIALOG, config.downloadCompleteNotification)
    }

    @Test
    fun oldConfigWithDialogEnabled_loadsAsDialog() {
        AppConfig(dir.absolutePath).apply { downloadCompleteNotification = DownloadCompleteNotification.DIALOG }.save()
        dropTrailingMode()
        assertEquals(DownloadCompleteNotification.DIALOG, load().downloadCompleteNotification)
    }

    @Test
    fun oldConfigWithDialogDisabled_loadsAsNone() {
        AppConfig(dir.absolutePath).apply { downloadCompleteNotification = DownloadCompleteNotification.NONE }.save()
        dropTrailingMode()
        assertEquals(DownloadCompleteNotification.NONE, load().downloadCompleteNotification)
    }
}
