package xdm.app.utils

import com.formdev.flatlaf.util.SystemFileChooser
import xdm.app.AppContext.app
import xdm.app.I8N
import xdm.app.OS
import xdm.app.ui.components.NotificationPopup
import xdm.app.ui.components.MessageBox
import xdm.app.utils.linux.DBusNotifications
import xdm.app.utils.linux.DBusTray
import xdm.app.utils.mac.MacNotifications
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
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * The tray icon, kept so [showTrayNotification] can post balloons through it. Null until
 * [createTray] runs, and on platforms without a system tray.
 */
private var trayIconRef: TrayIcon? = null

/**
 * Installs the tray icon. Returns whether there is one (only logged: a hidden start without a tray
 * is still reachable by launching XDM again).
 */
fun createTray(image: Image): Boolean {
    // Linux panels want a StatusNotifierItem; the AWT tray (XEmbed) is only the fallback, and the
    // Wayland toolkit has none at all.
    if (detectOS() == OS.Linux && DBusTray.install { size -> logoImage(size) }) {
        return true
    }
    if (!SystemTray.isSupported()) {
        Logger.info("SystemTray is not supported")
        return false
    }
    val tray = SystemTray.getSystemTray()
    // On macOS the menu bar expects a small, monochrome icon that adapts to the
    // light/dark appearance. Windows gets the edge-to-edge logo at each notification-area size
    // (16 px at 100%, up to 32 px at 200%), so the icon fills its slot and AWT picks the variant
    // for the display's scale instead of shrinking the padded 256 px logo. Linux keeps the logo.
    val trayImage = when (detectOS()) {
        OS.MacOS -> createMacTrayImage(tray)
        OS.Windows -> BaseMultiResolutionImage(*listOf(16, 20, 24, 32).map { windowsLogoImage(it) }.toTypedArray())
        else -> image
    }
    val trayIcon = TrayIcon(trayImage)
    trayIcon.isImageAutoSize = true
    // Right click: the same menu the Linux StatusNotifierItem has. The macOS menu-bar icon keeps
    // its click-to-open (Quit lives in the Dock and app menu there).
    if (detectOS() != OS.MacOS) {
        trayIcon.popupMenu = PopupMenu().apply {
            add(MenuItem(I8N.text("MSG_RESTORE")).apply { addActionListener { app.showAppWindow() } })
            addSeparator()
            add(MenuItem(I8N.text("MENU_EXIT")).apply { addActionListener { exitProcess(0) } })
        }
    }
    trayIcon.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            // The right button opens the menu instead.
            if (e.button != MouseEvent.BUTTON1) return
            Logger.info("Tray icon was clicked")
            app.showAppWindow()
        }
    })
    // Fires when a balloon posted by showTrayBalloon is clicked (and on a double-click of the icon
    // itself): both bring up the window.
    trayIcon.addActionListener {
        Logger.info("Tray notification was clicked")
        app.showAppWindow()
    }
    return try {
        tray.add(trayIcon)
        trayIconRef = trayIcon
        true
    } catch (ex: Exception) {
        Logger.error(ex.message, ex)
        false
    }
}

/**
 * Posts a system notification, drawn by the OS shell, so it never takes focus from the window the user
 * is working in. macOS goes through [MacNotifications] (the JDK's balloon never asks for permission, so
 * macOS shows nothing), Linux through the freedesktop notification server, Windows as XDM's own popup
 * ([NotificationPopup]).
 * It is the shell's call whether to show it at all (Focus Assist, Do Not Disturb), so delivery is
 * best-effort. A click runs [onClick] (on any thread), or brings up the window when it is null; the
 * tray-balloon fallback always brings up the window.
 * [onFailed] runs on the EDT when it is known that nothing will appear: no tray icon or notification
 * server, or notifications turned off for XDM in macOS.
 */
fun showTrayNotification(caption: String, message: String, onClick: (() -> Unit)? = null, onFailed: () -> Unit = {}) {
    val text = shortenLongNames(message)
    when {
        detectOS() == OS.MacOS && MacNotifications.isAvailable ->
            MacNotifications.post(caption, text, onClick) { SwingUtilities.invokeLater(onFailed) }

        // XDM's own popup: the tray balloon becomes a toast credited to the Java runtime on Windows 10/11.
        detectOS() == OS.Windows -> NotificationPopup.show(caption, text, onClick)

        detectOS() == OS.Linux ->
            DBusNotifications.post(caption, text, onClick) {
                SwingUtilities.invokeLater { showTrayBalloon(caption, text, onFailed) }
            }

        else -> showTrayBalloon(caption, text, onFailed)
    }
}

/** A long file name in a notification keeps this many characters from its start and its end. */
private const val NAME_HEAD = 50
private const val NAME_TAIL = 10

/**
 * Cuts every run of more than `NAME_HEAD + NAME_TAIL + 1` non-space characters (a long file name) to
 * its first [NAME_HEAD] and last [NAME_TAIL], joined by an ellipsis; the tail keeps the extension.
 */
internal fun shortenLongNames(text: String): String =
    Regex("\\S{${NAME_HEAD + NAME_TAIL + 2},}").replace(text) { it.value.take(NAME_HEAD) + "\u2026" + it.value.takeLast(NAME_TAIL) }

/**
 * The AWT tray balloon: the last resort (a Linux desktop with no notification server, a macOS run
 * outside the app bundle). Its click can't be told apart from a double-click on the icon, so it only
 * brings up the window and [showTrayNotification]'s click action doesn't apply.
 */
private fun showTrayBalloon(caption: String, text: String, onFailed: () -> Unit) {
    val icon = trayIconRef ?: run {
        Logger.info("No tray icon, skipping notification")
        return onFailed()
    }
    try {
        icon.displayMessage(caption, text, TrayIcon.MessageType.INFO)
    } catch (ex: Exception) {
        Logger.error(ex.message, ex)
        onFailed()
    }
}

/**
 * Tells the user that macOS has XDM's notifications turned off and offers to open System Settings.
 * Must run on the EDT.
 */
fun offerMacNotificationSettings(parent: Component?) {
    val window = parent?.let { SwingUtilities.getWindowAncestor(it) ?: it as? JFrame } as? JFrame
    if (MessageBox.confirm(window, I8N.text("MSG_NOTIFICATIONS_OFF_TITLE"), I8N.text("MSG_NOTIFICATIONS_OFF"))) {
        MacNotifications.openSystemSettings()
    }
}

/** Builds a menu-bar friendly monochrome tray icon for macOS. */
private fun createMacTrayImage(tray: SystemTray): Image {
    val baseSize = tray.trayIconSize.height.takeIf { it > 0 } ?: 22
    // Render at 2x for crisp results on Retina displays; auto-size fits it to the bar.
    val raw = trayMacImage(baseSize * 2)
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
        // Empty, or holding something that isn't text (a file copied in a file manager): no text.
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
        return clipboard.getData(DataFlavor.stringFlavor) as? String?
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

/**
 * Shows a native-feeling file/folder picker and returns the selected [File], or null if
 * cancelled. Uses FlatLaf's [SystemFileChooser] (native dialog) on Windows and macOS, but
 * falls back to Swing's [JFileChooser] on Linux because the native chooser can hang under
 * Wayland.
 */
fun chooseFile(parent: Component?, directoriesOnly: Boolean, currentDir: File? = null): File? {
    return if (detectOS() == OS.Linux) {
        val fc = JFileChooser()
        fc.fileSelectionMode =
            if (directoriesOnly) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
        currentDir?.let { fc.currentDirectory = it }
        if (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
    } else {
        val fc = SystemFileChooser()
        fc.fileSelectionMode =
            if (directoriesOnly) SystemFileChooser.DIRECTORIES_ONLY else SystemFileChooser.FILES_ONLY
        currentDir?.let { fc.currentDirectory = it }
        if (fc.showOpenDialog(parent) == SystemFileChooser.APPROVE_OPTION) fc.selectedFile else null
    }
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