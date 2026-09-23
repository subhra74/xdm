package xdm.app.ui.components

import java.awt.Color
import javax.swing.UIManager
import xdm.app.DownloadCategory
import xdm.app.utils.RemixIcon

/**
 * Shared visual palette for file-category icons. Vibrant, mid-luminance hues
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

    /**
     * Glyphs a user may pick for their own categories, in the order the editor shows them.
     * Keep every constant here present in [RemixIcon].
     */
    val PICKABLE_ICONS = listOf(
        RemixIcon.FILE_LINE,
        RemixIcon.FILE_LIST_2_FILL,
        RemixIcon.FILE_ZIP_FILL,
        RemixIcon.MV_FILL,
        RemixIcon.MOVIE_FILL,
        RemixIcon.MICROSOFT_FILL,
        RemixIcon.ARCHIVE_2_FILL,
        RemixIcon.FILE_TEXT_LINE,
        RemixIcon.FILE_SHIELD_LINE,
        RemixIcon.FOLDER_FILL,
        RemixIcon.GLOBAL_FILL,
        RemixIcon.SPARKLING_2_FILL,
    )

    /** Glyph used for a category; an unknown or stale icon name falls back to a plain file glyph. */
    fun iconName(category: DownloadCategory): RemixIcon =
        runCatching { RemixIcon.valueOf(category.icon) }.getOrDefault(RemixIcon.FILE_LINE)
}
