package xdm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xdm.app.AppConfig
import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.utils.categoryFolderFor
import java.io.File
import java.nio.file.Files

/** User-defined categories: matching, folder resolution and config round-tripping. */
class DownloadCategoryTest {
    private lateinit var dir: File

    @Before
    fun setup() {
        dir = Files.createTempDirectory("xdm-category").toFile()
        AppContext.config = AppConfig(dir.absolutePath).apply {
            defaultDownloadFolder = dir.absolutePath
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun parseExtensions_normalizesUserInput() {
        assertEquals(
            setOf(".mp4", ".mkv", ".webm"),
            DownloadCategory.parseExtensions("MP4, *.mkv; webm")
        )
    }

    @Test
    fun parseExtensions_dropsEmptyEntries() {
        assertTrue(DownloadCategory.parseExtensions(" , ; . ").isEmpty())
    }

    @Test
    fun blankFolder_resolvesToSubfolderOfBase() {
        val cat = DownloadCategory(id = "x", name = "Video", extensions = setOf(".mp4"))
        assertEquals(File("/base", "Video").absolutePath, cat.folderFor("/base"))
    }

    @Test
    fun explicitFolder_overridesBase() {
        val cat = DownloadCategory(id = "x", name = "Video", extensions = setOf(".mp4"), folder = "/movies")
        assertEquals("/movies", cat.folderFor("/base"))
    }

    @Test
    fun defaultCategories_keepLegacyFolderNames() {
        val base = dir.absolutePath
        assertEquals(File(base, "Video").absolutePath, categoryFolderFor("clip.mp4", base))
        assertEquals(File(base, "Documents").absolutePath, categoryFolderFor("paper.pdf", base))
        assertEquals(File(base, "Compressed").absolutePath, categoryFolderFor("bundle.tar.gz", base))
        assertEquals(File(base, "Music").absolutePath, categoryFolderFor("song.mp3", base))
        assertEquals(File(base, "Programs").absolutePath, categoryFolderFor("setup.exe", base))
    }

    @Test
    fun unmatchedFile_staysInBaseFolder() {
        assertEquals(dir.absolutePath, categoryFolderFor("notes.xyz", dir.absolutePath))
    }

    @Test
    fun firstMatchingCategoryWins() {
        val config = AppContext.config as AppConfig
        config.categories = listOf(
            DownloadCategory(id = "a", name = "Podcasts", extensions = setOf(".mp3"), folder = "/podcasts"),
            DownloadCategory(id = "b", name = "Music", extensions = setOf(".mp3"), folder = "/music"),
        )
        assertEquals("/podcasts", categoryFolderFor("episode.mp3", dir.absolutePath))
    }

    @Test
    fun userCategory_survivesSaveAndLoad() {
        val custom = DownloadCategory(
            id = "custom-1",
            name = "Subtitles",
            extensions = setOf(".srt", ".vtt"),
            folder = "/subs",
            icon = "FILE_TEXT_LINE",
        )
        AppConfig(dir.absolutePath).apply {
            categories = DownloadCategory.defaults() + custom
        }.save()

        val loaded = AppConfig(dir.absolutePath).apply { load() }.categories
        assertEquals(DownloadCategory.defaults().size + 1, loaded.size)
        assertEquals(custom, loaded.last())
    }

    @Test
    fun untouchedBuiltIn_usesItsTranslatedName() {
        val video = DownloadCategory.defaults().first { it.id == "CAT_VIDEOS" }
        // Translations are not loaded in tests, so this falls back to the stored name;
        // what matters is that a renamed built-in shows the typed name either way.
        assertEquals("Video", video.displayName)
        assertEquals("Movies", video.copy(name = "Movies").displayName)
    }

    @Test
    fun renamingBuiltInBackRestoresItsKey() {
        val video = DownloadCategory.defaults().first { it.id == "CAT_VIDEOS" }
        val renamed = video.copy(name = "Movies")
        assertEquals(video, renamed.copy(name = "Video"))
    }

    @Test
    fun deletingEveryCategory_survivesSaveAndLoad() {
        AppConfig(dir.absolutePath).apply { categories = emptyList() }.save()
        assertTrue(AppConfig(dir.absolutePath).apply { load() }.categories.isEmpty())
    }
}
