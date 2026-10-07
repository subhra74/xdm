package xdm.app.utils

import xdm.app.OS
import xdm.core.util.Logger
import java.io.File

/**
 * Picks the JetBrains Runtime's native Wayland toolkit (`WLToolkit`) on a Wayland session.
 *
 * Linux builds ship the JetBrains Runtime (PACKAGING.md §3.1), whose `java.desktop` carries
 * `sun.awt.wl.WLToolkit` next to the X11 one. AWT only uses it when `awt.toolkit.name=WLToolkit`
 * is set before the toolkit loads, so [apply] has to run before any AWT/Swing class initializes:
 * `AppContext.init` calls it right after the config is read, ahead of the look-and-feel. Without
 * it XDM runs through XWayland as before.
 *
 * Precedence: an explicit `-Dawt.toolkit.name`, then the `XDM_WAYLAND` environment variable
 * (`1` / `0`, the way out when native Wayland breaks start-up and the setting can't be reached),
 * then the Advanced setting.
 *
 * JBR 25.0.4.1 presets `awt.toolkit.name=XToolkit` at JVM start (25.0.1 left it unset), so that
 * value is the runtime's default, not a choice: only another value counts as explicit. To force
 * X11, use the setting or `XDM_WAYLAND=0`.
 */
object WaylandToolkit {

    private const val TOOLKIT_PROPERTY = "awt.toolkit.name"
    private const val WL_TOOLKIT = "WLToolkit"
    private const val WL_TOOLKIT_CLASS = "sun.awt.wl.WLToolkit"
    private const val X_TOOLKIT_DEFAULT = "XToolkit"

    /**
     * The Wayland app id of XDM's windows, which compositors match to `<id>.desktop`. Without it
     * WLToolkit uses the java command line (`xdm.app.AppMain ...`), so GNOME can't tie the window to
     * the menu entry it launched: the busy cursor runs until its timeout and the dock shows no XDM.
     * The packages install `xdm-app.desktop` (PACKAGING.md 7.2).
     */
    private const val APP_ID_PROPERTY = "awt.app.id"
    private const val APP_ID = "xdm-app"
    private const val ENV_OVERRIDE = "XDM_WAYLAND"

    /** True on a Linux Wayland session whose runtime has the Wayland toolkit, i.e. the setting can matter. */
    val isSupported: Boolean by lazy { detectOS() == OS.Linux && isWaylandSession() && runtimeHasToolkit() }

    /** True when this JVM was told to use the Wayland toolkit. */
    val isActive: Boolean
        get() = System.getProperty(TOOLKIT_PROPERTY) == WL_TOOLKIT

    /** Chooses the toolkit for this run. [enabled] is the Advanced setting. Must run before AWT loads. */
    fun apply(enabled: Boolean) {
        if (detectOS() != OS.Linux) return
        System.getProperty(TOOLKIT_PROPERTY)?.takeIf { it.isNotBlank() && it != X_TOOLKIT_DEFAULT }?.let {
            Logger.info("Toolkit", "Using $it from -D$TOOLKIT_PROPERTY")
            return
        }
        val forced = when (System.getenv(ENV_OVERRIDE)?.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
        val wanted = forced ?: enabled
        when {
            !wanted -> Logger.info("Toolkit", "Native Wayland off${if (forced != null) " ($ENV_OVERRIDE=0)" else ""}")
            !isWaylandSession() -> Logger.info("Toolkit", "Not a Wayland session; using X11")
            !runtimeHasToolkit() -> Logger.info("Toolkit", "Wayland session, but this runtime has no $WL_TOOLKIT; using XWayland")
            else -> {
                System.setProperty(TOOLKIT_PROPERTY, WL_TOOLKIT)
                if (System.getProperty(APP_ID_PROPERTY).isNullOrBlank()) System.setProperty(APP_ID_PROPERTY, APP_ID)
                Logger.info("Toolkit", "Wayland session: using $WL_TOOLKIT (app id ${System.getProperty(APP_ID_PROPERTY)})")
            }
        }
    }

    /**
     * `WAYLAND_DISPLAY` names the compositor's socket (relative to `XDG_RUNTIME_DIR` unless absolute).
     * The socket has to exist, so a variable left over in an ssh shell or a nested X session doesn't
     * count; `XDG_SESSION_TYPE=wayland` alone doesn't either, since the toolkit needs that socket.
     */
    private fun isWaylandSession(): Boolean {
        val display = System.getenv("WAYLAND_DISPLAY")?.takeIf { it.isNotBlank() } ?: return false
        val socket = if (display.startsWith("/")) File(display) else {
            val runtimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: return false
            File(runtimeDir, display)
        }
        return socket.exists()
    }

    /** Loads without initializing: only the JetBrains Runtime has the class. */
    private fun runtimeHasToolkit(): Boolean =
        runCatching { Class.forName(WL_TOOLKIT_CLASS, false, ClassLoader.getSystemClassLoader()) }.isSuccess
}
