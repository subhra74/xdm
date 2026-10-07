package xdm.app.utils.linux

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.linux.dbus.DBusConnection
import xdm.app.utils.linux.dbus.DBusError
import xdm.app.utils.linux.dbus.ExportedObject
import xdm.app.utils.linux.dbus.Reply
import xdm.app.utils.linux.dbus.Variant
import xdm.core.util.Logger
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * The Linux tray icon as a D-Bus StatusNotifierItem with a `com.canonical.dbusmenu` menu: what KDE
 * Plasma, Cinnamon, Xfce/LXQt panels, waybar and GNOME's AppIndicator extension show. Unlike
 * `java.awt.SystemTray` (XEmbed) it also works under the JetBrains Runtime's Wayland toolkit, which has
 * no AWT tray at all (PACKAGING.md §7.6, §7.7).
 *
 * Left click (`Activate`) brings up the window; the menu has the same plus Exit.
 */
internal object DBusTray {

    private const val WATCHER = "org.kde.StatusNotifierWatcher"
    private const val WATCHER_PATH = "/StatusNotifierWatcher"
    private const val ITEM_INTERFACE = "org.kde.StatusNotifierItem"
    private const val ITEM_PATH = "/StatusNotifierItem"
    private const val MENU_INTERFACE = "com.canonical.dbusmenu"
    private const val MENU_PATH = "/MenuBar"
    private const val PROPERTIES = "org.freedesktop.DBus.Properties"

    private const val ITEM_SHOW = 1
    private const val ITEM_SEPARATOR = 2
    private const val ITEM_EXIT = 3

    private var busName: String? = null

    /** False when install gave up, so a watcher that turns up later doesn't add a second icon. */
    @Volatile
    private var active = false

    /**
     * Puts the icon on the bus and registers it with the watcher. Returns false (and leaves nothing
     * behind) when there is no session bus or no watcher, so the caller can try the AWT tray.
     */
    fun install(icon: (Int) -> BufferedImage): Boolean {
        val bus = SessionBus.connection ?: return false
        return try {
            if (!bus.nameHasOwner(WATCHER)) {
                Logger.info("Tray", "No StatusNotifierWatcher on the session bus")
                return false
            }
            val name = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
            if (!bus.requestName(name)) {
                Logger.error("Tray", "Could not own $name")
                return false
            }
            busName = name
            bus.export(ITEM_PATH, Item(pixmaps(icon)))
            bus.export(MENU_PATH, Menu)
            // A panel restart brings a new watcher, which knows nothing of us.
            bus.addSignalListener(
                "type='signal',sender='${DBusConnection.BUS}',interface='${DBusConnection.BUS}'," +
                        "member='NameOwnerChanged',arg0='$WATCHER'"
            ) { signal ->
                if (active && signal.member == "NameOwnerChanged" && signal.body.getOrNull(0) == WATCHER &&
                    (signal.body.getOrNull(2) as? String).orEmpty().isNotEmpty()
                ) {
                    register(bus)
                }
            }
            active = register(bus)
            active
        } catch (e: Exception) {
            Logger.error("Tray", "StatusNotifierItem failed: ${e.message}")
            false
        }
    }

    private fun register(bus: DBusConnection): Boolean = runCatching {
        bus.call(WATCHER, WATCHER_PATH, WATCHER, "RegisterStatusNotifierItem", "s", listOf(busName))
        Logger.info("Tray", "Registered $busName with the StatusNotifierWatcher")
        true
    }.getOrElse {
        Logger.error("Tray", "RegisterStatusNotifierItem failed: ${it.message}")
        false
    }

    /** `a(iiay)`: ARGB32 in network byte order, a few sizes for the host to choose from. */
    private fun pixmaps(icon: (Int) -> BufferedImage): List<List<Any>> = listOf(22, 32, 48, 64).map { size ->
        val image = icon(size)
        val w = image.width
        val h = image.height
        val data = ByteArray(w * h * 4)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = image.getRGB(x, y)
                data[i++] = (argb ushr 24).toByte()
                data[i++] = (argb ushr 16).toByte()
                data[i++] = (argb ushr 8).toByte()
                data[i++] = argb.toByte()
            }
        }
        listOf(w, h, data)
    }

    private fun showWindow() = AppContext.app.showAppWindow()

    private class Item(pixmaps: List<List<Any>>) : ExportedObject {
        private val properties: Map<String, Variant> = linkedMapOf(
            "Category" to Variant("s", "ApplicationStatus"),
            "Id" to Variant("s", "xdm"),
            "Title" to Variant("s", "XDM"),
            "Status" to Variant("s", "Active"),
            "WindowId" to Variant("i", 0),
            "IconName" to Variant("s", ""),
            "IconThemePath" to Variant("s", ""),
            "IconPixmap" to Variant("a(iiay)", pixmaps),
            "OverlayIconName" to Variant("s", ""),
            "OverlayIconPixmap" to Variant("a(iiay)", emptyList<Any>()),
            "AttentionIconName" to Variant("s", ""),
            "AttentionIconPixmap" to Variant("a(iiay)", emptyList<Any>()),
            "AttentionMovieName" to Variant("s", ""),
            "ToolTip" to Variant("(sa(iiay)ss)", listOf("", emptyList<Any>(), "XDM", "")),
            "ItemIsMenu" to Variant("b", false),
            "Menu" to Variant("o", MENU_PATH),
        )

        override val introspection = """
            <interface name="$ITEM_INTERFACE">
              <method name="Activate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
              <method name="SecondaryActivate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
              <method name="ContextMenu"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
              <method name="Scroll"><arg name="delta" type="i" direction="in"/><arg name="orientation" type="s" direction="in"/></method>
              ${properties.entries.joinToString("\n") { (name, v) -> "<property name=\"$name\" type=\"${v.signature}\" access=\"read\"/>" }}
              <signal name="NewTitle"/><signal name="NewIcon"/><signal name="NewToolTip"/>
              <signal name="NewStatus"><arg name="status" type="s"/></signal>
            </interface>
            ${propertiesIntrospection()}
        """.trimIndent()

        override fun invoke(interfaceName: String?, member: String, args: List<Any?>): Reply? = when (interfaceName) {
            PROPERTIES -> properties(properties, ITEM_INTERFACE, member, args)
            ITEM_INTERFACE, null -> when (member) {
                "Activate", "SecondaryActivate" -> { showWindow(); Reply() }
                // We have a menu, so hosts draw it themselves; nothing to do for the other two.
                "ContextMenu", "Scroll" -> Reply()
                else -> null
            }
            else -> null
        }
    }

    private object Menu : ExportedObject {
        private val properties: Map<String, Variant> = linkedMapOf(
            "Version" to Variant("u", 3),
            "TextDirection" to Variant("s", "ltr"),
            "Status" to Variant("s", "normal"),
            "IconThemePath" to Variant("as", emptyList<String>()),
        )

        override val introspection = """
            <interface name="$MENU_INTERFACE">
              <method name="GetLayout"><arg type="i" name="parentId" direction="in"/><arg type="i" name="recursionDepth" direction="in"/><arg type="as" name="propertyNames" direction="in"/><arg type="u" name="revision" direction="out"/><arg type="(ia{sv}av)" name="layout" direction="out"/></method>
              <method name="GetGroupProperties"><arg type="ai" name="ids" direction="in"/><arg type="as" name="propertyNames" direction="in"/><arg type="a(ia{sv})" name="properties" direction="out"/></method>
              <method name="GetProperty"><arg type="i" name="id" direction="in"/><arg type="s" name="name" direction="in"/><arg type="v" name="value" direction="out"/></method>
              <method name="Event"><arg type="i" name="id" direction="in"/><arg type="s" name="eventId" direction="in"/><arg type="v" name="data" direction="in"/><arg type="u" name="timestamp" direction="in"/></method>
              <method name="EventGroup"><arg type="a(isvu)" name="events" direction="in"/><arg type="ai" name="idErrors" direction="out"/></method>
              <method name="AboutToShow"><arg type="i" name="id" direction="in"/><arg type="b" name="needUpdate" direction="out"/></method>
              <method name="AboutToShowGroup"><arg type="ai" name="ids" direction="in"/><arg type="ai" name="updatesNeeded" direction="out"/><arg type="ai" name="idErrors" direction="out"/></method>
              <signal name="ItemsPropertiesUpdated"><arg type="a(ia{sv})" name="updatedProps"/><arg type="a(ias)" name="removedProps"/></signal>
              <signal name="LayoutUpdated"><arg type="u" name="revision"/><arg type="i" name="parent"/></signal>
              <signal name="ItemActivationRequested"><arg type="i" name="id"/><arg type="u" name="timestamp"/></signal>
              ${properties.keys.joinToString("\n") { "<property name=\"$it\" type=\"${properties.getValue(it).signature}\" access=\"read\"/>" }}
            </interface>
            ${propertiesIntrospection()}
        """.trimIndent()

        private val children = listOf(ITEM_SHOW, ITEM_SEPARATOR, ITEM_EXIT)

        // Built on demand: the labels come from the translations, loaded after start-up.
        private fun itemProperties(id: Int): Map<String, Variant> = when (id) {
            0 -> mapOf("children-display" to Variant("s", "submenu"))
            ITEM_SHOW -> mapOf("label" to Variant("s", I8N.text("MSG_RESTORE")))
            ITEM_SEPARATOR -> mapOf("type" to Variant("s", "separator"))
            ITEM_EXIT -> mapOf("label" to Variant("s", I8N.text("MENU_EXIT")))
            else -> throw DBusError("org.freedesktop.DBus.Error.InvalidArgs", "No menu item $id")
        }

        private fun layout(id: Int, depth: Int): List<Any> {
            val nested = if (id == 0 && depth != 0) children.map { Variant("(ia{sv}av)", layout(it, depth - 1)) } else emptyList()
            return listOf(id, itemProperties(id), nested)
        }

        override fun invoke(interfaceName: String?, member: String, args: List<Any?>): Reply? = when (interfaceName) {
            PROPERTIES -> properties(properties, MENU_INTERFACE, member, args)
            MENU_INTERFACE, null -> when (member) {
                "GetLayout" -> Reply("u(ia{sv}av)", listOf(1, layout(args[0] as Int, args[1] as Int)))
                "GetGroupProperties" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = (args[0] as List<Int>).ifEmpty { listOf(0) + children }
                    Reply("a(ia{sv})", listOf(ids.map { listOf(it, itemProperties(it)) }))
                }
                "GetProperty" -> Reply("v", listOf(itemProperties(args[0] as Int)[args[1] as String] ?: Variant("s", "")))
                "Event" -> { event(args[0] as Int, args[1] as String); Reply() }
                "EventGroup" -> {
                    @Suppress("UNCHECKED_CAST")
                    (args[0] as List<List<Any?>>).forEach { event(it[0] as Int, it[1] as String) }
                    Reply("ai", listOf(emptyList<Int>()))
                }
                "AboutToShow" -> Reply("b", listOf(false))
                "AboutToShowGroup" -> Reply("aiai", listOf(emptyList<Int>(), emptyList<Int>()))
                else -> null
            }
            else -> null
        }

        private fun event(id: Int, eventId: String) {
            if (eventId != "clicked") return
            when (id) {
                ITEM_SHOW -> showWindow()
                ITEM_EXIT -> SwingUtilities.invokeLater { exitProcess(0) }
            }
        }
    }

    /** `org.freedesktop.DBus.Properties` over a fixed map. */
    private fun properties(values: Map<String, Variant>, ownInterface: String, member: String, args: List<Any?>): Reply? =
        when (member) {
            "Get" -> {
                if (args[0] != ownInterface) throw DBusError("org.freedesktop.DBus.Error.UnknownInterface", args[0] as String)
                Reply("v", listOf(values[args[1]] ?: throw DBusError("org.freedesktop.DBus.Error.UnknownProperty", args[1] as String)))
            }
            "GetAll" -> Reply("a{sv}", listOf(if (args[0] == ownInterface) values else emptyMap()))
            "Set" -> throw DBusError("org.freedesktop.DBus.Error.PropertyReadOnly", args[1] as String)
            else -> null
        }

    private fun propertiesIntrospection() = """
        <interface name="$PROPERTIES">
          <method name="Get"><arg type="s" name="interface_name" direction="in"/><arg type="s" name="property_name" direction="in"/><arg type="v" name="value" direction="out"/></method>
          <method name="GetAll"><arg type="s" name="interface_name" direction="in"/><arg type="a{sv}" name="properties" direction="out"/></method>
          <method name="Set"><arg type="s" name="interface_name" direction="in"/><arg type="s" name="property_name" direction="in"/><arg type="v" name="value" direction="in"/></method>
          <signal name="PropertiesChanged"><arg type="s" name="interface_name"/><arg type="a{sv}" name="changed_properties"/><arg type="as" name="invalidated_properties"/></signal>
        </interface>
    """.trimIndent()
}
