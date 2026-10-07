package xdm.app.ui.components

import com.formdev.flatlaf.ui.FlatNativeWindowsLibrary
import xdm.app.AppContext
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.createIcon
import xdm.app.utils.logoIcon
import xdm.app.utils.px
import xdm.app.utils.win.Win32Notifications
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager

/**
 * XDM's own notification on Windows: a small frameless window in the corner next to the taskbar,
 * shown without taking focus, gone after a few seconds (the timer waits while the mouse is over it).
 * A click runs its action, or brings up the main window; the x just closes it.
 *
 * Used instead of the tray balloon, which Windows 10/11 turns into a toast credited to the Java
 * runtime, with no way to tell its click apart from a double-click on the tray icon. New popups
 * stack away from the taskbar; the oldest goes when there are more than [MAX_VISIBLE].
 *
 * EDT only.
 */
object NotificationPopup {

    private const val WIDTH = 300
    private const val MARGIN = 12
    private const val GAP = 8
    private const val VISIBLE_MS = 6000
    private const val MAX_VISIBLE = 4

    /** Newest first: index 0 sits next to the taskbar. */
    private val visible = ArrayList<Popup>()

    fun show(title: String, body: String, onClick: (() -> Unit)?) {
        // A full-screen game or presentation, or Focus Assist: Windows wouldn't show one either.
        if (!Win32Notifications.acceptsNotifications()) {
            Logger.info("Notifications", "Windows is not taking notifications now; skipped")
            return
        }
        while (visible.size >= MAX_VISIBLE) visible.last().close()
        val popup = Popup(title, body, onClick)
        visible.add(0, popup)
        layoutAll()
        popup.open()
    }

    /** Places the popups in the corner by the taskbar, newest nearest to it. */
    private fun layoutAll() {
        val corner = Corner.current()
        var offset = 0
        for (popup in visible) {
            val size = popup.window.size
            popup.window.location = corner.place(size.width, size.height, offset)
            offset += size.height + GAP.px
        }
    }

    /**
     * The usable area of the primary screen and the edge the taskbar is on. The taskbar is the screen
     * inset Windows reports; with an auto-hiding taskbar there is none, and the popup goes bottom right.
     */
    private class Corner(private val area: Rectangle, private val edge: Edge) {
        enum class Edge { BOTTOM, TOP, LEFT, RIGHT }

        /** Top-left for a popup of [w]x[h], [offset] px further from the taskbar than the corner spot. */
        fun place(w: Int, h: Int, offset: Int): Point {
            val m = MARGIN.px
            val right = area.x + area.width - m - w
            val bottom = area.y + area.height - m - h
            return when (edge) {
                Edge.BOTTOM -> Point(right, bottom - offset)
                Edge.TOP -> Point(right, area.y + m + offset)
                Edge.LEFT -> Point(area.x + m, bottom - offset)
                Edge.RIGHT -> Point(right, bottom - offset)
            }
        }

        companion object {
            fun current(): Corner {
                val gc = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
                val bounds = gc.bounds
                val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
                val area = Rectangle(bounds.x + i.left, bounds.y + i.top, bounds.width - i.left - i.right, bounds.height - i.top - i.bottom)
                val edge = when (maxOf(i.bottom, i.top, i.left, i.right)) {
                    0, i.bottom -> Edge.BOTTOM
                    i.top -> Edge.TOP
                    i.left -> Edge.LEFT
                    else -> Edge.RIGHT
                }
                return Corner(area, edge)
            }
        }
    }

    private class Popup(title: String, body: String, private val onClick: (() -> Unit)?) {
        val window = JWindow()
        private val timer = Timer(VISIBLE_MS) { close() }.apply { isRepeats = false }

        init {
            window.type = Window.Type.POPUP // no taskbar button
            window.isAlwaysOnTop = true
            window.focusableWindowState = false
            window.isAutoRequestFocus = false

            val fg = UIManager.getColor("Label.foreground")
            val muted = UIManager.getColor("Label.disabledForeground") ?: fg
            val text = Box.createVerticalBox().apply {
                add(JLabel(title).apply { font = font.deriveFont(Font.BOLD); foreground = fg })
                add(Box.createVerticalStrut(3.px))
                add(JLabel(wrapped(body)).apply { foreground = muted })
            }
            val close = JButton(createIcon(RemixIcon.CLOSE_LINE, 14)).apply {
                putClientProperty("JButton.buttonType", "toolBarButton")
                isFocusable = false
                margin = java.awt.Insets(2, 2, 2, 2)
                cursor = Cursor.getDefaultCursor()
                addActionListener { close() }
            }
            val content = JPanel(BorderLayout(12.px, 0)).apply {
                background = UIManager.getColor("Panel.background")
                // Windows 10 draws no frame or rounding around a popup; Windows 11 adds both (below).
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color.GRAY),
                    ScaledEmptyBorder(12, 14, 12, 6),
                )
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(JLabel(logoIcon(32.px)), BorderLayout.WEST)
                add(text, BorderLayout.CENTER)
                add(JPanel(BorderLayout()).apply { isOpaque = false; add(close, BorderLayout.NORTH) }, BorderLayout.EAST)
            }
            window.contentPane = content
            content.forEachMouseTarget(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    close()
                    runCatching { (onClick ?: AppContext.app::showAppWindow)() }
                        .onFailure { Logger.error("Notification click action failed", it) }
                }

                // Reading it shouldn't make it vanish.
                override fun mouseEntered(e: MouseEvent) = timer.stop()
                override fun mouseExited(e: MouseEvent) {
                    if (!window.bounds.contains(e.locationOnScreen)) timer.restart()
                }
            })

            window.pack()
            window.setSize(WIDTH.px, window.height)
            roundCorners()
        }

        fun open() {
            window.isVisible = true
            timer.start()
        }

        fun close() {
            timer.stop()
            window.dispose()
            if (visible.remove(this)) layoutAll()
        }

        /** Windows 11's rounded corners and frame for the popup; nothing on Windows 10. */
        private fun roundCorners() {
            runCatching {
                if (!FlatNativeWindowsLibrary.isLoaded()) return
                val hwnd = FlatNativeWindowsLibrary.getHWND(window)
                if (hwnd != 0L) FlatNativeWindowsLibrary.setWindowCornerPreference(hwnd, FlatNativeWindowsLibrary.DWMWCP_ROUND)
            }
        }

        /**
         * Lets the body wrap to the popup's width (labels don't wrap plain text), escaped for HTML. Long
         * file names arrive already shortened (showTrayNotification), but even those don't fit on one
         * line, and HTML only breaks between words: they get a zero-width space between characters.
         */
        private fun wrapped(body: String): String {
            val breakable = Regex("\\S{25,}").replace(body) { it.value.toList().joinToString("\u200B") }
            val escaped = breakable.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            // The popup's width less its border, logo, close button and the gaps between them.
            return "<html><div style='width:${(WIDTH - 170).px}px'>$escaped</div></html>"
        }

        /** The listener on the panel and everything in it except buttons, so a click anywhere counts. */
        private fun JComponent.forEachMouseTarget(listener: MouseAdapter) {
            addMouseListener(listener)
            components.filterIsInstance<JComponent>().filter { it !is JButton }.forEach { it.forEachMouseTarget(listener) }
        }
    }
}
