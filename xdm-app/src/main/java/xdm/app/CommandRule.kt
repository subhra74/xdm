package xdm.app

import xdm.core.downloaders.DownloadType
import java.io.File

/**
 * A command run when a download whose file has one of [extensions] finishes (Settings > Advanced).
 * Rules are tried in order and the first enabled match wins; a file no rule matches, and every batch
 * download (many files, no single extension), goes to the catch-all command (`AppConfig.customCommand`).
 */
data class CommandRule(
    /** Lowercase, without the dot, e.g. `zip` or `tar.gz`; see [normalizeExtensions]. */
    val extensions: List<String>,
    val command: String,
    val enabled: Boolean = true,
) {
    /** Whether [fileName] ends with one of [extensions] (case-insensitive, after a dot). */
    fun matches(fileName: String): Boolean =
        extensions.any { fileName.endsWith(".$it", ignoreCase = true) }

    companion object {
        /** `"ZIP, .rar *.7z;tar.gz"` → `[zip, rar, 7z, tar.gz]`: separators are commas, semicolons and spaces. */
        fun normalizeExtensions(text: String): List<String> =
            text.split(',', ';', ' ', '\t')
                .map { it.trim().removePrefix("*").trimStart('.').lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}

/** The tokens of a command after its placeholders are filled in; see [PostDownloadCommand.expand]. */
object PostDownloadCommand {
    const val FILE = "%file%"
    const val NAME = "%name%"
    const val BASENAME = "%basename%"
    const val EXT = "%ext%"
    const val FOLDER = "%folder%"
    const val FOLDER_NAME = "%foldername%"
    const val TYPE = "%type%"
    private val ALL = listOf(FILE, NAME, BASENAME, EXT, FOLDER, FOLDER_NAME, TYPE)
    private val PATTERN = Regex(ALL.joinToString("|") { Regex.escape(it) })

    /**
     * The template for a finished download: the first enabled [rules] entry that matches the file name,
     * else [fallback] when [fallbackEnabled]. A batch ([DownloadType.Batch]) only ever takes the fallback.
     * Null when nothing should run.
     */
    fun pick(
        rules: List<CommandRule>,
        fallbackEnabled: Boolean,
        fallback: String,
        path: String,
        type: DownloadType,
    ): String? {
        if (type != DownloadType.Batch) {
            val name = File(path).name
            rules.firstOrNull { it.enabled && it.command.isNotBlank() && it.matches(name) }
                ?.let { return it.command }
        }
        return if (fallbackEnabled && fallback.isNotBlank()) fallback else null
    }

    /**
     * Splits [template] into arguments (double quotes group words) and fills the placeholders for
     * [path], a file or, for a batch, the folder. A template with no placeholder at all gets the path
     * and then the type added as its last two arguments. No shell runs the result: substitution is
     * per argument, so a path with spaces stays one argument.
     */
    fun expand(template: String, path: String, type: DownloadType): List<String> {
        val tokens = tokenize(template)
        if (tokens.isEmpty()) return tokens
        val values = values(path, type)
        if (tokens.none { t -> ALL.any { t.contains(it) } }) {
            return tokens + values.getValue(FILE) + values.getValue(TYPE)
        }
        // One pass, so a value that itself contains a placeholder (a folder named "%ext%") stays as it is.
        return tokens.map { t -> PATTERN.replace(t) { m -> values[m.value] ?: m.value } }
    }

    /** What a [DownloadType] reads as in `%type%`. */
    fun typeName(type: DownloadType): String = type.name.lowercase()

    private fun values(path: String, type: DownloadType): Map<String, String> {
        val file = File(path)
        val isFolder = type == DownloadType.Batch
        val name = file.name
        val dot = name.lastIndexOf('.')
        val folder = if (isFolder) file else file.absoluteFile.parentFile
        return linkedMapOf(
            FILE to path,
            NAME to name,
            BASENAME to if (isFolder || dot <= 0) name else name.substring(0, dot),
            EXT to if (isFolder || dot <= 0) "" else name.substring(dot + 1),
            FOLDER to (folder?.path ?: ""),
            FOLDER_NAME to (folder?.name ?: ""),
            TYPE to typeName(type),
        )
    }

    /** Splits a command line into tokens, honouring double-quoted segments. */
    fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var hasToken = false
        for (ch in input.trim()) {
            when {
                ch == '"' -> { inQuotes = !inQuotes; hasToken = true }
                ch.isWhitespace() && !inQuotes -> {
                    if (hasToken) {
                        tokens.add(current.toString())
                        current.setLength(0)
                        hasToken = false
                    }
                }
                else -> { current.append(ch); hasToken = true }
            }
        }
        if (hasToken) tokens.add(current.toString())
        return tokens
    }
}
