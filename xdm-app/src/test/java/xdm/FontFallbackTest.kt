package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import xdm.app.utils.FontFallback
import java.awt.Font
import java.awt.GraphicsEnvironment

/** The desktop font's glyph fallback (the "Chinese text is drawn as boxes" fix). */
class FontFallbackTest {

    @Test
    fun composite_keepsFamilyStyleAndSize() {
        val font = Font(Font.SANS_SERIF, Font.BOLD, 17)
        val composite = FontFallback.composite(font)
        assertEquals(font.family, composite.family, "family")
        assertEquals(font.style, composite.style, "style")
        assertEquals(font.size, composite.size, "size")
    }

    @Test
    fun composite_fallsBackToAnotherFamilyForCjk() {
        // The reported bug: a Chinese file name in a Latin-only desktop font. Which fonts are
        // installed is an environment property, so this only runs where a Latin-only family and
        // some CJK fallback font both exist.
        val cjk = "\u4E2D\u6587\u4E0B\u8F7D"
        val latinOnly = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
            .asSequence()
            .map { Font(it, Font.PLAIN, 13) }
            .firstOrNull { it.canDisplayUpTo(cjk) >= 0 && it.canDisplayUpTo("Download") == -1 }
        assumeTrue(latinOnly != null, "no Latin-only physical font installed")
        assumeTrue(
            Font(Font.SANS_SERIF, Font.PLAIN, 13).canDisplayUpTo(cjk) == -1,
            "no CJK fallback font installed",
        )

        val composite = FontFallback.composite(latinOnly!!)
        assertEquals(-1, composite.canDisplayUpTo(cjk), "composite font should fall back to the CJK font")
    }
}
