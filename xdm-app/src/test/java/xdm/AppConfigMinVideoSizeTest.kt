package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** The minimum video size is held in KB; MB values saved by older Settings are read as MB. */
class AppConfigMinVideoSizeTest {
    private lateinit var dir: File

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-config").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun roundTrip(value: Long): Long {
        AppConfig(dir.absolutePath).apply { minVideoSize = value }.save()
        return AppConfig(dir.absolutePath).apply { load() }.minVideoSize
    }

    @Test
    fun defaultsToOneMegabyte() {
        assertEquals(1024L, AppConfig(dir.absolutePath).minVideoSize, "1 MB in KB")
    }

    @Test
    fun kilobyteValues_roundTrip() {
        for (kb in listOf(1024L, 5 * 1024L, 100 * 1024L)) {
            assertEquals(kb, roundTrip(kb), "round trip of $kb KB")
        }
    }

    @Test
    fun megabyteValueSavedByOlderSettings_isReadAsMegabytes() {
        assertEquals(1024L, roundTrip(1), "1 MB")
        assertEquals(50 * 1024L, roundTrip(50), "50 MB")
        assertEquals(100 * 1024L, roundTrip(AppConfig.MAX_MIN_VIDEO_SIZE_MB), "100 MB")
    }
}
