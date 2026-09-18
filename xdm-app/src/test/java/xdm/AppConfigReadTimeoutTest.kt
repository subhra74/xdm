package xdm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xdm.app.AppConfig
import xdm.core.CoreConfig
import java.io.File
import java.nio.file.Files

/** CODE_REVIEW B11: the read timeout setting is saved with the config and older configs keep the default. */
class AppConfigReadTimeoutTest {
    private lateinit var dir: File

    @Before
    fun setup() {
        dir = Files.createTempDirectory("xdm-config").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun readTimeout_isSavedAndLoaded() {
        AppConfig(dir.absolutePath).apply { readTimeoutSeconds = 90 }.save()
        assertEquals(90, AppConfig(dir.absolutePath).apply { load() }.readTimeoutSeconds)
    }

    @Test
    fun configWithoutReadTimeout_keepsDefault() {
        AppConfig(dir.absolutePath).save()
        // Drop the last field (the Int timeout) plus the completion footer, then restore the footer:
        // this is what a config saved before the setting existed looks like.
        val file = File(dir, AppConfig.CONFIG_FILE)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 12) + "XDM-END!".toByteArray(Charsets.US_ASCII))

        assertEquals(CoreConfig.DEFAULT_READ_TIMEOUT_SECONDS, AppConfig(dir.absolutePath).apply { load() }.readTimeoutSeconds)
    }

    @Test
    fun outOfRangeValue_isClampedOnLoad() {
        AppConfig(dir.absolutePath).apply { readTimeoutSeconds = 100_000 }.save()
        assertEquals(AppConfig.MAX_READ_TIMEOUT_SECONDS, AppConfig(dir.absolutePath).apply { load() }.readTimeoutSeconds)
    }
}
