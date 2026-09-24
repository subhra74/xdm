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
     * Fill glyphs mapped to their outlined twin. A category stores one icon name, and the two
     * places that draw it disagree on style: the download list wants the filled glyph, the
     * filter sidebar the outlined one. Categories are stored filled and the sidebar translates
     * on the way out, so nothing on disk has to change.
     *
     * This map is also the source of truth for [PICKABLE_ICONS]: an icon is only offered if it
     * appears here, so the user can never pick a glyph that has no outlined counterpart and
     * would fall back to its filled self in the sidebar.
     */
    private val LINE_VARIANTS: Map<RemixIcon, RemixIcon> = linkedMapOf(
        RemixIcon.FILE_FILL to RemixIcon.FILE_LINE,
        RemixIcon.FILE_LIST_2_FILL to RemixIcon.FILE_LIST_2_LINE,
        RemixIcon.FILE_ZIP_FILL to RemixIcon.FILE_ZIP_LINE,
        RemixIcon.MV_FILL to RemixIcon.MV_LINE,
        RemixIcon.MOVIE_FILL to RemixIcon.MOVIE_LINE,
        RemixIcon.MICROSOFT_FILL to RemixIcon.MICROSOFT_LINE,
        RemixIcon.ARCHIVE_2_FILL to RemixIcon.ARCHIVE_2_LINE,
        RemixIcon.FILE_TEXT_FILL to RemixIcon.FILE_TEXT_LINE,
        RemixIcon.FILE_SHIELD_FILL to RemixIcon.FILE_SHIELD_LINE,
        RemixIcon.FOLDER_FILL to RemixIcon.FOLDER_LINE,
        RemixIcon.GLOBAL_FILL to RemixIcon.GLOBAL_LINE,
        RemixIcon.SPARKLING_2_FILL to RemixIcon.SPARKLING_2_LINE,
    )

    /**
     * Glyphs a user may pick for their own categories, in the order the editor shows them.
     * Derived from [LINE_VARIANTS] so every choice is guaranteed to have both styles.
     */
    val PICKABLE_ICONS: List<RemixIcon> = LINE_VARIANTS.keys.toList()

    /** The outlined twin of [icon], or [icon] itself when it has no filled/outlined pair. */
    fun lineVariant(icon: RemixIcon): RemixIcon = LINE_VARIANTS[icon] ?: icon

    /** Glyph used for a category; an unknown or stale icon name falls back to a plain file glyph. */
    fun iconName(category: DownloadCategory): RemixIcon =
        runCatching { RemixIcon.valueOf(category.icon) }.getOrDefault(RemixIcon.FILE_FILL)
}
