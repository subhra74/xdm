package xdm.app.utils

import xdm.app.OS
import xdm.app.utils.linux.SessionBus
import xdm.app.utils.linux.dbus.Variant
import xdm.app.utils.mac.ObjC
import xdm.app.utils.win.Win32Registry
import xdm.core.util.Logger
import java.lang.foreign.MemorySegment

/**
 * The OS light/dark preference, read through the native layers XDM already has (no helper
 * processes). The first run picks its theme from it; after that the saved setting rules.
 *
 * - Windows: `HKCU\...\Themes\Personalize` `AppsUseLightTheme` (REG_DWORD, 0 = dark).
 * - macOS: the `AppleInterfaceStyle` user default, `"Dark"` in dark mode and absent in light mode.
 * - Linux: the desktop portal's `org.freedesktop.appearance` `color-scheme`
 *   (0 = no preference, 1 = dark, 2 = light), which GNOME, KDE and others back. GNOME and Ubuntu
 *   report no preference in light mode, so then the GTK theme name decides, as Qt's GTK3 theme
 *   does: a name containing "dark" (`Yaru-dark`, `Adwaita-dark`) is dark, anything else unknown.
 */
internal object SystemTheme {

    private const val PERSONALIZE = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
    private const val PORTAL = "org.freedesktop.portal.Desktop"
    private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
    private const val SETTINGS = "org.freedesktop.portal.Settings"
    private const val APPEARANCE = "org.freedesktop.appearance"
    private const val GNOME_INTERFACE = "org.gnome.desktop.interface"

    /** True for dark, false for light, null when the OS doesn't say or it couldn't be read. Never throws. */
    fun isDark(): Boolean? = runCatching {
        when (detectOS()) {
            OS.Windows -> windowsIsDark(Win32Registry.getDword(PERSONALIZE, "AppsUseLightTheme"))
            OS.MacOS -> macIsDark(macInterfaceStyle())
            OS.Linux -> portalIsDark(portalSetting(APPEARANCE, "color-scheme") as? Number) {
                portalSetting(GNOME_INTERFACE, "gtk-theme") as? String
            }
        }
    }.onFailure { Logger.error("Reading the OS theme failed", it) }.getOrNull()

    fun windowsIsDark(appsUseLightTheme: Int?): Boolean? = appsUseLightTheme?.let { it == 0 }

    /** Light mode has no `AppleInterfaceStyle` at all, so a missing value is a real answer here. */
    fun macIsDark(interfaceStyle: String?): Boolean = interfaceStyle.equals("Dark", ignoreCase = true)

    /** [gtkTheme] is only read when `color-scheme` gives no answer. */
    fun portalIsDark(colorScheme: Number?, gtkTheme: () -> String?): Boolean? = when (colorScheme?.toInt()) {
        1 -> true
        2 -> false
        else -> if (gtkTheme()?.contains("dark", ignoreCase = true) == true) true else null
    }

    private fun macInterfaceStyle(): String? = ObjC.autoreleasing {
        val defaults = ObjC.call(ObjC.cls("NSUserDefaults"), "standardUserDefaults")
        val style = ObjC.call(defaults, "stringForKey:", ObjC.nsString("AppleInterfaceStyle"))
        if (style == MemorySegment.NULL) null else ObjC.string(style)
    }

    /** `ReadOne` (portal version 2) returns the value; the older `Read` wraps it in one more variant. */
    private fun portalSetting(namespace: String, key: String): Any? {
        val bus = SessionBus.connection ?: return null
        return runCatching {
            (bus.call(PORTAL, PORTAL_PATH, SETTINGS, "ReadOne", "ss", listOf(namespace, key)).first() as Variant).value
        }.recoverCatching {
            val outer = bus.call(PORTAL, PORTAL_PATH, SETTINGS, "Read", "ss", listOf(namespace, key)).first() as Variant
            (outer.value as? Variant)?.value ?: outer.value
        }.getOrNull()
    }
}
