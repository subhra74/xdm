package xdm.app.utils.linux

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.linux.dbus.Variant
import xdm.app.utils.logoImage
import xdm.core.util.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * Desktop notifications through `org.freedesktop.Notifications` (Linux only). Every desktop has a
 * notification server, GNOME without the AppIndicator extension included, so this works where there is
 * no tray at all. A click on the notification runs its action, or brings up the window.
 */
internal object DBusNotifications {

    private const val NAME = "org.freedesktop.Notifications"
    private const val PATH = "/org/freedesktop/Notifications"

    /** `Notify` waits for the server (which the bus may have to start), so never on the EDT. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dbus-notify").apply { isDaemon = true } }

    /**
     * Our notifications still on screen, by id, with what a click does (null: bring up the window).
     * Only clicks on these count. ConcurrentHashMap takes no null values, so the action is boxed.
     */
    private val shown = ConcurrentHashMap<Int, ClickAction>()

    private class ClickAction(val run: (() -> Unit)?)
    private val listening = AtomicBoolean()

    /**
     * Servers that advertise `body-markup` parse the body as a small HTML subset, so a file name with
     * `&` or `<` would break it. Asked once (worker thread only).
     */
    private val markup: Boolean by lazy {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (SessionBus.connection?.call(NAME, PATH, NAME, "GetCapabilities")?.first() as? List<String>)
                ?.contains("body-markup") == true
        }.getOrDefault(false)
    }

    private val icon: String by lazy {
        runCatching {
            val file = File(AppContext.configDir, "xdm-notification.png")
            if (!file.isFile) ImageIO.write(logoImage(128), "png", file)
            file.absolutePath
        }.getOrDefault("")
    }

    /**
     * Posts a notification. A click runs [onClick] (on the D-Bus thread), or brings up the window when it
     * is null; [onFailed] runs (on a background thread) when there is no server.
     */
    fun post(title: String, body: String, onClick: (() -> Unit)?, onFailed: () -> Unit) {
        worker.execute {
            val bus = SessionBus.connection ?: return@execute onFailed()
            try {
                listen()
                val hints = buildMap<String, Any> {
                    // Ties the notification to the menu entry the deb/rpm installs (icon, settings).
                    if (File("/usr/share/applications/xdman.desktop").isFile) put("desktop-entry", Variant("s", "xdman"))
                }
                val id = bus.call(
                    NAME, PATH, NAME, "Notify", "susssasa{sv}i",
                    listOf("XDM", 0, icon, title, if (markup) escape(body) else body, listOf("default", I8N.text("MSG_RESTORE")), hints, -1),
                ).first() as Int
                shown[id] = ClickAction(onClick)
            } catch (e: Exception) {
                Logger.error("Notifications", "Notify failed: ${e.message}")
                onFailed()
            }
        }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun listen() {
        val bus = SessionBus.connection ?: return
        if (!listening.compareAndSet(false, true)) return
        bus.addSignalListener("type='signal',interface='$NAME',path='$PATH'") { signal ->
            val id = signal.body.firstOrNull() as? Int ?: return@addSignalListener
            when (signal.member) {
                "ActionInvoked" -> shown[id]?.let { click ->
                    runCatching { (click.run ?: AppContext.app::showAppWindow)() }
                        .onFailure { Logger.error("Notifications", "Click action failed: ${it.message}") }
                }
                "NotificationClosed" -> shown -= id
            }
        }
    }
}
