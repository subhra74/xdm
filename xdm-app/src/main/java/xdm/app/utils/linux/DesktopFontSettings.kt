package xdm.app.utils.linux

import xdm.app.utils.linux.dbus.Variant
import xdm.core.util.Logger
import java.awt.Font
import java.awt.RenderingHints
import kotlin.math.roundToInt

/**
 * The desktop's UI font and text antialiasing, for the native Wayland toolkit (PACKAGING.md 7.6).
 *
 * Under X11, GNOME's settings daemon hands both to Java over XSETTINGS: FlatLaf takes the font from
 * the `gnome.Gtk/FontName` desktop property and Swing its antialiasing from `awt.font.desktophints`.
 * JBR's WLToolkit has neither, so Swing drew FlatLaf's fallback font (SansSerif 13 px) with no
 * antialiasing at all, and text came out jagged. Here they are read from the desktop portal
 * (`org.freedesktop.portal.Settings`), which is what GTK apps read under Wayland and which applies a
 * desktop's own overrides (Ubuntu's session: Ubuntu Sans, subpixel antialiasing).
 */
internal object DesktopFontSettings {
    private const val PORTAL = "org.freedesktop.portal.Desktop"
    private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
    private const val SETTINGS = "org.freedesktop.portal.Settings"
    private const val NAMESPACE = "org.gnome.desktop.interface"

    /** [font] is null when the portal has none or it isn't installed; FlatLaf then picks its own. */
    data class Settings(val font: Font?, val textAntialiasing: Any)

    fun read(): Settings {
        val fontName = setting("font-name") as? String
        val scaling = (setting("text-scaling-factor") as? Double)?.takeIf { it > 0 } ?: 1.0
        val font = fontName?.let { toFont(it, scaling) }
        val aa = textAntialiasing(setting("font-antialiasing") as? String, setting("font-rgba-order") as? String)
        Logger.info(
            "Toolkit",
            "Desktop font: ${font?.let { "${it.family} ${it.size}px" } ?: "not available (${fontName ?: "no portal"})"}, text: $aa"
        )
        return Settings(font, aa)
    }

    /** `ReadOne` (portal version 2) returns the value; the older `Read` wraps it in one more variant. */
    private fun setting(key: String): Any? {
        val bus = SessionBus.connection ?: return null
        return runCatching {
            (bus.call(PORTAL, PORTAL_PATH, SETTINGS, "ReadOne", "ss", listOf(NAMESPACE, key)).first() as Variant).value
        }.recoverCatching {
            val outer = bus.call(PORTAL, PORTAL_PATH, SETTINGS, "Read", "ss", listOf(NAMESPACE, key)).first() as Variant
            (outer.value as? Variant)?.value ?: outer.value
        }.getOrNull()
    }

    /** "Ubuntu Sans 11" -> Ubuntu Sans at 11 pt, in pixels at 96 dpi times the text scaling, as GTK does. */
    fun toFont(fontName: String, scaling: Double): Font? {
        val (family, points) = parseFontName(fontName) ?: return null
        val font = Font(family, Font.PLAIN, (points * 96.0 / 72.0 * scaling).roundToInt())
        // A family Java doesn't know falls back to the logical Dialog font: better left to FlatLaf.
        return font.takeIf { it.family != Font.DIALOG }
    }

    /** Splits a GSettings font name into family and point size: "Cantarell 11" -> ("Cantarell", 11.0). */
    fun parseFontName(fontName: String): Pair<String, Double>? {
        val name = fontName.trim()
        val size = name.substringAfterLast(' ', "").toDoubleOrNull() ?: return null
        val family = name.substringBeforeLast(' ').trim().takeIf { it.isNotEmpty() } ?: return null
        return family to size
    }

    /** GNOME's `font-antialiasing` / `font-rgba-order` as a Swing text-antialiasing hint; grayscale if unknown. */
    fun textAntialiasing(mode: String?, rgbaOrder: String?): Any = when (mode) {
        "none" -> RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
        "rgba" -> when (rgbaOrder) {
            "bgr" -> RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR
            "vrgb" -> RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VRGB
            "vbgr" -> RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VBGR
            else -> RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
        }
        else -> RenderingHints.VALUE_TEXT_ANTIALIAS_ON
    }
}
