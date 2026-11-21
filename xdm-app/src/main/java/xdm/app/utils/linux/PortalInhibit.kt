package xdm.app.utils.linux

import xdm.app.utils.linux.dbus.Variant
import xdm.core.util.Logger

/**
 * Blocks idle system suspend through the XDG desktop portal (`org.freedesktop.portal.Inhibit`), Linux only.
 * Works the same for a deb/rpm install and inside a Flatpak, where `systemd-inhibit` does not exist. The
 * portal backend (GNOME's, KDE's, the GTK one) passes the request to the session (gnome-session,
 * PowerDevil); only the suspend flag is set, so the display can still blank. The portal drops the
 * inhibitor by itself when our bus connection closes, so a crash cannot leave one behind.
 */
internal object PortalInhibit {

    private const val PORTAL = "org.freedesktop.portal.Desktop"
    private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
    private const val INHIBIT = "org.freedesktop.portal.Inhibit"
    private const val REQUEST = "org.freedesktop.portal.Request"

    /** The portal's `Suspend` flag. `Idle` (8) is not set: it would keep the screen on too. */
    private const val FLAG_SUSPEND = 4

    /** The portal may have to be started by the bus on the first call. */
    private const val CALL_TIMEOUT_MILLIS = 10_000L

    /**
     * Asks the portal to block suspend and returns the request handle that holds the inhibitor, or null
     * when there is no session bus, no portal, or no backend that implements Inhibit.
     */
    fun inhibit(reason: String): String? {
        val bus = SessionBus.connection ?: return null
        return try {
            bus.call(
                PORTAL, PORTAL_PATH, INHIBIT, "Inhibit", "sua{sv}",
                listOf("", FLAG_SUSPEND, mapOf("reason" to Variant("s", reason))),
                timeoutMillis = CALL_TIMEOUT_MILLIS,
            ).first() as String
        } catch (e: Exception) {
            Logger.error("KeepAwake", "Portal inhibit unavailable: ${e.message}")
            null
        }
    }

    /** Ends the inhibitor [inhibit] returned. */
    fun release(handle: String) {
        val bus = SessionBus.connection ?: return
        runCatching { bus.call(PORTAL, handle, REQUEST, "Close") }
            .onFailure { Logger.error("KeepAwake", "Portal inhibitor release failed: ${it.message}") }
    }
}
