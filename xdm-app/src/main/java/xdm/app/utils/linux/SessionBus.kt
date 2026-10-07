package xdm.app.utils.linux

import xdm.app.utils.linux.dbus.DBusConnection
import xdm.core.util.Logger

/**
 * The session bus connection shared by the tray icon and notifications (Linux only). Opened on first
 * use; null when there is no session bus we can reach (no `DBUS_SESSION_BUS_ADDRESS`, an abstract-socket
 * address, a container), and callers then fall back to AWT.
 */
internal object SessionBus {

    val connection: DBusConnection? by lazy {
        runCatching { DBusConnection.openSession() }
            .onSuccess { Logger.info("DBus", "Connected to the session bus as ${it.uniqueName}") }
            .onFailure { Logger.error("DBus", "No session bus: ${it.message}") }
            .getOrNull()
    }
}
