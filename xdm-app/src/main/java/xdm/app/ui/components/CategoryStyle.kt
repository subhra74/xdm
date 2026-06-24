package xdm.app.ui.components

import java.awt.Color
import javax.swing.UIManager

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

    /** Solid accent color for a category (icon glyph color in the sidebar). */
    fun color(category: FilterCategory): Color = when (category) {
        FilterCategory.All -> VIOLET
        FilterCategory.Docs -> SKY
        FilterCategory.Zip -> ORANGE
        FilterCategory.Music -> PINK
        FilterCategory.Video -> RED
        FilterCategory.Apps -> TEAL
    }

    /** Glyph used for a category. */
    fun iconName(category: FilterCategory): String = when (category) {
        FilterCategory.All -> "archive-2-fill.svg"
        FilterCategory.Docs -> "file-list-2-fill.svg"
        FilterCategory.Zip -> "file-zip-fill.svg"
        FilterCategory.Music -> "mv-fill.svg"
        FilterCategory.Video -> "movie-fill.svg"
        FilterCategory.Apps -> "microsoft-fill.svg"
    }

    /**
     * Subtle tinted background for the rounded icon badge in list rows.
     * A low-alpha wash of the accent color reads well on both themes and is
     * far softer than a saturated solid fill.
     */
    fun badgeBackground(category: FilterCategory): Color {
        val c = color(category)
        return Color(c.red, c.green, c.blue, 0x33)
    }
}
