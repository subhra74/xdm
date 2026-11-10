package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.app.ConfigSnapshot
import xdm.app.IAppConfig
import java.io.File
import java.nio.file.Files

/** Saving Settings keeps values changed elsewhere while it was open, unless the user changed them there too. */
class ConfigSnapshotTest {
    private lateinit var dir: File
    private lateinit var config: AppConfig

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-config").toFile()
        config = AppConfig(dir.absolutePath)
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** What the settings pages do on save: write back every value they loaded, plus the user's edits. */
    private fun writeAll(values: AppConfig, edit: AppConfig.() -> Unit) {
        config.showDownloadCompleteWindow = values.showDownloadCompleteWindow
        config.maxSegments = values.maxSegments
        config.noneFolder = values.noneFolder
        config.proxyHost = values.proxyHost
        config.edit()
    }

    private fun copyOf(c: AppConfig) = AppConfig(dir.absolutePath).also {
        it.showDownloadCompleteWindow = c.showDownloadCompleteWindow
        it.maxSegments = c.maxSegments
        it.noneFolder = c.noneFolder
        it.proxyHost = c.proxyHost
    }

    @Test
    fun valueChangedElsewhere_survivesSave() {
        config.showDownloadCompleteWindow = true
        val loaded = ConfigSnapshot.of(config)
        val shown = copyOf(config)

        config.showDownloadCompleteWindow = false // "don't show again" while Settings is open
        config.noneFolder = "/elsewhere"          // a download dialog remembered a folder
        val beforeWrite = ConfigSnapshot.of(config)
        writeAll(shown) { maxSegments = 16 }
        loaded.keepChangedElsewhere(config, beforeWrite)

        assertEquals(false, config.showDownloadCompleteWindow, "change made elsewhere is kept")
        assertEquals("/elsewhere", config.noneFolder, "nullable value changed elsewhere is kept")
        assertEquals(16, config.maxSegments, "the user's own edit is saved")
    }

    @Test
    fun bothChanged_userWins() {
        config.proxyHost = "a"
        val loaded = ConfigSnapshot.of(config)
        val shown = copyOf(config)

        config.proxyHost = "elsewhere"
        val beforeWrite = ConfigSnapshot.of(config)
        writeAll(shown) { proxyHost = "typed" }
        loaded.keepChangedElsewhere(config, beforeWrite)

        assertEquals("typed", config.proxyHost, "the value the user just chose stays")
    }

    @Test
    fun nothingChangedElsewhere_savesAsWritten() {
        val loaded = ConfigSnapshot.of(config)
        val shown = copyOf(config)
        val beforeWrite = ConfigSnapshot.of(config)
        writeAll(shown) { maxSegments = 3; showDownloadCompleteWindow = !shown.showDownloadCompleteWindow }
        loaded.keepChangedElsewhere(config, beforeWrite)

        assertEquals(3, config.maxSegments, "edit saved")
        assertEquals(!shown.showDownloadCompleteWindow, config.showDownloadCompleteWindow, "edit saved")
    }

    /**
     * Every settable [IAppConfig] property is in the snapshot's hand-written list. Reflection is fine
     * here: tests never go into the native image.
     */
    @Test
    fun snapshotCoversEveryConfigVar() {
        val methods = IAppConfig::class.java.methods
        val vars = methods.filter { it.parameterCount == 0 && it.returnType != Void.TYPE }.mapNotNull { getter ->
            val name = when {
                getter.name.startsWith("get") -> getter.name.removePrefix("get")
                getter.name.startsWith("is") -> getter.name.removePrefix("is")
                else -> return@mapNotNull null
            }
            val hasSetter = methods.any {
                it.name == "set$name" && it.parameterCount == 1 && it.parameterTypes[0] == getter.returnType
            }
            if (hasSetter) name.replaceFirstChar { it.lowercase() } else null
        }.toSet()
        // Derived from another value that is in the list.
        val excluded = setOf("showDownloadCompleteWindow")
        val listed = ConfigSnapshot.propertyNames.toSet()
        assertEquals(emptySet<String>(), vars - excluded - listed, "config vars missing from ConfigSnapshot")
        assertEquals(emptySet<String>(), listed - vars, "ConfigSnapshot lists something that is not a config var")
        assertEquals(listed.size, ConfigSnapshot.propertyNames.size, "no property listed twice")
    }
}
