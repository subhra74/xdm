package xdm.app.utils.linux

import xdm.app.OS
import xdm.app.utils.detectOS
import xdm.app.utils.win.Ffm
import xdm.core.util.Logger
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * Fractional display scaling for the X11 toolkit (an X11 session, or XWayland).
 *
 * The desktop publishes its scale to X11 apps as `Xft.dpi` in the X resource database (KDE with
 * "Legacy applications: apply scaling themselves" writes 96 x scale; with "scaled by the system" it
 * writes 96 and stretches the window itself). JBR's X11 backend only scales by whole numbers: it
 * rounds `Xft.dpi / 96`, so 125% came out at 1x and 175% at 2x. For a fractional scale Java2D is
 * pinned to 1x here and FlatLaf scales the UI by the exact factor instead; the app's own pixel
 * sizes follow through `UIScale.scale` (see `Scale.kt`).
 *
 * Whole-number scales stay with Java2D, as does anything the user set: `-Dsun.java2d.uiScale`,
 * `-Dflatlaf.uiScale`, `GDK_SCALE` or `J2D_UISCALE`. Must run before AWT loads (the device scale is
 * fixed when the graphics environment starts) and before the look-and-feel is installed.
 */
internal object X11UiScale {

    fun apply() {
        if (detectOS() != OS.Linux) return
        if (System.getenv("DISPLAY").isNullOrBlank()) return
        val preset = listOf("sun.java2d.uiScale", "flatlaf.uiScale").firstOrNull { System.getProperty(it) != null }
            ?: listOf("GDK_SCALE", "J2D_UISCALE").firstOrNull { !System.getenv(it).isNullOrBlank() }
        if (preset != null) {
            Logger.info("Toolkit", "UI scale left to $preset")
            return
        }
        val dpi = runCatching { readXftDpi() }
            .onFailure { Logger.info("Toolkit", "Xft.dpi not readable: $it") }
            .getOrNull() ?: return
        val scale = dpi / 96.0
        if (scale <= 1.0 || scale == Math.floor(scale)) {
            Logger.info("Toolkit", "Xft.dpi $dpi: scale left to the toolkit")
            return
        }
        System.setProperty("sun.java2d.uiScale", "1")
        System.setProperty("flatlaf.uiScale", scale.toString())
        Logger.info("Toolkit", "Xft.dpi $dpi: Java2D at 1x, FlatLaf scaling by $scale")
    }

    /** `Xft.dpi` from the root window's RESOURCE_MANAGER, as `xrdb -query` prints it; null when unset. */
    private fun readXftDpi(): Double? {
        Arena.ofConfined().use { arena ->
            val x11 = SymbolLookup.libraryLookup("libX11.so.6", arena)
            // Display *XOpenDisplay(char *name); char *XResourceManagerString(Display *); int XCloseDisplay(Display *)
            val open = Ffm.downcall(x11, "XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS))
            val resources = Ffm.downcall(x11, "XResourceManagerString", FunctionDescriptor.of(ADDRESS, ADDRESS))
            val close = Ffm.downcall(x11, "XCloseDisplay", FunctionDescriptor.of(JAVA_INT, ADDRESS))

            val display = open.invoke(MemorySegment.NULL) as MemorySegment
            if (display == MemorySegment.NULL) return null
            try {
                val text = resources.invoke(display) as MemorySegment
                if (text == MemorySegment.NULL) return null
                return parseXftDpi(text.reinterpret(Long.MAX_VALUE).getString(0))
            } finally {
                close.invoke(display)
            }
        }
    }

    /** Finds `Xft.dpi:<whitespace>120` among the resource lines. */
    fun parseXftDpi(resources: String): Double? =
        resources.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Xft.dpi:") }
            ?.substringAfter(':')?.trim()?.toDoubleOrNull()
            ?.takeIf { it > 0 }
}
