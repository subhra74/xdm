package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** "Detect subtitle files" and "Save as SRT" are on by default and round-trip independently. */
class AppConfigSubtitlesTest {
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
        val config = AppConfig(dir.absolutePath)
        assertTrue(config.detectSubtitles, "detection on by default")
        assertTrue(config.saveSubtitlesAsSrt, "SRT on by default")
    }

    @Test
    fun eachValue_isSavedAndLoaded() {
        for ((detect, srt) in listOf(true to false, false to true, false to false)) {
            AppConfig(dir.absolutePath).apply { detectSubtitles = detect; saveSubtitlesAsSrt = srt }.save()
            val loaded = load()
            assertEquals(detect, loaded.detectSubtitles, "detect round trip of $detect")
            assertEquals(srt, loaded.saveSubtitlesAsSrt, "SRT round trip of $srt")
        }
    }
}
