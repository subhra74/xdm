package xdm.app

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.themes.FlatMacDarkLaf
import com.formdev.flatlaf.themes.FlatMacLightLaf
import xdm.app.headless.CliParser
import xdm.app.headless.HeadlessCli
import xdm.app.ui.components.WaylandComboBoxUI
import xdm.app.utils.AppDirs
import xdm.app.utils.ConscryptTls
import xdm.app.utils.WaylandToolkit
import xdm.app.utils.linux.DesktopFontSettings
import xdm.app.utils.windowIconImages
import xdm.core.downloaders.TaskInfoDB
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import java.awt.Insets
import java.awt.RenderingHints
import java.io.File
import javax.swing.JOptionPane
import javax.swing.UIManager
import javax.swing.plaf.FontUIResource
import kotlin.system.exitProcess

object AppMain {
    init {
        System.setProperty("http.KeepAlive.remainingData", "0")
        System.setProperty("http.KeepAlive.queuedConnections", "0")
        System.setProperty("swing.aatext", "true")
        System.setProperty("sun.java2d.d3d", "false")
        System.setProperty("sun.java2d.opengl", "false")
        System.setProperty("sun.java2d.xrender", "false")
        System.setProperty("sun.java2d.metal", "false")
        System.setProperty("sun.java2d.pmoffscreen", "false")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Before anything else: headless mode builds no UI, binds no port and loads no app state.
        if (CliParser.isHeadless(args)) {
            ConscryptTls.install()
            exitProcess(HeadlessCli.run(args))
        }

        val configDir = AppDirs.root.path
        val tempDir = "$configDir${File.separatorChar}tmp"

        File(configDir).mkdirs()
        // Holds cookies, captured request headers and logs: other users of the machine stay out.
        FileUtils.restrictToOwner(File(configDir))
        Logger.init(File(configDir))
        // Before any HTTP client or digest: the shared TLS context (SharedTls) is built once.
        ConscryptTls.install()
        // HotSpot appends "sharing" to java.vm.info while a CDS archive is mapped (APPCDS.md).
        Logger.info("CDS: archive " + if (System.getProperty("java.vm.info").orEmpty().contains("sharing")) "in use" else "not in use")

        Logger.info("Use OKHttp..")
        Logger.info("loading...")
        Logger.info(System.getProperty("java.version") + " " + System.getProperty("os.version"))

        System.setProperty("apple.awt.application.appearance", "system")
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", "XDM")
        System.setProperty("apple.awt.enableTemplateImages", "true")

        Logger.info("Creating dir: $tempDir")
        File(tempDir).mkdirs()

        val appDB = AppDB(configDir)
        val taskDB = TaskInfoDB(configDir)

        if (!args.contains("--no-gc")) {
            Logger.info("Registering periodic GC")
            Thread {
                while (true) {
                    Thread.sleep(15000)
                    //Logger.info("XDM", "Triggering GC")
                    System.gc()
                }
            }.apply {
                name = "periodic-gc"
                isDaemon = true // must never keep the JVM alive on its own
            }.start()
        }

        AppContext.apply {
            db = appDB
            app = AppInstance()
            config = AppConfig(configDir)
            queue = QueueManager()
            platform = PlatformInvoke()
            downloader = DownloadManager(appDB = appDB, taskInfoDB = taskDB, configDir = configDir)
            videoTracker = CapturedVideoTracker()
            taskInfoDB = taskDB
            scheduler = DownloadScheduler(appDB, configDir)
        }.init(args, configDir, tempDir)

        AppContext.scheduler.start()
    }

    /**
     * Installs the FlatLaf look-and-feel for the configured theme. Must be called
     * before any Swing UI is created (i.e. before [AppInstance.run]).
     */
    @JvmStatic
    fun setupTheme(theme: String) {
        // Native Wayland gives Java neither the desktop font nor text antialiasing (both come over
        // XSETTINGS under X11): read them from the desktop portal. FlatLaf takes a "defaultFont" set
        // before setup as its base font; the antialiasing hint goes into Swing's defaults after it.
        val desktop = if (WaylandToolkit.isActive) DesktopFontSettings.read() else null
        desktop?.font?.let { UIManager.put("defaultFont", FontUIResource(it)) }
        // XDM's palette: resources/xdm/app/themes/<LafClassName>.properties, merged over the theme's own.
        FlatLaf.registerCustomDefaultsSource("xdm.app.themes")
        when (theme.lowercase()) {
            "light" -> FlatMacLightLaf.setup()
            else -> FlatMacDarkLaf.setup()
        }
        desktop?.let {
            UIManager.put(RenderingHints.KEY_TEXT_ANTIALIASING, it.textAntialiasing)
            UIManager.put(RenderingHints.KEY_TEXT_LCD_CONTRAST, 140)
        }
        UIManager.put("TableHeader.cellMargins", Insets(0, 10, 0, 0))
        UIManager.put("SplitPaneDivider.gripDotCount", 0)
        UIManager.put("SplitPane.dividerSize", 10)
        // KWin can slide a combo popup under the pointer; see WaylandComboBoxUI.
        if (WaylandToolkit.isActive) UIManager.put("ComboBoxUI", WaylandComboBoxUI::class.java.name)
        // Dialogs made with no owner argument (JDialog(), JOptionPane with a null parent) get Swing's
        // hidden shared frame as owner and take its icon (X11 window manager icon, FlatLaf title pane):
        // without this, the Java cup. A truly ownerless one (JDialog(null as Window?)) does not, and
        // sets windowIconImages itself (docs/windows-and-modality.md).
        JOptionPane.getRootFrame().iconImages = windowIconImages
    }
}
