package xdm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.integration.ConfigDto
import java.io.File
import java.nio.file.Files

/** The update announced while hidden is remembered; the sync reply carries XDM's version. */
class AppConfigNotifiedUpdateTest {
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
    fun defaultsToBlank() {
        assertEquals("", AppConfig(dir.absolutePath).notifiedUpdateVersion, "nothing announced yet")
    }

    @Test
    fun isSavedAndLoaded() {
        AppConfig(dir.absolutePath).apply { notifiedUpdateVersion = "9.1.0" }.save()
        assertEquals("9.1.0", AppConfig(dir.absolutePath).apply { load() }.notifiedUpdateVersion, "round trip")
    }

    @Test
    fun syncReply_carriesAppVersion() {
        val dto = ConfigDto(true, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), appVersion = "9.0.9")
        assertTrue(Json.encodeToString(dto).contains("\"appVersion\":\"9.0.9\""), "appVersion in the JSON")
    }
}
