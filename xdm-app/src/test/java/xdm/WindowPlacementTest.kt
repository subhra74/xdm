package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.utils.SavedGeometry
import xdm.app.utils.ScreenInfo
import xdm.app.utils.WindowGeometry
import xdm.app.utils.WindowPlacement
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Restoring a saved window onto whatever monitors are attached now. */
class WindowPlacementTest {
    private fun screen(id: String, x: Int, y: Int, w: Int, h: Int, scale: Double = 1.0, taskbar: Int = 40) =
        ScreenInfo(id, Rectangle(x, y, w, h), Rectangle(x, y, w, h - taskbar), scale)

    private val left = screen("\\Display0", 0, 0, 1920, 1080)
    private val right = screen("\\Display1", 1920, 0, 2560, 1440)

    private fun savedOn(s: ScreenInfo, normal: Rectangle, uiScale: Float = 1f, maximized: Boolean = false) =
        SavedGeometry(s.id, Rectangle(s.bounds), s.scale, uiScale, normal, maximized)

    @Test
    fun unchangedLayoutRestoresExactly() {
        val saved = savedOn(right, Rectangle(2100, 100, 1000, 700))
        val target = WindowPlacement.pickScreen(saved, listOf(left, right), left)
        assertEquals(right, target, "same monitor")
        assertEquals(Rectangle(2100, 100, 1000, 700), WindowPlacement.place(saved, target, 1f), "same bounds")
    }

    @Test
    fun unpluggedMonitorFallsBackToPrimary() {
        val saved = savedOn(right, Rectangle(2100, 100, 1000, 700))
        val target = WindowPlacement.pickScreen(saved, listOf(left), left)
        assertEquals(left, target, "primary screen")
        val r = WindowPlacement.place(saved, target, 1f)
        assertTrue(left.workArea.contains(r), "window inside the remaining monitor: $r")
        assertEquals(Rectangle(180, 100, 1000, 700), r, "offset from the screen corner kept")
    }

    @Test
    fun renumberedMonitorIsFoundByItsBounds() {
        val saved = savedOn(right, Rectangle(2100, 100, 1000, 700))
        val renumbered = right.copy(id = "\\Display2")
        assertEquals(renumbered, WindowPlacement.pickScreen(saved, listOf(left, renumbered), left), "matched by bounds")
    }

    @Test
    fun movedMonitorKeepsTheWindowOnIt() {
        // The second monitor now sits to the left of the first.
        val saved = savedOn(right, Rectangle(2100, 100, 1000, 700))
        val moved = right.copy(bounds = Rectangle(-2560, 0, 2560, 1440), workArea = Rectangle(-2560, 0, 2560, 1400))
        val target = WindowPlacement.pickScreen(saved, listOf(left, moved), left)
        assertEquals(moved, target, "same device id")
        assertEquals(Rectangle(-2380, 100, 1000, 700), WindowPlacement.place(saved, target, 1f), "same offset on the moved monitor")
    }

    @Test
    fun windowLargerThanTheScreenIsShrunkAndPulledIn() {
        val saved = savedOn(right, Rectangle(2000, 50, 2400, 1400))
        val r = WindowPlacement.place(saved, left, 1f)
        assertEquals(Rectangle(0, 0, 1920, 1040), r, "shrunk to the work area")
    }

    @Test
    fun partlyOffScreenWindowIsMovedBackIn() {
        val r = WindowPlacement.fitInto(Rectangle(1500, 900, 800, 600), left.workArea, 400, 250)
        assertEquals(Rectangle(1120, 440, 800, 600), r, "right and bottom edges inside")
        val above = WindowPlacement.fitInto(Rectangle(-300, -200, 800, 600), left.workArea, 400, 250)
        assertEquals(Rectangle(0, 0, 800, 600), above, "title bar reachable")
    }

    @Test
    fun tinyWindowGetsTheMinimumSize() {
        val r = WindowPlacement.fitInto(Rectangle(10, 10, 50, 20), left.workArea, 400, 250)
        assertEquals(Rectangle(10, 10, 400, 250), r, "minimum size")
    }

    @Test
    fun screenScaleChangeKeepsThePhysicalOffset() {
        // Same 3840x2160 panel, 100% when saved, 200% now: user space halves.
        val at100 = screen("\\Display0", 0, 0, 3840, 2160, scale = 1.0)
        val at200 = screen("\\Display0", 0, 0, 1920, 1080, scale = 2.0)
        val saved = savedOn(at100, Rectangle(800, 400, 1000, 600))
        assertEquals(Rectangle(400, 200, 1000, 600), WindowPlacement.place(saved, at200, 1f), "offset halved, size in user space kept")
    }

    @Test
    fun uiScaleChangeConvertsTheSize() {
        // Fractional X11: Java2D at 1x, FlatLaf at 1.5 when saved, 1.0 now.
        val saved = savedOn(left, Rectangle(100, 100, 1200, 750), uiScale = 1.5f)
        assertEquals(Rectangle(100, 100, 800, 500), WindowPlacement.place(saved, left, 1f), "size follows the UI scale")
    }

    @Test
    fun windowOnNoKnownScreenUsesTheLargestOverlap() {
        val saved = SavedGeometry("gone", Rectangle(5000, 0, 1920, 1080), 1.0, 1f, Rectangle(1800, 100, 800, 600), false)
        assertEquals(right, WindowPlacement.pickScreen(saved, listOf(left, right), left), "mostly on the right monitor")
    }

    @Test
    fun maximizedBoundsAreNotTakenAsNormal() {
        assertTrue(WindowPlacement.fills(Rectangle(-8, -8, 1936, 1056), left.workArea), "Windows maximized frame")
        assertFalse(WindowPlacement.fills(Rectangle(0, 0, 1200, 800), left.workArea), "normal window")
    }

    @Test
    fun fileRoundTripsAndRejectsOtherVersions() {
        val records = mapOf(
            "main" to savedOn(right, Rectangle(2100, 100, 1000, 700), uiScale = 1.25f, maximized = true),
        )
        val bytes = ByteArrayOutputStream().also { WindowGeometry.encode(DataOutputStream(it), records) }.toByteArray()
        assertEquals(records, WindowGeometry.decode(DataInputStream(ByteArrayInputStream(bytes))), "round trip")

        bytes[3] = 99
        assertEquals(emptyMap<String, SavedGeometry>(), WindowGeometry.decode(DataInputStream(ByteArrayInputStream(bytes))), "unknown version")
    }
}
