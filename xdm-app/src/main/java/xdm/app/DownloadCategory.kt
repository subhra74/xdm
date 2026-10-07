package xdm.app

import java.io.File

/**
 * A user-editable file category. Categories drive three things:
 *  - which folder a finished download lands in when "Automatic (by file type)" is picked,
 *  - the category list in the sidebar and the row icons in the downloads list,
 *  - nothing in xdm-core: the engine only carries the `autoCategorize` flag and
 *    [xdm.app.DownloadManager] resolves the folder here.
 *
 * Every category owns a concrete [folder]. It is decided once — when the category is
 * created, or when the built-ins are first seeded from the download folder — and nothing
 * moves it afterwards: renaming a category does not touch its folder, and changing the
 * default download folder does not either. Only editing the folder itself does.
 *
 * The five built-ins ([defaults]) can be edited or deleted like any other; [predefined]
 * only tells "Restore defaults" what to put back.
 */
data class DownloadCategory(
    /** Stable key: the language-file key for a built-in, a random id for a user category. */
    val id: String,
    /** Untranslated name; shown as-is once a built-in has been renamed. */
    val name: String,
    /** Lowercase, dot-prefixed, e.g. `.mp4`. */
    val extensions: Set<String>,
    /** Absolute folder this category's downloads land in. */
    val folder: String,
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
            if (defaultNameOf(id) != name) return name
            val translated: String? = I8N.text(id)
            return if (translated.isNullOrBlank()) name else translated
        }

    fun matches(fileName: String): Boolean =
        extensions.any { fileName.endsWith(it, ignoreCase = true) }

    companion object {
        /** Everything about a built-in except the folder, which depends on where it is seeded. */
        private class BuiltIn(
            val id: String,
            val name: String,
            val icon: String,
            val extensions: Set<String>,
        )

        /**
         * The built-in categories. Names, extension lists and icons match what the app used
         * before categories became editable, so seeding them changes no download's path.
         */
        private val BUILT_INS = listOf(
            BuiltIn(
                "CAT_DOCUMENTS", "Documents", "FILE_LIST_2_FILL",
                setOf(".pdf", ".docx", ".doc", ".ppt", ".pptx", ".odt", ".odf")
            ),
            BuiltIn(
                "CAT_COMPRESSED", "Compressed", "FILE_ZIP_FILL",
                setOf(".zip", ".rar", ".7z", ".gz", ".tar", ".tgz", ".tz", ".bz2", ".xz")
            ),
            BuiltIn(
                "CAT_MUSIC", "Music", "MV_FILL",
                setOf(".mp3", ".aac", ".wav", ".ac3")
            ),
            BuiltIn(
                "CAT_VIDEOS", "Video", "MOVIE_FILL",
                setOf(".ts", ".mp4", ".mkv", ".webm", ".avi")
            ),
            BuiltIn(
                "CAT_PROGRAMS", "Programs", "MICROSOFT_FILL",
                setOf(".exe", ".msi", ".msix", ".deb", ".dmg", ".rpm", ".iso", ".pkg", ".sh", ".py")
            ),
        )

        /**
         * Seeds the built-in categories under [baseFolder], each in a sub-folder named after
         * it. This is the only point where the download folder feeds into a category: the
         * paths are captured here and then stand on their own.
         */
        fun defaults(baseFolder: String): List<DownloadCategory> = BUILT_INS.map {
            DownloadCategory(
                id = it.id,
                name = it.name,
                extensions = it.extensions,
                folder = File(baseFolder, it.name).absolutePath,
                predefined = true,
                icon = it.icon,
            )
        }

        /** The shipped name for a built-in id, or null when [id] is not a built-in. */
        fun defaultNameOf(id: String): String? = BUILT_INS.firstOrNull { it.id == id }?.name

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
