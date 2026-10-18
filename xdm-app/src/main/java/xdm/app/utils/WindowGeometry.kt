package xdm.app.utils

import com.formdev.flatlaf.util.UIScale
import xdm.app.AppContext
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors
import javax.swing.Timer

/**
 * Remembers where top-level windows were: position, normal size, maximized state and the monitor
 * they were on, in `window-geometry.dat` (one record per window key). [WindowPlacement] decides where
 * a saved window goes on the current monitor layout; a missing, unreadable or older-format file
 * falls back to the default size, centered on the primary screen.
 *
 * On the Wayland toolkit clients cannot place their windows, so only the size and maximized state
 * come back there.
 */
object WindowGeometry {
    private const val FILE = "window-geometry.dat"
    private const val VERSION = 1
    private const val SAVE_DELAY_MS = 1000

    /** Guarded by itself; written on the EDT, read by the writer thread and the shutdown hook. */
    private val records = HashMap<String, SavedGeometry>()
    private var loaded = false
    @Volatile
    private var dirty = false

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "window-geometry").apply { isDaemon = true } }
    private val saveTimer = Timer(SAVE_DELAY_MS) { flush() }.apply { isRepeats = false }

    /**
     * Gives [frame] its saved geometry, or [defaultWidth] x [defaultHeight] (100% pixels) centered on
     * the primary screen, then keeps the record up to date as the user moves, resizes, maximizes or
     * hides it. Call on the EDT before the frame is first shown.
     */
    fun restore(frame: Frame, key: String, defaultWidth: Int, defaultHeight: Int) {
        val saved = if (GraphicsEnvironment.isHeadless()) null else synchronized(records) {
            ensureLoaded()
            records[key]
        }
        val applied = saved?.let { runCatching { apply(frame, it) }.onFailure { e -> Logger.error("Could not restore window '$key'", e) }.getOrNull() }
        if (applied != true) {
            frame.setSize(defaultWidth.px, defaultHeight.px)
            frame.setLocationRelativeTo(null)
        }
        track(frame, key)
    }

    private fun apply(frame: Frame, saved: SavedGeometry): Boolean {
        val screens = currentScreens()
        val primary = primaryScreen() ?: return false
        val uiScale = UIScale.getUserScaleFactor()
        if (WaylandToolkit.isActive) {
            val size = WindowPlacement.place(saved, primary, uiScale)
            frame.setSize(size.width, size.height)
            frame.setLocationRelativeTo(null)
        } else {
            val target = WindowPlacement.pickScreen(saved, screens, primary)
            frame.bounds = WindowPlacement.place(saved, target, uiScale)
        }
        if (saved.maximized && frame.toolkit.isFrameStateSupported(Frame.MAXIMIZED_BOTH)) {
            // The normal bounds go first, so the window maximizes on that screen and un-maximizes
            // back to them (as Qt does). Some X11 window managers drop a state set before the window
            // is mapped, hence the second attempt once it is open.
            frame.extendedState = frame.extendedState or Frame.MAXIMIZED_BOTH
            frame.addWindowListener(object : WindowAdapter() {
                override fun windowOpened(e: WindowEvent) {
                    frame.removeWindowListener(this)
                    if (frame.extendedState and Frame.MAXIMIZED_BOTH != Frame.MAXIMIZED_BOTH) {
                        frame.extendedState = frame.extendedState or Frame.MAXIMIZED_BOTH
                    }
                }
            })
        }
        return true
    }

    private fun track(frame: Frame, key: String) {
        if (GraphicsEnvironment.isHeadless()) return
        val onChange = { capture(frame, key) }
        frame.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = onChange()
            override fun componentResized(e: ComponentEvent) = onChange()
            // Closing the main window hides it to the tray: save now rather than after the delay.
            override fun componentHidden(e: ComponentEvent) = flush()
        })
        frame.addWindowStateListener { onChange() }
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = flush()
        })
    }

    /** Records [frame]'s current geometry under [key]. EDT. */
    private fun capture(frame: Frame, key: String) {
        if (!frame.isShowing) return
        val state = frame.extendedState
        // A minimized window keeps the geometry it had before; only remember whether it was maximized.
        val maximized = state and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
        val screens = currentScreens()
        val uiScale = UIScale.getUserScaleFactor()
        synchronized(records) {
            val previous = records[key]
            val updated = when {
                state and Frame.ICONIFIED != 0 -> previous?.copy(maximized = maximized)
                maximized -> previous?.let { p ->
                    // Maximized onto another monitor (Win+Shift+Arrow, a window manager shortcut):
                    // un-maximizing next time should happen on that monitor too.
                    val screen = WindowPlacement.screenFor(frame.bounds, screens)
                    if (screen != null && !WaylandToolkit.isActive && screen.bounds != p.screenBounds) {
                        WindowPlacement.moveTo(p, screen, uiScale).copy(maximized = true)
                    } else {
                        p.copy(maximized = true)
                    }
                }
                else -> {
                    val bounds = frame.bounds
                    val screen = WindowPlacement.screenFor(bounds, screens) ?: return
                    if (WindowPlacement.fills(bounds, screen.workArea)) return
                    SavedGeometry(screen.id, Rectangle(screen.bounds), screen.scale, uiScale, bounds, false)
                }
            } ?: return
            if (updated == previous) return
            records[key] = updated
            dirty = true
        }
        saveTimer.restart()
    }

    /** Writes pending changes now, off the EDT. */
    private fun flush() {
        saveTimer.stop()
        if (dirty) writer.execute(::save)
    }

    private fun save() {
        val snapshot = synchronized(records) {
            if (!dirty) return
            dirty = false
            HashMap(records)
        }
        AtomicIO.writeTransacted(FILE, AppContext.configDir) { encode(it, snapshot) }
            .onFailure { Logger.error("Could not save window positions", it) }
    }

    /** Must hold the [records] lock. */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        // A window moved shortly before exit (from the tray menu, a second-instance handover) would
        // otherwise lose the last second of changes. Touches no AWT state.
        Runtime.getRuntime().addShutdownHook(Thread({ save() }, "window-geometry-exit"))
        if (!AtomicIO.exists(FILE, AppContext.configDir)) return
        AtomicIO.readTransacted(FILE, AppContext.configDir) { decode(it) }
            .onSuccess { records.putAll(it) }
            .onFailure { Logger.error("Could not read window positions; using defaults", it) }
    }

    internal fun encode(out: DataOutputStream, map: Map<String, SavedGeometry>) {
        out.writeInt(VERSION)
        out.writeInt(map.size)
        for ((key, g) in map) {
            out.writeUTF(key)
            out.writeUTF(g.screenId)
            writeRect(out, g.screenBounds)
            out.writeDouble(g.screenScale)
            out.writeFloat(g.uiScale)
            writeRect(out, g.normal)
            out.writeBoolean(g.maximized)
        }
    }

    /** An unknown version reads as empty: the windows then open at their defaults. */
    internal fun decode(input: DataInputStream): Map<String, SavedGeometry> {
        val version = input.readInt()
        if (version != VERSION) {
            Logger.info("XDM", "Ignoring $FILE version $version")
            return emptyMap()
        }
        val count = input.readInt()
        val map = HashMap<String, SavedGeometry>()
        repeat(count) {
            val key = input.readUTF()
            val g = SavedGeometry(
                screenId = input.readUTF(),
                screenBounds = readRect(input),
                screenScale = input.readDouble(),
                uiScale = input.readFloat(),
                normal = readRect(input),
                maximized = input.readBoolean(),
            )
            // Nonsense sizes (a hand-edited or damaged file) fall back to the default for that window.
            if (g.normal.width > 0 && g.normal.height > 0 && g.screenBounds.width > 0 && g.screenBounds.height > 0) {
                map[key] = g
            }
        }
        return map
    }

    private fun writeRect(out: DataOutputStream, r: Rectangle) {
        out.writeInt(r.x); out.writeInt(r.y); out.writeInt(r.width); out.writeInt(r.height)
    }

    private fun readRect(input: DataInputStream) =
        Rectangle(input.readInt(), input.readInt(), input.readInt(), input.readInt())

    private fun currentScreens(): List<ScreenInfo> =
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.mapNotNull { device ->
            runCatching {
                val gc = device.defaultConfiguration
                val bounds = gc.bounds
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
                val work = Rectangle(
                    bounds.x + insets.left, bounds.y + insets.top,
                    bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom,
                )
                ScreenInfo(device.iDstring, bounds, work, gc.defaultTransform.scaleX)
            }.getOrNull()
        }

    private fun primaryScreen(): ScreenInfo? {
        val id = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.iDstring
        return currentScreens().let { all -> all.firstOrNull { it.id == id } ?: all.firstOrNull() }
    }
}
