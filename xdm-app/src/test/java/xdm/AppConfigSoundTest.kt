package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** The "Play a sound" toggle and the two custom WAV paths round-trip; the defaults are off and blank. */
class AppConfigSoundTest {
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
    fun defaults_areOffAndBlank() {
        AppConfig(dir.absolutePath).save()
        val config = load()
        assertEquals(false, config.playSound)
        assertEquals("", config.completeSoundFile)
        assertEquals("", config.failedSoundFile)
    }

    @Test
    fun values_areSavedAndLoaded() {
        AppConfig(dir.absolutePath).apply {
            playSound = true
            completeSoundFile = "/sounds/done.wav"
            failedSoundFile = "C:\\sounds\\oops.wav"
        }.save()
        val config = load()
        assertEquals(true, config.playSound)
        assertEquals("/sounds/done.wav", config.completeSoundFile)
        assertEquals("C:\\sounds\\oops.wav", config.failedSoundFile)
    }
}
