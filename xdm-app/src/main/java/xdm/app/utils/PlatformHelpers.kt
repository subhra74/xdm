package xdm.app.utils

import xdm.app.AppContext.app
import xdm.app.OS
import xdm.core.util.Logger
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.desktop.AppReopenedEvent
import java.awt.desktop.AppReopenedListener
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.File
import java.net.URI

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

fun getClipBoardText(): String? {
    try {
        return Toolkit.getDefaultToolkit().systemClipboard
            .getData(DataFlavor.stringFlavor) as? String?
    } catch (e: Exception) {
        Logger.error(e)
    }
    return null
}

fun setClipBoardText(text: String) {
    try {
        return Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(text), null)
    } catch (e: Exception) {
        Logger.error(e)
    }
}

fun isMacPopupTrigger(e: MouseEvent): Boolean {
    if (detectOS() == OS.MacOS) {
        return (e.modifiersEx and InputEvent.BUTTON1_DOWN_MASK) != 0
                && (e.modifiersEx and InputEvent.CTRL_DOWN_MASK) != 0
    }
    return false
}

fun detectOS(): OS {
    System.getProperty("os.name")?.let { osName ->
        return if (osName.contains("windows", ignoreCase = true)) {
            OS.Windows
        } else if (osName.contains("mac os", ignoreCase = true)) {
            OS.MacOS
        } else {
            OS.Linux
        }
    } ?: return OS.Linux
}

fun openFileExternal(file: String, folder: String?) {
    val os = detectOS()
    val f = File(folder, file)
    when (os) {
        OS.Windows -> WinUtils.open(f)
        OS.Linux -> LinuxUtils.open(f)
        OS.MacOS -> MacUtils.open(f)
        else -> Desktop.getDesktop().open(f)
    }
}


fun openFolderExternal(file: String?, folder: String) {
    val os = detectOS()
    when (os) {
        OS.Windows -> WinUtils.openFolder(folder, file)
        OS.Linux -> {
            val f = File(folder)
            LinuxUtils.open(f)
        }

        OS.MacOS -> MacUtils.openFolder(folder, file)
        else -> {
            val ff = File(folder)
            Desktop.getDesktop().open(ff)
        }
    }
}

fun openWebPage(url: String): Boolean {
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
            return true
        }
    } catch (e: Exception) {
        Logger.error(e)
    }
    return false
}