package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.app.CommandRule
import xdm.app.PostDownloadCommand
import xdm.core.downloaders.DownloadType
import java.io.File
import java.nio.file.Files

/** Per-extension post-download commands: picking the rule, filling placeholders, saving the rules. */
class CommandRuleTest {
    private lateinit var dir: File
    private val sep = File.separator
    private val folder = "${sep}dl${sep}My Books"
    private val pdf = "$folder${sep}kira goddess.pdf"

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-cmd").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun normalizeExtensions_acceptsDotsStarsCaseAndSeparators() {
        assertEquals(
            listOf("zip", "rar", "7z", "tar.gz"),
            CommandRule.normalizeExtensions(" ZIP, .rar *.7z;tar.gz,, zip"),
            "normalized list"
        )
    }

    @Test
    fun pick_firstEnabledMatchWins_elseFallback() {
        val rules = listOf(
            CommandRule(listOf("pdf"), "off-pdf", enabled = false),
            CommandRule(listOf("gz"), "gz"),
            CommandRule(listOf("tar.gz", "pdf"), "archive-or-pdf"),
            CommandRule(listOf("pdf"), "later-pdf"),
        )
        fun pick(path: String, type: DownloadType = DownloadType.Http, fallbackOn: Boolean = true) =
            PostDownloadCommand.pick(rules, fallbackOn, "fallback", path, type)
        assertEquals("archive-or-pdf", pick(pdf), "disabled rule skipped, first match wins")
        assertEquals("archive-or-pdf", pick("${sep}a${sep}KIRA.PDF"), "case-insensitive")
        assertEquals("gz", pick("${sep}a${sep}x.tar.gz"), "earlier rule matching the last part wins")
        assertEquals("fallback", pick("${sep}a${sep}movie.mp4", DownloadType.Hls), "no match uses fallback")
        assertNull(pick("${sep}a${sep}movie.mp4", fallbackOn = false), "nothing runs with fallback off")
        assertEquals("archive-or-pdf", pick(pdf, fallbackOn = false), "rules run with fallback off")
        assertEquals("fallback", pick("${sep}a${sep}pics.pdf", DownloadType.Batch), "a batch only takes the fallback")
        assertNull(
            PostDownloadCommand.pick(emptyList(), true, "  ", pdf, DownloadType.Http),
            "blank fallback runs nothing"
        )
    }

    @Test
    fun expand_fillsEveryPlaceholderForAFile() {
        val cmd = PostDownloadCommand.expand(
            "\"tool x\" %file% %name% %basename% %ext% %folder% %foldername% %type% --out=%folder%$sep%basename%.txt",
            pdf, DownloadType.Http
        )
        assertEquals(
            listOf(
                "tool x", pdf, "kira goddess.pdf", "kira goddess", "pdf", folder, "My Books", "http",
                "--out=$folder${sep}kira goddess.txt"
            ),
            cmd,
            "expanded command"
        )
    }

    @Test
    fun expand_forABatchFolder() {
        val cmd = PostDownloadCommand.expand("run %file% %name% %basename% [%ext%] %folder% %foldername% %type%", folder, DownloadType.Batch)
        assertEquals(
            listOf("run", folder, "My Books", "My Books", "[]", folder, "My Books", "batch"),
            cmd,
            "batch gets the folder"
        )
    }

    @Test
    fun expand_withoutPlaceholders_appendsPathAndType() {
        assertEquals(
            listOf("unzip", "-o", pdf, "dash"),
            PostDownloadCommand.expand("unzip -o", pdf, DownloadType.Dash),
            "path and type appended"
        )
        assertTrue(PostDownloadCommand.expand("   ", pdf, DownloadType.Http).isEmpty(), "blank command is empty")
    }

    @Test
    fun expand_doesNotReexpandValues() {
        val odd = "${sep}dl${sep}%ext%${sep}a.zip"
        assertEquals(
            listOf("x", odd, "zip"),
            PostDownloadCommand.expand("x %file% %ext%", odd, DownloadType.Http),
            "placeholder inside a value stays"
        )
    }

    @Test
    fun expand_fileWithoutExtension() {
        val path = "$folder${sep}README"
        assertEquals(
            listOf("README", ""),
            PostDownloadCommand.expand("%basename% %ext%", path, DownloadType.Http),
            "no extension"
        )
    }

    @Test
    fun rules_areSavedAndReadBackInOrder() {
        val rules = listOf(
            CommandRule(listOf("zip", "rar"), "unpack \"%file%\""),
            CommandRule(listOf("mp4"), "compress %file%", enabled = false),
        )
        AppConfig(dir.absolutePath).apply {
            commandRules = rules
            runCommand = true
            customCommand = "notify %name%"
        }.save()
        val read = AppConfig(dir.absolutePath).apply { load() }
        assertEquals(rules, read.commandRules, "rules round trip")
        assertEquals("notify %name%", read.customCommand, "fallback kept")
        assertTrue(read.runCommand, "fallback switch kept")
        assertTrue(AppConfig(dir.absolutePath).commandRules.isEmpty(), "no rules by default")
    }
}
