package xdm.app.utils.linux

import xdm.app.I8N
import xdm.app.utils.linux.dbus.DBusConnection
import xdm.app.utils.linux.dbus.Message
import xdm.app.utils.linux.dbus.Variant
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.Component
import java.awt.EventQueue
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.RootPaneContainer
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.concurrent.thread

/**
 * The desktop's own file/folder dialog through the XDG desktop portal (`org.freedesktop.portal.FileChooser`),
 * Linux only. The portal backend (GNOME's, KDE's, …) draws it in its own process, so there is no in-process
 * native dialog to hang, and it works the same on X11, XWayland and native Wayland. See
 * private-docs/docs/linux-file-chooser.md.
 *
 * Not a Java modal: the owner window is blocked by hand while the dialog is open ([WindowBlock]), and the EDT
 * keeps pumping events on a [java.awt.SecondaryLoop] while a background thread waits for the portal's answer.
 */
internal object PortalFileChooser {

    sealed interface Result {
        class Chosen(val file: File) : Result
        object Cancelled : Result
        /** No portal, too old a portal, or it failed: the caller shows Swing's chooser instead. */
        object Unavailable : Result
    }

    private const val PORTAL = "org.freedesktop.portal.Desktop"
    private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
    private const val FILE_CHOOSER = "org.freedesktop.portal.FileChooser"
    private const val REQUEST = "org.freedesktop.portal.Request"
    private const val PROPERTIES = "org.freedesktop.DBus.Properties"

    /** `directory` came with version 3 of the interface. */
    private const val DIRECTORY_VERSION = 3

    /** `OpenFile` may have to wait for the bus to start the portal. */
    private const val CALL_TIMEOUT_MILLIS = 30_000L

    /** The interface version, asked once; 0 when there is no portal file chooser. */
    @Volatile
    private var version: Int? = null

    /**
     * Shows the portal dialog for [parent] and waits for it (call on the EDT). [filter] is a description
     * and extensions without the dot.
     */
    fun choose(
        parent: Component?, title: String, directoriesOnly: Boolean, currentDir: File?,
        filter: Pair<String, List<String>>?,
    ): Result {
        val ask = { request(title, directoriesOnly, currentDir, filter) }
        if (!EventQueue.isDispatchThread()) return ask()

        val owner = parent?.let { it as? Window ?: SwingUtilities.getWindowAncestor(it) }
        val result = AtomicReference<Result>(Result.Unavailable)
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        val worker = thread(start = false, isDaemon = true, name = "portal-file-chooser") {
            try {
                result.set(ask())
            } finally {
                loop.exit()
            }
        }
        val block = owner?.let(::WindowBlock)
        try {
            // Started from inside the loop: an exit() that came before enter() would leave the EDT blocked.
            EventQueue.invokeLater { worker.start() }
            loop.enter()
        } finally {
            block?.restore()
        }
        return result.get()
    }

    /**
     * Keeps [window] from being used while the portal dialog is open, and [restore]s it. Disabling the window
     * is not enough: JBR's native Wayland toolkit still delivers clicks and the close button to a disabled
     * window, and does not stop them at a glass pane either. So the window's content is swapped for a
     * "waiting for the file selection" card (nothing left to click), Enter no longer presses its default
     * button, keys are dropped and closing does nothing. Always-on-top is taken off the window and its
     * owners, or under X11/XWayland they would cover the portal dialog.
     */
    private class WindowBlock(private val window: Window) {
        private val onTop = generateSequence(window) { it.owner }.filter { it.isAlwaysOnTop }.toList()
        private val root = (window as? RootPaneContainer)?.rootPane
        private val content = root?.contentPane
        private val defaultButton = root?.defaultButton
        private val focused = window.mostRecentFocusOwner
        private val closeOperation = when (window) {
            is JDialog -> window.defaultCloseOperation
            is JFrame -> window.defaultCloseOperation
            else -> null
        }
        private val keys = KeyEventDispatcher { e ->
            val source = e.component
            source != null && (source as? Window ?: SwingUtilities.getWindowAncestor(source)) == window
        }

        init {
            onTop.forEach { it.isAlwaysOnTop = false }
            if (root != null) {
                root.defaultButton = null
                // Not packed: the window keeps its size, with the message in the middle.
                root.contentPane = JPanel(BorderLayout()).apply {
                    add(JLabel(I8N.text("CHOOSER_WAITING"), SwingConstants.CENTER), BorderLayout.CENTER)
                }
                root.revalidate()
                root.repaint()
            }
            setCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys)
            window.isEnabled = false
        }

        fun restore() {
            window.isEnabled = true
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys)
            closeOperation?.let(::setCloseOperation)
            if (root != null && content != null) {
                root.contentPane = content
                root.defaultButton = defaultButton
                root.revalidate()
                root.repaint()
            }
            onTop.forEach { it.isAlwaysOnTop = true }
            window.toFront()
            focused?.requestFocusInWindow()
        }

        private fun setCloseOperation(operation: Int) {
            when (window) {
                is JDialog -> window.defaultCloseOperation = operation
                is JFrame -> window.defaultCloseOperation = operation
            }
        }
    }

    private fun request(
        title: String, directoriesOnly: Boolean, currentDir: File?, filter: Pair<String, List<String>>?,
    ): Result {
        val bus = SessionBus.connection ?: return Result.Unavailable
        val version = version(bus)
        if (version < 1 || (directoriesOnly && version < DIRECTORY_VERSION)) return Result.Unavailable

        // The reply names the request object, but a fast Response can beat it: listen on the path the
        // portal will use before calling (Request docs). Portals older than 0.9 pick another path, so the
        // returned one is listened on as well.
        val token = "xdm" + UUID.randomUUID().toString().replace("-", "")
        val expected = "$PORTAL_PATH/request/${bus.uniqueName.removePrefix(":").replace('.', '_')}/$token"
        val response = CompletableFuture<Message>()
        val paths = mutableSetOf(expected)
        val listener: (Message) -> Unit = {
            if (it.interfaceName == REQUEST && it.member == "Response" && synchronized(paths) { it.path in paths }) {
                response.complete(it)
            }
        }
        val rules = mutableListOf(rule(expected))
        bus.addSignalListener(rules[0], listener)
        try {
            val handle = bus.call(
                PORTAL, PORTAL_PATH, FILE_CHOOSER, "OpenFile", "ssa{sv}",
                listOf("", title, options(token, directoriesOnly, currentDir, filter)),
                timeoutMillis = CALL_TIMEOUT_MILLIS,
            ).first() as String
            if (handle != expected) {
                synchronized(paths) { paths += handle }
                rules += rule(handle)
                bus.addSignalListener(rules[1], listener)
            }
            return toResult(await(bus, response).body)
        } catch (e: Exception) {
            Logger.error("FileChooser", "Portal file chooser failed: ${e.message}")
            return Result.Unavailable
        } finally {
            rules.forEach { bus.removeSignalListener(it, listener) }
        }
    }

    private fun rule(path: String) = "type='signal',interface='$REQUEST',member='Response',path='$path'"

    /** The user may keep the dialog open for as long as they like; only a lost bus ends the wait. */
    private fun await(bus: DBusConnection, response: CompletableFuture<Message>): Message {
        while (true) {
            try {
                return response.get(1, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                if (!bus.isOpen) throw IllegalStateException("D-Bus connection lost")
            }
        }
    }

    private fun version(bus: DBusConnection): Int = version ?: runCatching {
        // Get, not NameHasOwner: the bus starts the portal on first use, so it may not own its name yet.
        (bus.call(PORTAL, PORTAL_PATH, PROPERTIES, "Get", "ss", listOf(FILE_CHOOSER, "version"),
            timeoutMillis = CALL_TIMEOUT_MILLIS).first() as Variant).value as Int
    }.onSuccess { Logger.info("FileChooser", "Portal file chooser version $it") }
        .onFailure { Logger.info("FileChooser", "No portal file chooser: ${it.message}") }
        .getOrDefault(0)
        .also { version = it }

    /** The `OpenFile` options (`a{sv}`). */
    internal fun options(
        token: String, directoriesOnly: Boolean, currentDir: File?, filter: Pair<String, List<String>>?,
    ): Map<String, Variant> = buildMap {
        put("handle_token", Variant("s", token))
        put("modal", Variant("b", true))
        put("multiple", Variant("b", false))
        if (directoriesOnly) put("directory", Variant("b", true))
        currentDir?.takeIf { it.isDirectory }?.let {
            put("current_folder", Variant("ay", it.absolutePath.toByteArray(StandardCharsets.UTF_8) + 0))
        }
        filter?.let { (description, extensions) ->
            val one = listOf(description, globs(extensions).map { listOf(0, it) })
            put("filters", Variant("a(sa(us))", listOf(one)))
            put("current_filter", Variant("(sa(us))", one))
        }
    }

    /** GTK matches globs case-sensitively, so `wav` gives `*.wav` and `*.WAV`. */
    internal fun globs(extensions: List<String>): List<String> =
        extensions.flatMap { listOf("*.${it.lowercase()}", "*.${it.uppercase()}") }.distinct()

    /** `Response(u code, a{sv} results)`: 0 chosen, 1 cancelled, anything else failed. */
    internal fun toResult(body: List<Any?>): Result {
        val code = body.getOrNull(0) as? Int ?: return Result.Unavailable
        when (code) {
            0 -> {}
            1 -> return Result.Cancelled
            else -> {
                Logger.error("FileChooser", "Portal file chooser ended with code $code")
                return Result.Unavailable
            }
        }
        val results = body.getOrNull(1) as? Map<*, *>
        val uri = ((results?.get("uris") as? Variant)?.value as? List<*>)?.firstOrNull() as? String
            ?: return Result.Cancelled
        val file = runCatching { File(URI(uri)) }.getOrElse {
            Logger.error("FileChooser", "Portal returned a location that is not a local file: $uri")
            return Result.Cancelled
        }
        // Flatpak without access to the location: a FUSE path from the document portal (see the doc).
        if (file.path.startsWith("/run/user/") && file.path.contains("/doc/")) {
            Logger.info("FileChooser", "Portal returned a document-portal path: $file")
        }
        return Result.Chosen(file)
    }
}
