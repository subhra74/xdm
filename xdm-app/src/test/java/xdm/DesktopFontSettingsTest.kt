package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.app.utils.linux.DesktopFontSettings
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
    fun antialiasing_followsGnomeModeAndSubpixelOrder() {
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, DesktopFontSettings.textAntialiasing("rgba", "rgb"), "subpixel rgb")
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR, DesktopFontSettings.textAntialiasing("rgba", "bgr"), "subpixel bgr")
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON, DesktopFontSettings.textAntialiasing("grayscale", "rgb"), "grayscale")
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_OFF, DesktopFontSettings.textAntialiasing("none", null), "off")
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON, DesktopFontSettings.textAntialiasing(null, null), "unknown: grayscale")
    }
}
