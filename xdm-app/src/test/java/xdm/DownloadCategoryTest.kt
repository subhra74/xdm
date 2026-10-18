package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.CategoryRules
import xdm.app.ui.components.AppTheme
import xdm.app.ui.components.CategoryStyle
import xdm.app.utils.RemixIcon
import xdm.app.utils.folderFor
import java.io.File
import java.nio.file.Files

/** User-defined categories: rules, folder resolution and config round-tripping. */
class DownloadCategoryTest {
    private lateinit var dir: File

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-category").toFile()
        AppContext.config = AppConfig(dir.absolutePath).apply {
            defaultDownloadFolder = dir.absolutePath
            categories = DownloadCategory.defaults(dir.absolutePath)
        }
    }

    @AfterEach
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
    fun defaults_areSeededUnderTheGivenFolder() {
        val seeded = DownloadCategory.defaults("/base")
        assertEquals(File("/base", "Video").absolutePath, seeded.first { it.id == "CAT_VIDEOS" }.folder)
        assertTrue(seeded.all { it.folder.isNotBlank() })
    }

    @Test
    fun renamingACategory_doesNotMoveItsFolder() {
        val video = DownloadCategory.defaults("/base").first { it.id == "CAT_VIDEOS" }
        assertEquals(video.folder, video.copy(name = "Movies").folder)
    }

    @Test
    fun seededFolders_areIndependentOfTheDownloadFolderAfterwards() {
        val config = AppContext.config as AppConfig
        val before = config.categories.map { it.folder }
        config.defaultDownloadFolder = "/somewhere/else"
        assertEquals(before, config.categories.map { it.folder })
    }

    private fun match(name: String?, url: String? = null, page: String? = null) =
        CategoryRules.match(name, url, page)?.id

    @Test
    fun defaultCategories_matchByExtension() {
        assertEquals("CAT_VIDEOS", match("clip.mp4"))
        assertEquals("CAT_DOCUMENTS", match("paper.pdf"))
        assertEquals("CAT_COMPRESSED", match("bundle.tar.gz"))
        assertEquals("CAT_MUSIC", match("song.mp3"))
        assertEquals("CAT_PROGRAMS", match("setup.exe"))
        assertEquals(null, match("notes.xyz"), "no rule matches: None")
    }

    @Test
    fun folderFor_isTheCategoryFolderOrTheNoneFolder() {
        val video = AppContext.config.categories.first { it.id == "CAT_VIDEOS" }
        assertEquals(File(dir, "Video").absolutePath, folderFor(video))
        assertEquals(dir.absolutePath, folderFor(null))
        AppContext.config.noneFolder = "/picked"
        assertEquals("/picked", folderFor(null))
    }

    @Test
    fun globWithoutWildcard_isContains_withWildcard_isWholeMatch() {
        val config = AppContext.config as AppConfig
        config.categories = listOf(
            DownloadCategory(id = "setup", name = "Setups", extensions = emptySet(), fileNamePatterns = listOf("setup"), folder = "/s"),
            DownloadCategory(id = "inv", name = "Invoices", extensions = emptySet(), fileNamePatterns = listOf("invoice_*"), folder = "/i"),
        )
        assertEquals("setup", match("my-SETUP-v2.exe"))
        assertEquals("inv", match("invoice_42.pdf"))
        assertEquals(null, match("old_invoice_42.pdf"), "a wildcard pattern must match the whole name")
    }

    @Test
    fun urlRules_seeTheDownloadAndThePageUrl() {
        val config = AppContext.config as AppConfig
        config.categories = listOf(
            DownloadCategory(id = "yt", name = "Site", extensions = emptySet(), urlPatterns = listOf("example.com/watch"), folder = "/v"),
        )
        assertEquals("yt", match("x.bin", "https://cdn.net/1.ts", "https://www.example.com/watch?v=1"))
        assertEquals(null, match("x.bin", "https://cdn.net/1.ts", "https://other.org/"))
    }

    @Test
    fun all_needsEveryNonEmptyKind() {
        val config = AppContext.config as AppConfig
        config.categories = listOf(
            DownloadCategory(id = "w", name = "Work", extensions = setOf(".pdf"), urlPatterns = listOf("intranet.corp/"),
                matchAll = true, folder = "/w"),
        )
        assertEquals("w", match("a.pdf", "https://intranet.corp/a.pdf"))
        assertEquals(null, match("a.pdf", "https://public.org/a.pdf"), "URL rule not met")
        assertEquals(null, match("a.doc", "https://intranet.corp/a.doc"), "extension rule not met")
    }

    @Test
    fun precedence_moreSpecificKindWins_thenListOrder() {
        val config = AppContext.config as AppConfig
        config.categories = listOf(
            DownloadCategory(id = "docs", name = "Docs", extensions = setOf(".pdf"), folder = "/d"),
            DownloadCategory(id = "work", name = "Work", extensions = emptySet(), urlPatterns = listOf("intranet.corp/"), folder = "/w"),
            DownloadCategory(id = "work2", name = "Work 2", extensions = emptySet(), urlPatterns = listOf("intranet"), folder = "/w2"),
        )
        assertEquals("work", match("report.pdf", "https://intranet.corp/report.pdf"), "URL beats extension; list order breaks the tie")
        assertEquals("docs", match("report.pdf", "https://public.org/report.pdf"))
    }

    @Test
    fun fileGlyph_comesFromTheExtension_notTheCategory() {
        val config = AppContext.config as AppConfig
        // A category claiming .mp4 does not change the glyph: categories only have a color.
        config.categories = listOf(DownloadCategory(id = "w", name = "Work", extensions = setOf(".mp4"), folder = "/w"))
        assertEquals(RemixIcon.MOVIE_FILL, CategoryStyle.glyphForFile("clip.MP4"))
        assertEquals(RemixIcon.FILE_ZIP_FILL, CategoryStyle.glyphForFile("bundle.tar.xz"))
        assertEquals(RemixIcon.FILE_FILL, CategoryStyle.glyphForFile("notes.xyz"))
        assertEquals(RemixIcon.FILE_FILL, CategoryStyle.glyphForFile("README"))
    }

    @Test
    fun pickedColor_winsOverTheThemeColor() {
        val c = DownloadCategory(id = "x", name = "X", extensions = emptySet(), folder = "/x", color = 0x123456)
        assertEquals(java.awt.Color(0x123456), AppTheme.categoryColor(c))
    }

    @Test
    fun categoryWithoutRules_neverMatches() {
        val config = AppContext.config as AppConfig
        config.categories = listOf(DownloadCategory(id = "manual", name = "Manual", extensions = emptySet(), folder = "/m"))
        assertEquals(null, match("anything.mp4", "https://x.org/anything.mp4"))
    }

    @Test
    fun untouchedBuiltIn_usesItsTranslatedName() {
        val video = DownloadCategory.defaults("/base").first { it.id == "CAT_VIDEOS" }
        // Translations are not loaded in tests, so this falls back to the stored name;
        // what matters is that a renamed built-in shows the typed name either way.
        assertEquals("Video", video.displayName)
        assertEquals("Movies", video.copy(name = "Movies").displayName)
    }

    @Test
    fun renamingBuiltInBackRestoresItsKey() {
        val video = DownloadCategory.defaults("/base").first { it.id == "CAT_VIDEOS" }
        assertEquals(video, video.copy(name = "Movies").copy(name = "Video"))
    }

    @Test
    fun userCategory_survivesSaveAndLoad() {
        val custom = DownloadCategory(
            id = "custom-1",
            name = "Subtitles",
            extensions = setOf(".srt", ".vtt"),
            urlPatterns = listOf("opensubtitles.org/"),
            fileNamePatterns = listOf("*.forced.*"),
            matchAll = true,
            folder = "/subs",
            color = 0x2B88C8,
        )
        AppConfig(dir.absolutePath).apply {
            categories = DownloadCategory.defaults(dir.absolutePath) + custom
        }.save()

        val loaded = AppConfig(dir.absolutePath).apply { load() }.categories
        assertEquals(DownloadCategory.defaults(dir.absolutePath).size + 1, loaded.size)
        assertEquals(custom, loaded.last())
    }

    @Test
    fun editedFolder_survivesSaveAndLoad() {
        AppConfig(dir.absolutePath).apply {
            categories = DownloadCategory.defaults(dir.absolutePath)
                .map { if (it.id == "CAT_VIDEOS") it.copy(folder = "/mnt/media") else it }
        }.save()

        val loaded = AppConfig(dir.absolutePath).apply { load() }.categories
        assertEquals("/mnt/media", loaded.first { it.id == "CAT_VIDEOS" }.folder)
    }

    @Test
    fun deletingEveryCategory_survivesSaveAndLoad() {
        AppConfig(dir.absolutePath).apply { categories = emptyList() }.save()
        assertTrue(AppConfig(dir.absolutePath).apply { load() }.categories.isEmpty())
    }
}
