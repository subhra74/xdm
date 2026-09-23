package xdm.app

import java.io.File

/**
 * A user-editable file category. Categories drive three things:
 *  - which sub-folder a finished download lands in when "As per file type" is picked,
 *  - the category list in the sidebar and the row icons in the downloads list,
 *  - nothing in xdm-core: the engine only carries the `autoCategorize` flag and
 *    [xdm.app.DownloadManager] resolves the folder here.
 *
 * The five [defaults] ship with the app and can be edited or deleted like any other;
 * [predefined] only tells "Restore defaults" what to put back.
 */
data class DownloadCategory(
    /** Stable key: the language-file key for a built-in, a random id for a user category. */
    val id: String,
    /** Untranslated name. Doubles as the sub-folder name when [folder] is blank. */
    val name: String,
    /** Lowercase, dot-prefixed, e.g. `.mp4`. */
    val extensions: Set<String> = emptySet(),
    /** Absolute folder override. Blank means `<base folder>/<name>`. */
    val folder: String = "",
    val predefined: Boolean = false,
    /** Name of a [xdm.app.utils.RemixIcon] constant; unknown names fall back to a file glyph. */
    val icon: String = "FILE_LINE",
) {
    /**
     * Translated name for an untouched built-in, the plain [name] otherwise. The language key
     * is derived from [id] rather than stored, so renaming a built-in drops the translation
     * (the typed name wins) and renaming it back restores it.
     */
    val displayName: String
        get() {
            if (defaults().none { it.id == id && it.name == name }) return name
            val translated: String? = I8N.text(id)
            return if (translated.isNullOrBlank()) name else translated
        }

    fun matches(fileName: String): Boolean =
        extensions.any { fileName.endsWith(it, ignoreCase = true) }

    /**
     * Where a file of this category goes given the base folder the user picked. A category
     * with an explicit [folder] always wins; otherwise the file lands in a sub-folder of
     * [baseFolder] so auto-categorization still composes with a non-default "Save in" choice.
     */
    fun folderFor(baseFolder: String): String =
        if (folder.isNotBlank()) folder else File(baseFolder, name).absolutePath

    companion object {
        /**
         * The built-in categories. Extension lists and sub-folder names match what the app
         * used before categories became editable, so upgrading changes no download's path.
         */
        fun defaults(): List<DownloadCategory> = listOf(
            DownloadCategory(
                id = "CAT_DOCUMENTS",
                name = "Documents",
                extensions = setOf(".pdf", ".docx", ".doc", ".ppt", ".pptx", ".odt", ".odf"),
                predefined = true,
                icon = "FILE_LIST_2_FILL",
            ),
            DownloadCategory(
                id = "CAT_COMPRESSED",
                name = "Compressed",
                extensions = setOf(".zip", ".rar", ".7z", ".gz", ".tar", ".tgz", ".tz", ".bz2", ".xz"),
                predefined = true,
                icon = "FILE_ZIP_FILL",
            ),
            DownloadCategory(
                id = "CAT_MUSIC",
                name = "Music",
                extensions = setOf(".mp3", ".aac", ".wav", ".ac3"),
                predefined = true,
                icon = "MV_FILL",
            ),
            DownloadCategory(
                id = "CAT_VIDEOS",
                name = "Video",
                extensions = setOf(".ts", ".mp4", ".mkv", ".webm", ".avi"),
                predefined = true,
                icon = "MOVIE_FILL",
            ),
            DownloadCategory(
                id = "CAT_PROGRAMS",
                name = "Programs",
                extensions = setOf(".exe", ".msi", ".msix", ".deb", ".dmg", ".rpm", ".iso", ".pkg", ".sh", ".py"),
                predefined = true,
                icon = "MICROSOFT_FILL",
            ),
        )

        /**
         * Parses a free-form extension list ("mp4, *.mkv; AVI") into the canonical
         * lowercase dot-prefixed form, preserving the order the user typed.
         */
        fun parseExtensions(raw: String): Set<String> =
            raw.split(',', ';', ' ', '\n', '\t', '\r')
                .map { it.trim().removePrefix("*").lowercase() }
                .filter { it.isNotBlank() && it != "." }
                .map { if (it.startsWith(".")) it else ".$it" }
                .toCollection(LinkedHashSet())

        /** Renders [extensions] back into the comma-separated form shown in the editor. */
        fun formatExtensions(extensions: Set<String>): String = extensions.joinToString(", ")
    }
}
