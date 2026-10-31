package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.AppMain

/** "system" follows the OS (light when it has no answer); dark and light ignore it. */
class ThemeResolveTest {
    @Test
    fun system_followsTheOs() {
        assertTrue(AppMain.resolveDark("system") { true }, "OS dark")
        assertFalse(AppMain.resolveDark("system") { false }, "OS light")
        assertFalse(AppMain.resolveDark("system") { null }, "OS has no answer")
    }

    @Test
    fun explicitChoice_neverAsksTheOs() {
        var asked = 0
        val os = { asked++; true }
        assertTrue(AppMain.resolveDark("dark", os), "dark")
        assertFalse(AppMain.resolveDark("light", os), "light")
        assertTrue(AppMain.resolveDark("unknown", os), "unknown value keeps the old dark default")
        assertEquals(0, asked, "OS not read")
    }
}
