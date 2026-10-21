package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.app.I8N
import java.io.File
import java.util.Properties

/** Loading order, English fallback, the preview folder, and the Settings language list. */
class I8NTest {

    @TempDir
    lateinit var tmp: File

    private val texts = mapOf(
        "en" to "HELLO=Hello\nCOUNT=%d files\nONLY_EN=English only\n",
        "de" to "HELLO=Hallo\nCOUNT=%d Dateien\n",
    )

    // What I8N reads from the classpath, as streams.
    private val files: (String) -> java.io.InputStream? = { texts[it]?.byteInputStream() }

    private fun props(vararg kv: Pair<String, String>) = Properties().apply { kv.forEach { (k, v) -> setProperty(k, v) } }

    private fun write(dir: File, name: String, text: String) = File(dir, name).apply { parentFile.mkdirs(); writeText(text) }

    @AfterEach
    fun reset() {
        I8N.loadTexts("en", files, null)
    }

    @Test
    fun translationOverEnglish() {
        assertTrue(I8N.loadTexts("de", files, null))
        assertEquals("Hallo", I8N.text("HELLO"))
        assertEquals("English only", I8N.text("ONLY_EN"), "a key the translation lacks shows in English")
    }

    @Test
    fun unknownKeyReturnsItsId() {
        I8N.loadTexts("en", files, null)
        assertEquals("NO_SUCH_KEY", I8N.text("NO_SUCH_KEY"))
    }

    @Test
    fun missingLanguageFallsBackToEnglish() {
        assertFalse(I8N.loadTexts("xx", files, null))
        assertEquals("Hello", I8N.text("HELLO"))
    }

    @Test
    fun codeCannotLeaveTheFolder() {
        write(tmp, "secret.txt", "HELLO=leaked")
        assertFalse(I8N.loadTexts("../secret", files, File(tmp, "lang")))
        assertEquals("Hello", I8N.text("HELLO"))
    }

    @Test
    fun brokenPreviewPlaceholderKeepsBundledText() {
        val dir = File(tmp, "lang")
        write(dir, "de.txt", "COUNT=%s Dateien\nHELLO=Servus\n")
        I8N.loadTexts("de", files, dir)
        assertEquals("%d Dateien", I8N.text("COUNT"), "%s instead of %d would throw in String.format")
        assertEquals("Servus", I8N.text("HELLO"), "the rest of the preview file is used")
    }

    @Test
    fun previewFolderOverridesBundled() {
        val dir = File(tmp, "lang")
        write(dir, "de.txt", "HELLO=Servus\n")
        I8N.loadTexts("de", files, dir)
        assertEquals("Servus", I8N.text("HELLO"))
        assertEquals("%d Dateien", I8N.text("COUNT"), "the bundled file still fills in what the preview lacks")
    }

    @Test
    fun previewFolderAddsALanguage() {
        val dir = File(tmp, "lang")
        write(dir, "ta.txt", "HELLO=\\u0bb5\\u0ba3\\u0b95\\u0bcd\\u0b95\\u0bae\\u0bcd\n")
        assertTrue(I8N.loadTexts("ta", files, dir))
        assertEquals("வணக்கம்", I8N.text("HELLO"))
    }

    @Test
    fun specifiersIgnoreOrderAndLiterals() {
        assertEquals(I8N.specifiers("%s of %d"), I8N.specifiers("%2\$d / %1\$s"))
        assertEquals(emptyList<String>(), I8N.specifiers("100%% done%n"))
        assertEquals(listOf("%free%", "%total%"), I8N.specifiers("%total%: %free% free"))
        assertEquals(listOf("%free%", "d"), I8N.specifiers("%d at %free%"))
    }

    @Test
    fun languagesComeFromTheIndex() {
        val index = props("en" to "English", "de" to "Deutsch (German)", "ar" to "Arabic")
        assertEquals(
            listOf(I8N.Language("en", "English"), I8N.Language("ar", "Arabic"), I8N.Language("de", "Deutsch (German)")),
            I8N.languages(index, null),
            "English first, the rest by name",
        )
    }

    @Test
    fun previewFilesJoinTheList() {
        val dir = File(tmp, "lang")
        write(dir, "ta.txt", "LANGUAGE_NAME=Tamil\nHELLO=x\n")
        write(dir, "de.txt", "HELLO=Servus\n")
        val list = I8N.languages(props("en" to "English", "de" to "Deutsch"), dir)
        assertEquals(listOf("en", "de", "ta"), list.map { it.code })
        assertEquals("Deutsch", list[1].name, "a preview without LANGUAGE_NAME keeps the bundled name")
        assertEquals("Tamil", list[2].name)
    }

    @Test
    fun missingIndexStillListsEnglish() {
        assertEquals(listOf(I8N.Language("en", "English")), I8N.languages(null, null))
    }
}
