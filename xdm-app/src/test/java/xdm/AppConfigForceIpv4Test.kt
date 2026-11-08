package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.File
import java.nio.file.Files

/** "Use IPv4 only" is off by default, round-trips, and the startup reader sees what was saved. */
class AppConfigForceIpv4Test {
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
    fun defaultsToOff() {
        assertFalse(AppConfig(dir.absolutePath).forceIpv4, "off by default")
        assertFalse(AppConfig.readForceIpv4(dir.absolutePath), "no config file reads as off")
    }

    @Test
    fun eachValue_isSavedAndReadBack() {
        for (value in listOf(true, false)) {
            // Other fields around it, so the early reader has to skip them.
            AppConfig(dir.absolutePath).apply {
                forceIpv4 = value
                proxyHost = "proxy.example"
                fileExtensions = listOf("zip", "iso")
            }.save()
            assertEquals(value, AppConfig(dir.absolutePath).apply { load() }.forceIpv4, "round trip of $value")
            assertEquals(value, AppConfig.readForceIpv4(dir.absolutePath), "startup read of $value")
        }
    }

    @Test
    fun unreadableConfig_readsAsOff() {
        File(dir, AppConfig.CONFIG_FILE).writeText("not a tagged file")
        assertFalse(AppConfig.readForceIpv4(dir.absolutePath), "unreadable config reads as off")
    }
}
