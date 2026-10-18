package xdm.app.utils

import java.awt.Rectangle
import kotlin.math.roundToInt

/** One monitor as AWT reports it: [bounds] and [workArea] (bounds minus taskbar/dock/panels) in user space. */
internal data class ScreenInfo(
    /** `GraphicsDevice.getIDstring()`: `\Display0` on Windows, the CGDirectDisplayID on macOS, `:0.0` on X11. */
    val id: String,
    val bounds: Rectangle,
    val workArea: Rectangle,
    /** Java2D device scale (`defaultTransform.scaleX`): 1.0 at 100%, 1.5 at 150%, 2.0 on Retina. */
    val scale: Double,
)

/** A window's remembered geometry, plus the screen it was on when it was saved. */
internal data class SavedGeometry(
    val screenId: String,
    val screenBounds: Rectangle,
    val screenScale: Double,
    /** FlatLaf's UI scale at save time; above 1 only on a fractional X11 scale (see `X11UiScale`). */
    val uiScale: Float,
    /** The un-maximized bounds, frame included. */
    val normal: Rectangle,
    val maximized: Boolean,
)

/**
 * Where a saved window goes on the current monitor layout. Modelled on Qt's
 * `QWidget::restoreGeometry`: put the window back on the screen it was saved on (falling back to
 * another screen when that one is gone), then pull it inside the screen's work area so it can never
 * come back off-screen or larger than the screen (Qt's `checkRestoredGeometry`).
 *
 * Unlike Qt, a window whose screen moved, changed resolution or changed scale keeps its offset from
 * that screen's corner rather than its absolute desktop coordinates, so rearranging monitors does
 * not throw it onto a neighbour.
 */
internal object WindowPlacement {
    /** The window keeps at least this much (in 100% pixels) when it is shrunk to fit a screen. */
    const val MIN_WIDTH = 400
    const val MIN_HEIGHT = 250

    /**
     * The screen to restore on, most specific match first:
     * 1. the same device at the same position and size - nothing changed;
     * 2. a device at exactly the old position and size - the OS renumbered the monitors;
     * 3. the same device id - it changed resolution or moved in the arrangement;
     * 4. the screen the window's saved rectangle overlaps most;
     * 5. [primary], when the window would land on no screen at all (its monitor was unplugged).
     */
    fun pickScreen(saved: SavedGeometry, screens: List<ScreenInfo>, primary: ScreenInfo): ScreenInfo =
        screens.firstOrNull { it.id == saved.screenId && it.bounds == saved.screenBounds }
            ?: screens.firstOrNull { it.bounds == saved.screenBounds }
            ?: screens.firstOrNull { it.id == saved.screenId }
            ?: screenFor(saved.normal, screens)
            ?: primary

    /** The screen [r] overlaps most, or null if it overlaps none. */
    fun screenFor(r: Rectangle, screens: List<ScreenInfo>): ScreenInfo? =
        screens.map { it to overlap(r, it.bounds) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

    /**
     * The normal bounds to apply on [target]. [currentUiScale] is FlatLaf's scale now.
     *
     * Sizes are content-relative: AWT user space already follows each screen's DPI, and on a fractional
     * X11 scale (Java2D at 1x, FlatLaf scaling) the size is converted by the change in UI scale, so a
     * window that fit its content at 150% still fits it at 100%. The offset from the screen's corner
     * keeps its physical distance when the screen's Java2D scale changed.
     */
    fun place(saved: SavedGeometry, target: ScreenInfo, currentUiScale: Float): Rectangle {
        val uiRatio = if (saved.uiScale > 0f) currentUiScale / saved.uiScale else 1f
        val w = (saved.normal.width * uiRatio).roundToInt()
        val h = (saved.normal.height * uiRatio).roundToInt()
        val unchanged = target.bounds == saved.screenBounds && target.scale == saved.screenScale
        val r = if (unchanged) {
            Rectangle(saved.normal.x, saved.normal.y, w, h)
        } else {
            val k = if (target.scale > 0) saved.screenScale / target.scale else 1.0
            Rectangle(
                target.bounds.x + ((saved.normal.x - saved.screenBounds.x) * k).roundToInt(),
                target.bounds.y + ((saved.normal.y - saved.screenBounds.y) * k).roundToInt(),
                w, h,
            )
        }
        return fitInto(r, target.workArea, (MIN_WIDTH * currentUiScale).roundToInt(), (MIN_HEIGHT * currentUiScale).roundToInt())
    }

    /**
     * Qt's `checkRestoredGeometry`: shrink [r] to the work area if it is larger, then, if any edge is
     * outside, move it back in. AWT bounds include the title bar, so unlike Qt there is no frame
     * height to add. The window is never made smaller than [minWidth] x [minHeight] unless the
     * work area itself is smaller.
     */
    fun fitInto(r: Rectangle, area: Rectangle, minWidth: Int, minHeight: Int): Rectangle {
        val w = r.width.coerceAtLeast(minWidth).coerceAtMost(area.width)
        val h = r.height.coerceAtLeast(minHeight).coerceAtMost(area.height)
        val x = r.x.coerceAtMost(area.x + area.width - w).coerceAtLeast(area.x)
        val y = r.y.coerceAtMost(area.y + area.height - h).coerceAtLeast(area.y)
        return Rectangle(x, y, w, h)
    }

    /**
     * Whether [r] covers [area] entirely: a maximized or full-screen window. Some X11 window managers
     * report the maximized size before the state change, and macOS full screen is not a frame state
     * at all, so such bounds are never remembered as the normal size.
     */
    fun fills(r: Rectangle, area: Rectangle): Boolean =
        r.width >= area.width && r.height >= area.height

    /** [saved]'s normal bounds moved onto [target], keeping the offset from the screen's corner. */
    fun moveTo(saved: SavedGeometry, target: ScreenInfo, uiScale: Float): SavedGeometry =
        saved.copy(
            screenId = target.id, screenBounds = Rectangle(target.bounds), screenScale = target.scale,
            uiScale = uiScale, normal = place(saved, target, uiScale),
        )

    private fun overlap(a: Rectangle, b: Rectangle): Long {
        val i = a.intersection(b)
        return if (i.isEmpty) 0 else i.width.toLong() * i.height
    }
}
