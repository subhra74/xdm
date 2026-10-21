package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.I8N
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * The checks every language file must pass before it ships. CI runs this on translation PRs
 * (.github/workflows/translations.yml), so a Weblate PR that passes can be merged as is.
 */
class LanguageFilesTest {

    private val dir = File("src/main/resources/lang")

    // Properties keeps the last of two equal keys; remember the others.
    private class Recording : Properties() {
        val duplicates = mutableListOf<String>()

        @Synchronized
        override fun put(key: Any, value: Any): Any? = super.put(key, value)?.also { duplicates += key.toString() }
    }

    private fun load(file: File) = Recording().apply {
        InputStreamReader(file.inputStream(), StandardCharsets.UTF_8).use { load(it) }
    }

    private val english = load(File(dir, "${I8N.BASE}.txt"))
    private val translations = dir.listFiles { f -> f.name.endsWith(".txt") && f.name != "${I8N.BASE}.txt" }!!
        .sortedBy { it.name }

    @Test
    fun englishIsComplete() {
        assertTrue(english.duplicates.isEmpty(), "duplicate keys in en.txt: ${english.duplicates}")
        assertTrue(english.getProperty(I8N.NAME_KEY).isNotBlank(), "en.txt has no ${I8N.NAME_KEY}")
    }

    @Test
    fun everyFileParsesAsUtf8() {
        for (file in translations) {
            val bytes = file.readBytes()
            val decoded = StandardCharsets.UTF_8.newDecoder().runCatching { decode(java.nio.ByteBuffer.wrap(bytes)) }
            assertTrue(decoded.isSuccess, "${file.name} is not valid UTF-8")
        }
    }

    @Test
    fun translationsOnlyUseEnglishKeys() {
        val problems = translations.flatMap { file ->
            val p = load(file)
            p.stringPropertyNames().filter { english.getProperty(it) == null }.map { "${file.name}: unknown key $it" } +
                p.duplicates.map { "${file.name}: duplicate key $it" }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun translationsKeepPlaceholders() {
        val problems = translations.flatMap { file ->
            val p = load(file)
            p.stringPropertyNames().mapNotNull { key ->
                val base = english.getProperty(key) ?: return@mapNotNull null
                val value = p.getProperty(key)
                if (I8N.specifiers(base) == I8N.specifiers(value)) null
                else "${file.name}: $key has ${I8N.specifiers(value)}, en.txt has ${I8N.specifiers(base)}"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun buildIndexListsEveryFile() {
        // Written by src/tools/LangIndex.java at process-resources, before the tests run.
        val index = I8N::class.java.getResourceAsStream("/lang/index")
        assertTrue(index != null, "lang/index missing from the build output")
        val listed = Properties().apply { InputStreamReader(index!!, StandardCharsets.UTF_8).use { load(it) } }
        val files = (translations.map { it.name.removeSuffix(".txt") } + I8N.BASE).toSet()
        assertEquals(files, listed.stringPropertyNames(), "codes in lang/index")
        for (code in files) {
            val name = load(File(dir, "$code.txt")).getProperty(I8N.NAME_KEY)
            assertEquals(name, listed.getProperty(code), "lang/index name for $code")
        }
    }

    @Test
    fun everyFileNamesItsLanguage() {
        val unnamed = translations.filter { load(it).getProperty(I8N.NAME_KEY).isNullOrBlank() }.map { it.name }
        assertEquals(emptyList<String>(), unnamed, "files without ${I8N.NAME_KEY}")
    }
}
