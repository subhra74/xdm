package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import xdm.app.utils.linux.DesktopFontSettings
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints

/** The desktop portal's font settings, as the native Wayland toolkit gets them (PACKAGING.md 7.6). */
class DesktopFontSettingsTest {
    @Test
    fun fontName_splitsFamilyAndPoints() {
        assertEquals("Ubuntu Sans" to 11.0, DesktopFontSettings.parseFontName("Ubuntu Sans 11"), "family with a space")
        assertEquals("Cantarell" to 10.5, DesktopFontSettings.parseFontName(" Cantarell 10.5 "), "fractional size")
        assertNull(DesktopFontSettings.parseFontName("Cantarell"), "no size")
        assertNull(DesktopFontSettings.parseFontName("11"), "no family")
    }

    @Test
    fun toFont_keepsGlyphFallbackForALatinOnlyFamily() {
        // The reported bug (a Chinese file name in Noto Sans): the portal font must be a composite,
        // or every CJK character it lacks is drawn as a box. Skips where the fonts don't exist.
        val cjk = "\u4E2D\u6587\u4E0B\u8F7D"
        assumeTrue(
            Font(Font.SANS_SERIF, Font.PLAIN, 13).canDisplayUpTo(cjk) == -1,
            "no CJK fallback font installed",
        )

        // Any Latin-only installed family reproduces the bug. Skip families Java cannot build from
        // the portal's name (toFont then returns null and FlatLaf's own font is used instead).
        val candidate = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
            .asSequence()
            .mapNotNull { family ->
                val raw = Font(family, Font.PLAIN, 13)
                if (raw.canDisplayUpTo(cjk) < 0 || raw.canDisplayUpTo("Download") >= 0) return@mapNotNull null
                DesktopFontSettings.toFont("$family 11", 1.0)?.let { family to it }
            }
            .firstOrNull()
        assumeTrue(candidate != null, "no Latin-only font family Java can load")

        val (family, font) = candidate!!
        assertEquals(family, font.family, "family")
        assertEquals(-1, font.canDisplayUpTo(cjk), "composite font should fall back to the CJK font")
    }

    @Test
    fun antialiasing_followsGnomeModeAndSubpixelOrder() {
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, DesktopFontSettings.textAntialiasing("rgba", "rgb"), "subpixel rgb")
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR, DesktopFontSettings.textAntialiasing("rgba", "bgr"), "subpixel bgr")
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON, DesktopFontSettings.textAntialiasing("grayscale", "rgb"), "grayscale")
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_OFF, DesktopFontSettings.textAntialiasing("none", null), "off")
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON, DesktopFontSettings.textAntialiasing(null, null), "unknown: grayscale")
    }
}
