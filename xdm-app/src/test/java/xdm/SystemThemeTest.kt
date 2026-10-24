package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.app.utils.SystemTheme

/** The OS light/dark preference that picks the first run's theme. */
class SystemThemeTest {
    @Test
    fun windows_appsUseLightTheme() {
        assertEquals(true, SystemTheme.windowsIsDark(0), "0 = dark")
        assertEquals(false, SystemTheme.windowsIsDark(1), "1 = light")
        assertNull(SystemTheme.windowsIsDark(null), "missing value: unknown")
    }

    @Test
    fun mac_appleInterfaceStyle() {
        assertEquals(true, SystemTheme.macIsDark("Dark"), "Dark")
        assertEquals(false, SystemTheme.macIsDark(null), "absent in light mode")
    }

    @Test
    fun linux_portalColorScheme() {
        assertEquals(true, SystemTheme.portalIsDark(1) { "Yaru" }, "prefer dark wins over the theme name")
        assertEquals(false, SystemTheme.portalIsDark(2) { "Yaru-dark" }, "prefer light wins over the theme name")
    }

    @Test
    fun linux_noPreference_fallsBackToGtkThemeName() {
        assertEquals(true, SystemTheme.portalIsDark(0) { "Yaru-dark" }, "Ubuntu dark")
        assertEquals(true, SystemTheme.portalIsDark(null) { "Adwaita-Dark" }, "case-insensitive")
        assertNull(SystemTheme.portalIsDark(0) { "Yaru" }, "Ubuntu light: unknown, so the light fallback")
        assertNull(SystemTheme.portalIsDark(null) { null }, "no portal: unknown")
    }

    @Test
    fun isDark_neverThrows() {
        // Reads the real OS setting on whatever machine runs the tests; only the contract is checked.
        SystemTheme.isDark()
    }
}
