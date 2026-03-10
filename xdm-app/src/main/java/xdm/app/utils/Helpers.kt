package xdm.app.utils

import xdm.app.AppContext.app
import xdm.core.util.Logger
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.desktop.AppReopenedEvent
import java.awt.desktop.AppReopenedListener
import java.awt.event.ActionEvent

fun createTray(image: Image) {
    if (!SystemTray.isSupported()) {
        Logger.info("SystemTray is not supported")
        return
    }
    val trayIcon = TrayIcon(image)
    trayIcon.isImageAutoSize = true
    trayIcon.addActionListener { e: ActionEvent? -> app.showAppWindow() }
    val tray = SystemTray.getSystemTray()
    try {
        tray.add(trayIcon)
    } catch (ex: Exception) {
        Logger.error(ex.message, ex)
    }
}

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

fun getClipBoardText(): String {
    try {
        return Toolkit.getDefaultToolkit().systemClipboard
            .getData(DataFlavor.stringFlavor) as String
    } catch (e: Exception) {
        xdman.util.Logger.log(e)
    }
    return ""
}