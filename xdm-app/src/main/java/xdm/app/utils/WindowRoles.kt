package xdm.app.utils

import xdm.app.ui.screens.AppWindow
import java.awt.Component
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/*
 * Helpers for the window roles in docs/windows-and-modality.md: which window owns a dialog, where an
 * independent window opens, and windows of which only one may be open.
 */

/**
 * [component]'s window if it is on screen, else null. Hidden (the main window in the tray) or
 * minimized, it is no good as an owner: an owned window has no taskbar button on Windows, Windows
 * hides it along with a minimized owner, and native Wayland gives it no parent surface unless the
 * owner is mapped. Not worth centring on either.
 */
fun shownWindow(component: Component?): Window? {
    val window = component as? Window ?: component?.let { SwingUtilities.getWindowAncestor(it) } ?: return null
    if (!window.isShowing) return null
    val minimized = generateSequence(window) { it.owner }
        .any { it is Frame && (it.extendedState and Frame.ICONIFIED) != 0 }
    return window.takeUnless { minimized }
}

/** The main window while it is on screen: where an independent window opens. Null centres it on the screen. */
fun mainWindowOnScreen(): Window? = shownWindow(Frame.getFrames().firstOrNull { it is AppWindow })

/** Shows [window] again, restoring it if minimized, and raises it. */
fun bringToFront(window: Window) {
    if (window is Frame && (window.extendedState and Frame.ICONIFIED) != 0) {
        window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
    }
    window.isVisible = true
    window.toFront()
}

/**
 * Windows of which one is open at a time per key (Settings, a download's Properties): opening
 * another brings the open one forward instead. Needed once they stopped being modal, since nothing
 * else stops a second copy now. EDT only.
 */
object SingleWindows {
    private val open = HashMap<Any, Window>()

    /** Brings forward the window open under [key], or shows the one [create] makes. */
    fun show(key: Any, create: () -> Window) {
        open[key]?.takeIf { it.isDisplayable }?.let { bringToFront(it); return }
        val window = create()
        open[key] = window
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                if (open[key] === window) open.remove(key)
            }
        })
        window.isVisible = true
    }
}
