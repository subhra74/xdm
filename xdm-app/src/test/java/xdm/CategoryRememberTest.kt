package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.utils.captureDestination
import xdm.app.utils.rememberCategoryPick
import xdm.app.utils.rememberHandPickedFolder
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.integration.BrowserIntegration
import xdm.integration.ExtensionMessage
import java.io.File
import java.nio.file.Files

/**
 * What is remembered between downloads (docs/download-categories.md §4.2): only a hand-picked None,
 * the None folder and the folder history; and where a browser capture goes without a dialog.
 */
class CategoryRememberTest {
    private lateinit var dir: File
    private lateinit var config: AppConfig

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-remember").toFile()
        config = AppConfig(dir.absolutePath).apply {
            defaultDownloadFolder = File(dir, "downloads").absolutePath
            categories = DownloadCategory.defaults(File(dir, "downloads").absolutePath)
        }
        AppContext.config = config
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun video() = config.categories.first { it.id == "CAT_VIDEOS" }

    @Test
    fun handPickingNone_turnsRulesOff_andACategoryTurnsThemBackOn() {
        assertEquals("CAT_VIDEOS", captureDestination("clip.mp4", null, null).first)
        rememberCategoryPick(null)
        assertTrue(config.rememberNone)
        assertNull(captureDestination("clip.mp4", null, null).first, "None wins over the rules")
        rememberCategoryPick(video())
        assertFalse(config.rememberNone)
        assertEquals("CAT_VIDEOS", captureDestination("clip.mp4", null, null).first)
    }

    @Test
    fun folderPickedUnderNone_becomesTheNoneFolder_underACategoryOnlyJoinsTheHistory() {
        val a = File(dir, "a").absolutePath
        val b = File(dir, "b").absolutePath
        rememberHandPickedFolder(a, underNone = false)
        assertNull(config.noneFolder, "a one-off under a category does not change the None folder")
        rememberHandPickedFolder(b, underNone = true)
        assertEquals(b, config.noneFolder)
        assertEquals(listOf(b, a), config.folderHistory, "most recent first")
        assertEquals(null to b, captureDestination("notes.xyz", null, null))
    }

    @Test
    fun folderHistory_isDedupedAndCapped() {
        repeat(15) { rememberHandPickedFolder(File(dir, "f$it").absolutePath, underNone = false) }
        rememberHandPickedFolder(File(dir, "f3").absolutePath, underNone = false)
        assertEquals(AppConfig.MAX_FOLDER_HISTORY, config.folderHistory.size)
        assertEquals(File(dir, "f3").absolutePath, config.folderHistory.first())
        assertEquals(config.folderHistory.distinct(), config.folderHistory)
    }

    @Test
    fun changingTheDefaultFolder_resetsTheNoneFolder() {
        rememberHandPickedFolder(File(dir, "picked").absolutePath, underNone = true)
        config.defaultDownloadFolder = File(dir, "new-default").absolutePath
        assertNull(config.noneFolder)
        assertEquals(null to config.defaultDownloadFolder, captureDestination("notes.xyz", null, null))
    }

    @Test
    fun rememberedState_survivesSaveAndLoad() {
        rememberCategoryPick(null)
        rememberHandPickedFolder(File(dir, "x").absolutePath, underNone = true)
        val loaded = AppConfig(dir.absolutePath).apply { load() }
        assertTrue(loaded.rememberNone)
        assertEquals(File(dir, "x").absolutePath, loaded.noneFolder)
        assertEquals(listOf(File(dir, "x").absolutePath), loaded.folderHistory)
    }

    private fun taskFor(fileName: String): HttpDownloadTaskInfo {
        val msg = ExtensionMessage(url = "http://127.0.0.1/$fileName", filename = fileName, method = "GET", fileSize = 1024)
        val m = BrowserIntegration::class.java.getDeclaredMethod("toHttpSource", ExtensionMessage::class.java)
            .apply { isAccessible = true }
        return m.invoke(BrowserIntegration, msg) as HttpDownloadTaskInfo
    }

    @Test
    fun browserCapture_isCategorizedByTheRules() {
        val task = taskFor("clip.mp4")
        assertEquals("CAT_VIDEOS", task.categoryId)
        assertEquals(video().folder, task.targetFolder)
    }

    @Test
    fun browserCapture_withNoneRemembered_goesToTheNoneFolder() {
        rememberCategoryPick(null)
        val task = taskFor("clip.mp4")
        assertNull(task.categoryId)
        assertEquals(config.defaultDownloadFolder, task.targetFolder)
    }
}
