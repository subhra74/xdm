package xdm.app.ui.components

import java.awt.Color
import javax.swing.UIManager
import xdm.app.utils.RemixIcon

/**
 * Shared palette and file-type glyphs. Vibrant, mid-luminance hues
 * (Tailwind 500-ish) that keep good contrast on both light and dark themes.
 * Used by the sidebar filter list and the main download-list rows so a given
 * category looks identical in both places.
 */
object CategoryStyle {
    val INDIGO = Color(0x63, 0x66, 0xF1)
    val EMERALD = Color(0x10, 0xB9, 0x81)
    val AMBER = Color(0xF5, 0x9E, 0x0B)
    val VIOLET = Color(0x8B, 0x5C, 0xF6)
    val SKY = Color(0x0E, 0xA5, 0xE9)
    val ORANGE = Color(0xF9, 0x73, 0x16)
    val PINK = Color(0xEC, 0x48, 0x99)
    val RED = Color(0xEF, 0x44, 0x44)
    val TEAL = Color(0x14, 0xB8, 0xA6)

    // Semantic colors for toolbar / row action icons.
    val ACCENT = INDIGO            // primary action (new download)
    val SUCCESS = EMERALD          // resume / play
    val WARN = AMBER               // pause
    val DANGER = RED               // delete

    /**
     * Theme-aware neutral icon color. Uses the look-and-feel's full-strength
     * label foreground so icons stay crisp and high-contrast on both light and
     * dark themes, instead of a fixed mid-gray that washes out.
     */
    fun neutralIcon(): Color = UIManager.getColor("Label.foreground") ?: Color.GRAY

    /** Glyph used for the "All types" sidebar row, which has no category behind it. */
    val ALL_ICON = RemixIcon.ARCHIVE_2_FILL

    /** Fill glyphs mapped to their outlined twin, for places that draw a file glyph outlined. */
    private val LINE_VARIANTS: Map<RemixIcon, RemixIcon> = linkedMapOf(
        RemixIcon.FILE_FILL to RemixIcon.FILE_LINE,
        RemixIcon.FILE_LIST_2_FILL to RemixIcon.FILE_LIST_2_LINE,
        RemixIcon.FILE_ZIP_FILL to RemixIcon.FILE_ZIP_LINE,
        RemixIcon.MV_FILL to RemixIcon.MV_LINE,
        RemixIcon.MOVIE_FILL to RemixIcon.MOVIE_LINE,
        RemixIcon.MICROSOFT_FILL to RemixIcon.MICROSOFT_LINE,
        RemixIcon.ARCHIVE_2_FILL to RemixIcon.ARCHIVE_2_LINE,
    )

    /** The outlined twin of [icon], or [icon] itself when it has no filled/outlined pair. */
    fun lineVariant(icon: RemixIcon): RemixIcon = LINE_VARIANTS[icon] ?: icon

    /**
     * File-type glyphs by extension. A download's glyph comes from its file name alone, never from its
     * category (categories only have a color).
     */
    private val GLYPHS: Map<String, RemixIcon> = buildMap {
        fun put(glyph: RemixIcon, vararg exts: String) = exts.forEach { put(it, glyph) }
        put(RemixIcon.MOVIE_FILL, "mp4", "mkv", "webm", "avi", "ts", "mov", "m4v", "wmv", "flv", "mpg", "mpeg", "3gp", "ogv")
        put(RemixIcon.MV_FILL, "mp3", "aac", "wav", "ac3", "m4a", "flac", "ogg", "oga", "opus", "wma")
        put(RemixIcon.FILE_ZIP_FILL, "zip", "rar", "7z", "gz", "tar", "tgz", "tz", "bz2", "xz", "zst", "cab")
        put(RemixIcon.FILE_LIST_2_FILL, "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "odt", "odf", "ods", "odp", "rtf", "txt", "epub")
        put(RemixIcon.MICROSOFT_FILL, "exe", "msi", "msix", "deb", "dmg", "rpm", "iso", "pkg", "sh", "py", "apk", "xapk", "appimage", "jar", "img")
    }

    /** The glyph for a file of this name, from its extension; a plain file glyph when it is unknown. */
    fun glyphForFile(fileName: String): RemixIcon =
        GLYPHS[fileName.substringAfterLast('.', "").lowercase()] ?: RemixIcon.FILE_FILL
}
