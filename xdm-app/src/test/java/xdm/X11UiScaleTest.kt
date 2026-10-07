package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.app.utils.linux.X11UiScale

/** `Xft.dpi` as KDE/GNOME publish it in the X resource database (`xrdb -query` format). */
class X11UiScaleTest {
    @Test
    fun readsXftDpiAmongOtherResources() {
        val resources = "Xcursor.size:\t30\nXcursor.theme:\tbreeze_cursors\nXft.antialias:\t1\nXft.dpi:\t120\nXft.rgba:\trgb\n"
        assertEquals(120.0, X11UiScale.parseXftDpi(resources), "Xft.dpi value")
    }

    @Test
    fun missingOrInvalidDpiIsNull() {
        assertNull(X11UiScale.parseXftDpi("Xft.antialias:\t1\n"), "no Xft.dpi line")
        assertNull(X11UiScale.parseXftDpi("Xft.dpi:\tabc\n"), "unparsable value")
        assertNull(X11UiScale.parseXftDpi("Xft.dpi:\t0\n"), "zero dpi")
    }
}
