package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.utils.WindowsTrayMenu
import java.awt.Point
import java.awt.Rectangle

/** Where the Windows tray menu opens, for each taskbar edge (1920x1080 screen, 48 px taskbar). */
class WindowsTrayMenuTest {
    private val w = 150
    private val h = 70

    @Test
    fun bottomTaskbarCentersAboveTheIcon() {
        val area = Rectangle(0, 0, 1920, 1032)
        assertEquals(Point(1325, 954), WindowsTrayMenu.menuLocation(Point(1400, 1056), w, h, area), "centered, 8 px above the taskbar")
    }

    @Test
    fun iconNearTheCornerStaysOnScreen() {
        val area = Rectangle(0, 0, 1920, 1032)
        assertEquals(Point(1762, 954), WindowsTrayMenu.menuLocation(Point(1900, 1056), w, h, area), "8 px from the right edge")
    }

    @Test
    fun otherTaskbarEdges() {
        assertEquals(Point(1325, 56), WindowsTrayMenu.menuLocation(Point(1400, 20), w, h, Rectangle(0, 48, 1920, 1032)), "top")
        assertEquals(Point(56, 965), WindowsTrayMenu.menuLocation(Point(20, 1000), w, h, Rectangle(48, 0, 1872, 1080)), "left")
        assertEquals(Point(1714, 965), WindowsTrayMenu.menuLocation(Point(1900, 1000), w, h, Rectangle(0, 0, 1872, 1080)), "right")
    }

    @Test
    fun overflowFlyoutOpensAboveThePointer() {
        val area = Rectangle(0, 0, 1920, 1032)
        assertEquals(Point(1325, 892), WindowsTrayMenu.menuLocation(Point(1400, 970), w, h, area), "above the flyout icon")
    }
}
