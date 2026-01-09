package xdm.app

import com.formdev.flatlaf.themes.FlatMacDarkLaf
import xdm.app.service.impl.*
import xdman.util.Logger
import java.awt.Insets
import javax.swing.UIManager


object AppMain {
    init {
        System.setProperty("http.KeepAlive.remainingData", "0")
        System.setProperty("http.KeepAlive.queuedConnections", "0")
        System.setProperty("awt.useSystemAAFontSettings", "lcd")
        System.setProperty("swing.aatext", "true")
        System.setProperty("sun.java2d.d3d", "false")
        System.setProperty("sun.java2d.opengl", "false")
        System.setProperty("sun.java2d.xrender", "false")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Logger.log("Use OKHttp..")
        Logger.log("loading...")
        Logger.log(System.getProperty("java.version") + " " + System.getProperty("os.version"))

        System.setProperty("apple.awt.application.appearance", "system")
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", "XDM")

        FlatMacDarkLaf.setup()
        UIManager.put("TableHeader.cellMargins", Insets(0, 10, 0, 0))
        UIManager.put("SplitPaneDivider.gripDotCount", 0)
        UIManager.put("SplitPane.dividerSize", 10)

        AppContext.apply {
            db = DownloadsDBServiceImpl()
            app = AppControllerServiceImpl()
            config = AppConfigServiceImpl()
            queue = QueueServiceImpl()
            platform = PlatformServiceImpl()
            downloader = DownloadsControllerServiceImpl()
            videoTracker = VideoTrackerImpl()
        }.init(args)
    }
}
