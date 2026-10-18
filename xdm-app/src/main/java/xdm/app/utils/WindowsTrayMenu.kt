package xdm.app.utils

import xdm.app.AppContext.app
import xdm.app.I8N
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.Timer
import xdm.app.utils.win.Win32Window
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.system.exitProcess

/**
 * The tray icon's right-click menu on Windows, as a Swing (FlatLaf) popup. AWT's `PopupMenu` is
 * owner-drawn by AWT at a fixed size, so it came out tiny on a 150-200% display and in the classic
 * Windows style; this one scales with the UI and matches the rest of the app.
 *
 * A popup needs a showing invoker, so it hangs off an invisible 1x1 [owner] window placed at the
 * pointer. The owner taking focus is also how the menu closes like a native one: a click anywhere
 * else takes focus away and hides it ([watch] covers the case where Windows keeps the foreground
 * with the taskbar). All methods run on the EDT.
 */
internal object WindowsTrayMenu {
    private const val OWNER_TITLE = "xdm-tray-menu-owner"

    /**
     * The foreground window when the menu opened, if it wasn't ours (Windows refused the foreground
     * and the taskbar kept it); 0 otherwise.
     */
    private var shellWindow = 0L

    /**
     * Safety net for when [owner] never got the real foreground: closes the menu once the user
     * activates any other window - the foreground leaves both XDM and the window that had it.
     */
    private val watch = Timer(150) {
        if (!Win32Window.isForegroundOurs() && Win32Window.foregroundWindow() != shellWindow) {
            popup.isVisible = false
        }
    }

    private val popup: JPopupMenu by lazy {
        JPopupMenu().apply {
            add(JMenuItem(I8N.text("MSG_RESTORE")).apply { addActionListener { app.showAppWindow() } })
            addSeparator()
            add(JMenuItem(I8N.text("MENU_EXIT")).apply { addActionListener { exitProcess(0) } })
        }
    }

    private val owner: JDialog by lazy {
        JDialog().apply {
            isUndecorated = true
            // Not shown anywhere; lets Win32Window find the HWND.
            title = OWNER_TITLE
            // No taskbar button and no Alt+Tab entry.
            type = Window.Type.UTILITY
            isAlwaysOnTop = true
            setSize(1, 1)
            opacity = 0f
            addWindowFocusListener(object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) {
                    popup.isVisible = false
                }
            })
            popup.addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = Unit
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                    watch.stop()
                    isVisible = false
                }
                override fun popupMenuCanceled(e: PopupMenuEvent) = Unit
            })
        }
    }

    /** Opens the menu at the mouse pointer, which is on the tray icon. */
    fun show() {
        // The pointer, not the tray MouseEvent's coordinates: those are device pixels on a scaled
        // display, while the pointer location is in the same user space as windows.
        val at = MouseInfo.getPointerInfo()?.location ?: return
        // Not part of any window, so a theme switch in the settings doesn't reach it otherwise.
        SwingUtilities.updateComponentTreeUI(popup)
        val size = popup.preferredSize
        val area = workAreaAt(at)
        val location = menuLocation(at, size.width, size.height, area)
        owner.setLocation(at.x, at.y)
        owner.isVisible = true
        owner.toFront()
        // AWT's toFront doesn't take the foreground from the taskbar, and without it a click in
        // another app never takes focus from the owner, so the menu would stay open.
        Win32Window.bringToForeground(OWNER_TITLE)
        owner.requestFocus()
        popup.show(owner, location.x - at.x, location.y - at.y)
        shellWindow = Win32Window.foregroundWindow().takeUnless { Win32Window.isForegroundOurs() } ?: 0L
        watch.start()
    }

    /** Space between the menu and the taskbar (or screen edge), in user space like Windows 11's own menus. */
    private const val GAP = 8

    /**
     * Where the menu goes for a click at [at] on a screen whose work area is [area], placed like the
     * Windows 11 tray menus: centered on the icon along the taskbar and [GAP] clear of it, whichever
     * edge the taskbar is on. The pointer stands in for the icon, which AWT gives no rectangle for.
     * A pointer inside the work area (the overflow flyout above the taskbar) gets the menu centered
     * above it. Always kept [GAP] inside [area].
     */
    internal fun menuLocation(at: Point, width: Int, height: Int, area: Rectangle): Point {
        val right = area.x + area.width
        val bottom = area.y + area.height
        val centeredX = at.x - width / 2
        val centeredY = at.y - height / 2
        val (x, y) = when {
            at.y >= bottom -> centeredX to bottom - height - GAP
            at.y < area.y -> centeredX to area.y + GAP
            at.x < area.x -> area.x + GAP to centeredY
            at.x >= right -> right - width - GAP to centeredY
            else -> centeredX to at.y - height - GAP
        }
        return Point(
            x.coerceAtMost(right - width - GAP).coerceAtLeast(area.x + GAP),
            y.coerceAtMost(bottom - height - GAP).coerceAtLeast(area.y + GAP),
        )
    }

    /** The work area (screen minus taskbar) of the screen holding [p]. */
    private fun workAreaAt(p: Point): Rectangle {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val gc = env.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(p) }
            ?: env.defaultScreenDevice.defaultConfiguration
        val b = gc.bounds
        val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    }
}
