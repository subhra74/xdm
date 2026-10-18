package xdm.app

import java.io.File

/**
 * A user-editable download category (see docs/download-categories.md). Categories drive:
 *  - the folder a new download is saved to when the category is picked, or preselected by its rules
 *    ([urlPatterns], [fileNamePatterns], [extensions]; see [CategoryRules]) for a browser capture,
 *  - the category list in the sidebar (filtered by each download's stored category id) and the color
 *    of a download row's tile,
 *  - nothing in xdm-core: the engine only carries the download's opaque `categoryId`.
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
    /** Globs matched against the download URL and the page URL ([CategoryRules]). */
    val urlPatterns: List<String> = emptyList(),
    /** Globs matched against the file name ([CategoryRules]). */
    val fileNamePatterns: List<String> = emptyList(),
    /** False: any rule matching is enough. True: every non-empty rule kind must match. */
    val matchAll: Boolean = false,
    /** Absolute folder this category's downloads land in. */
    val folder: String,
    val predefined: Boolean = false,
    /**
     * The category's color as `0xRRGGBB`, picked by the user; null keeps the theme's default for it
     * (see [xdm.app.ui.components.AppTheme.categoryColor]). Categories have no icon: a download's glyph
     * comes from its file type.
     */
    val color: Int? = null,
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

    /** Whether [fileName] has one of [extensions]. Used for row icons; download matching is [CategoryRules]. */
    fun matches(fileName: String): Boolean =
        extensions.any { fileName.endsWith(it, ignoreCase = true) }

    /** True when the category has no rules at all: it is only ever picked by hand. */
    val hasNoRules: Boolean
        get() = extensions.isEmpty() && urlPatterns.isEmpty() && fileNamePatterns.isEmpty()

    companion object {
        /** Everything about a built-in except the folder, which depends on where it is seeded. */
        private class BuiltIn(
            val id: String,
            val name: String,
            val extensions: Set<String>,
        )

        /**
         * The built-in categories. Names and extension lists match what the app used
         * before categories became editable, so seeding them changes no download's path.
         */
        private val BUILT_INS = listOf(
            BuiltIn(
                "CAT_DOCUMENTS", "Documents",
                setOf(".pdf", ".docx", ".doc", ".ppt", ".pptx", ".odt", ".odf")
            ),
            BuiltIn(
                "CAT_COMPRESSED", "Compressed",
                setOf(".zip", ".rar", ".7z", ".gz", ".tar", ".tgz", ".tz", ".bz2", ".xz")
            ),
            BuiltIn(
                "CAT_MUSIC", "Music",
                setOf(".mp3", ".aac", ".wav", ".ac3")
            ),
            BuiltIn(
                "CAT_VIDEOS", "Video",
                setOf(".ts", ".mp4", ".mkv", ".webm", ".avi")
            ),
            BuiltIn(
                "CAT_PROGRAMS", "Programs",
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

        /** Splits a one-pattern-per-line field into its trimmed, non-blank patterns, in order. */
        fun parsePatterns(raw: String): List<String> =
            raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
