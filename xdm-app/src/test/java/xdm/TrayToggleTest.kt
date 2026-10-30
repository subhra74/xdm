package xdm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.utils.trayToggleHides
import java.awt.Frame

/** Left click on the tray icon hides the window only when it is already up and in front. */
class TrayToggleTest {
    @Test
    fun hiddenWindowComesUp() {
        assertFalse(trayToggleHides(isVisible = false, isFocused = false, Frame.NORMAL), "hidden")
        assertFalse(trayToggleHides(isVisible = false, isFocused = false, Frame.ICONIFIED), "hidden minimized")
    }

    @Test
    fun minimizedWindowComesUp() {
        assertFalse(
            trayToggleHides(isVisible = true, isFocused = false, Frame.ICONIFIED),
            "minimized without focus"
        )
        assertFalse(
            trayToggleHides(isVisible = true, isFocused = true, Frame.ICONIFIED),
            "minimized keeps focus flag on some platforms"
        )
    }

    @Test
    fun windowBehindOthersComesUp() {
        assertFalse(
            trayToggleHides(isVisible = true, isFocused = false, Frame.NORMAL),
            "visible but behind"
        )
    }

    @Test
    fun focusedWindowHides() {
        assertTrue(
            trayToggleHides(isVisible = true, isFocused = true, Frame.NORMAL),
            "up and focused"
        )
        assertTrue(
            trayToggleHides(isVisible = true, isFocused = true, Frame.MAXIMIZED_BOTH),
            "maximized and focused"
        )
    }
}
