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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI

fun createTray(image: Image) {
    if (!SystemTray.isSupported()) {
        Logger.info("SystemTray is not supported")
        return
    }
    val tray = SystemTray.getSystemTray()
    // On macOS the menu bar expects a small, monochrome icon that adapts to the
    // light/dark appearance. Windows and Linux keep using the original logo.
    val trayImage = if (detectOS() == OS.MacOS) createMacTrayImage(tray) else image
    val trayIcon = TrayIcon(trayImage)
    trayIcon.isImageAutoSize = true
    trayIcon.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent?) {
            Logger.info("Tray icon was clicked")
            app.showAppWindow()
        }
    })
//    trayIcon.addActionListener {
//        Logger.info("Tray icon was clicked")
//        app.showAppWindow()
//    }
    try {
        tray.add(trayIcon)
    } catch (ex: Exception) {
        Logger.error(ex.message, ex)
    }
}

/** Builds a menu-bar friendly monochrome tray icon for macOS. */
private fun createMacTrayImage(tray: SystemTray): Image {
    val baseSize = tray.trayIconSize.height.takeIf { it > 0 } ?: 22
    // Render at 2x for crisp results on Retina displays; auto-size fits it to the bar.
    val raw = createSVGIcon("xdm-tray-mac.svg", baseSize * 2).image
    val color = if (isMacDarkMode()) Color.WHITE else Color(0x26, 0x26, 0x26)
    return tintByAlpha(raw, color)
}

/** Recolors an image to [color] while preserving its alpha channel (keeps edges smooth). */
private fun tintByAlpha(src: Image, color: Color): BufferedImage {
    val w = src.getWidth(null).coerceAtLeast(1)
    val h = src.getHeight(null).coerceAtLeast(1)
    val buf = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = buf.createGraphics()
    g.drawImage(src, 0, 0, null)
    g.dispose()
    val rgb = color.rgb and 0x00FFFFFF
    for (y in 0 until h) {
        for (x in 0 until w) {
            val alpha = (buf.getRGB(x, y) ushr 24) and 0xFF
            buf.setRGB(x, y, (alpha shl 24) or rgb)
        }
    }
    return buf
}

private fun isMacDarkMode(): Boolean {
    return try {
        val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle").start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        output.equals("Dark", ignoreCase = true)
    } catch (e: Exception) {
        false
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

fun initShutdown(){
    when (detectOS()) {
        OS.Windows -> WinUtils.initShutdown()
        OS.Linux -> LinuxUtils.initShutdown()
        OS.MacOS -> MacUtils.initShutdown()
    }
}