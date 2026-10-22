package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** "Skip repeated playlist requests" is off by default and round-trips. */
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
}
