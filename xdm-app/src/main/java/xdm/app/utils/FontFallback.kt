package xdm.app.utils

import java.awt.Font
import javax.swing.text.StyleContext

/**
 * Glyph fallback for a physical font, through Swing's own composite fonts.
 *
 * A font built with the [Font] constructor is one physical font: every character it has no glyph
 * for is drawn as the missing-glyph box. FlatLaf builds its Linux default fonts as composite fonts
 * already, but the desktop portal's font (DesktopFontSettings, native Wayland) is installed as a
 * raw one: with Noto Sans, or another Latin-only desktop font, every Chinese character in the
 * window and in file names came out as a box.
 *
 * [StyleContext.getFont] returns a composite font instead: it draws the characters the family has
 * from that family and the rest from other installed fonts. FlatLaf uses the same helper for the
 * Windows and macOS system fonts.
 */
internal object FontFallback {
    /** [font]'s family, style and size, plus fallback for the glyphs that family lacks. */
    fun composite(font: Font): Font =
        StyleContext.getDefaultStyleContext().getFont(font.family, font.style, font.size)
}
