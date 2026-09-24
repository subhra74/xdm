package xdm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xdm.app.AppConfig
import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.I8N
import xdm.app.utils.categoryFolderFor
import xdm.app.utils.rememberedAutoCategorize
import xdm.app.utils.rememberedBaseFolder
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.integration.BrowserIntegration
import xdm.integration.ExtensionMessage
import java.io.File
import java.nio.file.Files

/**
 * Downloads started from the browser extension follow the user's last "Save in" choice, so they
 * honour both the configured download folder and the "Automatic (by file type)" option. Before
 * this they hardcoded `autoCategorize = false` and a fixed folder, which meant categories never
 * applied to anything coming from the browser.
 */
class ExtensionDownloadFolderTest {
    private lateinit var dir: File
    private lateinit var config: AppConfig

    @Before
    fun setup() {
        // recentFolders builds the "Automatic" label from the language file.
        I8N.loadTexts("en")
        dir = Files.createTempDirectory("xdm-ext").toFile()
        config = AppConfig(dir.absolutePath).apply {
            defaultDownloadFolder = File(dir, "downloads").absolutePath
            savedFolders = listOf(File(dir, "other").absolutePath)
            categories = DownloadCategory.defaults(File(dir, "downloads").absolutePath)
        }
        AppContext.config = config
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun taskFor(fileName: String): HttpDownloadTaskInfo {
        val msg = ExtensionMessage(
            url = "http://127.0.0.1/$fileName",
            filename = fileName,
            method = "GET",
            fileSize = 1024,
        )
        val m = BrowserIntegration::class.java.getDeclaredMethod("toHttpSource", ExtensionMessage::class.java)
            .apply { isAccessible = true }
        return m.invoke(BrowserIntegration, msg) as HttpDownloadTaskInfo
    }

    @Test
    fun automaticChoice_makesBrowserDownloadsUseCategories() {
        config.autoSelectFolder = true

        val task = taskFor("clip.mp4")
        assertTrue("browser download must follow the Automatic choice", task.autoCategorize)
        assertEquals(config.defaultDownloadFolder, task.defaultDownloadFolder)

        // The category decides the real destination at publish time.
        assertEquals(
            config.categories.first { it.id == "CAT_VIDEOS" }.folder,
            categoryFolderFor(task.fileName, task.defaultDownloadFolder)
        )
    }

    @Test
    fun explicitFolderChoice_isUsedInsteadOfCategories() {
        config.autoSelectFolder = false
        config.folderIndex = 0 // the default download folder

        val task = taskFor("clip.mp4")
        assertFalse(task.autoCategorize)
        assertEquals(config.defaultDownloadFolder, task.defaultDownloadFolder)
    }

    @Test
    fun aSavedFolderChoice_isHonoured() {
        config.autoSelectFolder = false
        config.folderIndex = 1 // the saved folder, not the default one

        assertEquals(config.savedFolders[0], rememberedBaseFolder())
        assertEquals(config.savedFolders[0], taskFor("clip.mp4").defaultDownloadFolder)
    }

    @Test
    fun configuredDownloadFolderIsRespected() {
        // Previously this used AppContext.defaultDownloadFolder, so a download folder set in
        // settings was ignored for anything started from the browser.
        config.autoSelectFolder = true
        config.defaultDownloadFolder = File(dir, "elsewhere").absolutePath

        assertTrue(rememberedAutoCategorize())
        assertEquals(config.defaultDownloadFolder, taskFor("file.bin").defaultDownloadFolder)
    }
}
