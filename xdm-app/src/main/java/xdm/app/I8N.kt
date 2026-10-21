package xdm.app

import xdm.app.utils.AppDirs
import xdm.core.util.Logger
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * UI texts. `lang/en.txt` holds every key; a translation `lang/<code>.txt` is laid over it, so a
 * text it lacks shows in English. Files are `java.util.Properties` in UTF-8 (docs/i18n.md).
 *
 * Files in [overrideDir] are laid over the bundled ones: translators drop a file there to see
 * their work in the app before it ships, and a language XDM does not bundle yet shows up too.
 *
 * Startup only reads the two files it needs. Bundled files are checked at build time
 * (LanguageFilesTest), so only preview files get the placeholder check here.
 */
object I8N {
    const val BASE = "en"

    /** The key each file sets to its language's name, as listed in Settings. */
    const val NAME_KEY = "LANGUAGE_NAME"

    data class Language(val code: String, val name: String)

    @Volatile
    private var texts = Properties()
    private val reportedMissing = ConcurrentHashMap.newKeySet<String>()

    // Texts use two kinds of placeholder: java.util.Formatter's (%s, %d, %1$s ...; group 3 is the
    // conversion) and named ones the code fills with replace() (%free%, %folder% ...). Lazy: only
    // preview files and the tests need them, so a normal startup never compiles them.
    private val specifier by lazy { Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?([a-zA-Z%])") }
    private val named by lazy { Regex("%[a-z][a-z_]+%") }

    val overrideDir: File get() = File(AppDirs.root, "lang")

    /** The text for [id]; the id itself (logged once) when not even `en.txt` has it. */
    fun text(id: String): String =
        texts.getProperty(id) ?: id.also { if (reportedMissing.add(id)) Logger.error("I8N: no text for $id") }

    fun loadTexts(code: String): Boolean = loadTexts(code, ::bundled, overrideDir)

    /**
     * Loads, each over the last: bundled English, preview English, bundled [code], preview [code].
     * A preview text whose placeholders differ from the English one is dropped, as `String.format`
     * would throw on it. False when [code] has no file anywhere (English is still loaded).
     */
    internal fun loadTexts(code: String, bundled: (String) -> InputStream?, overrideDir: File?): Boolean {
        Logger.info("Loading language $code")
        // Bundled files load straight into one Properties, as before: no copies on the startup path.
        val merged = Properties()
        if (!loadInto(merged, bundled(BASE))) Logger.error("I8N: bundled $BASE.txt not found")
        overrideDir?.let { read(File(it, "$BASE.txt")) }?.let(merged::putAll)

        var found = code == BASE
        if (!found && isValidCode(code)) {
            found = loadInto(merged, bundled(code))
            overrideDir?.let { read(File(it, "$code.txt")) }?.let { preview ->
                found = true
                val english = Properties().also { loadInto(it, bundled(BASE)) }
                for (key in preview.stringPropertyNames()) {
                    val value = preview.getProperty(key)
                    val base = english.getProperty(key)
                    if (base != null && specifiers(base) != specifiers(value)) {
                        Logger.error("I8N: preview $code.txt $key has other placeholders than en.txt, not used")
                    } else {
                        merged[key] = value
                    }
                }
            }
        }
        if (!found) Logger.error("I8N: no language file for $code, using English")
        texts = merged
        return found
    }

    /**
     * The languages for the Settings list: the build-time `lang/index` (code=name per bundled file)
     * plus the files in [overrideDir]. English first, the rest by name.
     */
    fun languages(): List<Language> = languages(resource("index"), overrideDir)

    internal fun languages(index: Properties?, overrideDir: File?): List<Language> {
        val names = LinkedHashMap<String, String>()
        index?.stringPropertyNames()?.forEach { names[it] = index.getProperty(it) }
            ?: Logger.error("I8N: lang/index not found (built without Maven?), listing English only")
        names.putIfAbsent(BASE, "English")
        // Preview files are few; a name of their own wins, else the bundled one, else the code.
        overrideDir?.listFiles { f -> f.name.endsWith(".txt") }?.forEach { f ->
            val code = f.name.removeSuffix(".txt")
            if (isValidCode(code)) {
                names[code] = read(f)?.getProperty(NAME_KEY)?.takeIf { it.isNotBlank() } ?: names[code] ?: code
            }
        }
        return names.filterKeys(::isValidCode).map { (code, name) -> Language(code, name) }
            .sortedWith(compareBy<Language> { it.code != BASE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** The placeholders in [text], `%free%` or a Formatter conversion (`%%` and `%n` aside), sorted. */
    internal fun specifiers(text: String): List<String> {
        val names = named.findAll(text).map { it.value }.toList()
        val conversions = specifier.findAll(named.replace(text, "")).map { it.groupValues[3].lowercase() }
            .filter { it != "%" && it != "n" }
        return (names + conversions).sorted()
    }

    // A code names a file: ASCII letters, digits, '_' and '-' only, so nothing can leave the folder.
    private fun isValidCode(code: String): Boolean =
        code.isNotEmpty() && code.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

    private fun bundled(code: String): InputStream? = I8N::class.java.getResourceAsStream("/lang/$code.txt")

    private fun resource(name: String): Properties? = I8N::class.java.getResourceAsStream("/lang/$name")?.let(::load)

    private fun read(file: File): Properties? =
        if (file.isFile) runCatching { load(file.inputStream()) }.onFailure { Logger.error(it) }.getOrNull() else null

    private fun load(stream: InputStream): Properties = Properties().also { loadInto(it, stream) }

    private fun loadInto(target: Properties, stream: InputStream?): Boolean {
        stream ?: return false
        InputStreamReader(stream, StandardCharsets.UTF_8).use { target.load(it) }
        return true
    }
}
