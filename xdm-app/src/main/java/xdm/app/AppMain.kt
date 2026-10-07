package xdm.app

import com.formdev.flatlaf.themes.FlatMacDarkLaf
import com.formdev.flatlaf.themes.FlatMacLightLaf
import xdm.app.utils.CdsJarPin
import xdm.app.utils.HeapFileSetup
import xdm.app.utils.JitOverride
import xdm.app.utils.WaylandToolkit
import xdm.app.utils.linux.DesktopFontSettings
import xdm.core.downloaders.TaskInfoDB
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import java.awt.Insets
import java.awt.RenderingHints
import java.io.File
import javax.swing.UIManager
import javax.swing.plaf.FontUIResource

object AppMain {
    init {
        System.setProperty("http.KeepAlive.remainingData", "0")
        System.setProperty("http.KeepAlive.queuedConnections", "0")
        System.setProperty("awt.useSystemAAFontSettings", "lcd")
        System.setProperty("swing.aatext", "true")
        System.setProperty("sun.java2d.d3d", "false")
        System.setProperty("sun.java2d.opengl", "false")
        System.setProperty("sun.java2d.xrender", "false")
        System.setProperty("sun.java2d.metal", "false")
        System.setProperty("sun.java2d.pmoffscreen", "false")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // The MSI's heap-file hooks (PACKAGING.md §5.7) exit here, before anything touches the profile.
        HeapFileSetup.handle(args)
        val homeDir = System.getProperty("user.home")
        val configDir = "$homeDir${File.separatorChar}.xdm-app"
        val tempDir = "$configDir${File.separatorChar}tmp"

        File(configDir).mkdirs()
        // Holds cookies, captured request headers and logs: other users of the machine stay out.
        FileUtils.restrictToOwner(File(configDir))
        Logger.init(File(configDir))
        CdsJarPin.repin()
        JitOverride.sync()

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
    }
}
