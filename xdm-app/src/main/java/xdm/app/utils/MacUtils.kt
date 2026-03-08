package xdm.app.utils

import xdm.app.AppContext.app
import java.awt.Desktop
import java.awt.Image
import java.awt.Taskbar
import java.awt.desktop.AppReopenedEvent
import java.awt.desktop.AppReopenedListener

fun applyMacOSWindowCustomizations(image: Image) {
    /* Set Dock icon in macOS */
    try {
        Taskbar.getTaskbar().iconImage = image
    } catch (e: UnsupportedOperationException) {
        // Nothing to do
    } catch (e: SecurityException) {
        // Noop
    }
//    try {
//        if (SystemInfo.isMacFullWindowContentSupported) {
//            getRootPane().putClientProperty("apple.awt.transparentTitleBar", true)
//        }
//    } catch (ex: Exception) {
//        // Nothing to do
//    }
    try {
        Desktop.getDesktop()
            .addAppEventListener(
                AppReopenedListener { e: AppReopenedEvent? -> app.showAppWindow() })
    } catch (ex: Exception) {
        // Nothing to do
    }
}