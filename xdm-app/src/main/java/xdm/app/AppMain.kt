package xdm.app

import com.formdev.flatlaf.themes.FlatMacDarkLaf
import xdm.app.controllers.DownloadHostController
import xdm.app.data.AppDB
import xdm.app.service.impl.*
import xdm.core.downloaders.TaskInfoDB
import xdman.util.Logger
import java.awt.Insets
import java.io.File
import javax.swing.UIManager

//java -XX:+UseShenandoahGC -XX:+UnlockExperimentalVMOptions -XX:MinMetaspaceFreeRatio=1 -XX:MaxMetaspaceFreeRatio=2 -XX:ShenandoahGuaranteedGCInterval=30 -XX:ShenandoahUncommitDelay=10 -XX:+ClassUnloading -XX:+ClassUnloadingWithConcurrentMark  -XX:-DisableExplicitGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -Xms4m -XX:-AlwaysActAsServerClassMachine -jar /Users/subhro/Documents/xdm-app.jar
//java.exe  -XX:+UseZGC -XX:MinMetaspaceFreeRatio=1 -XX:MaxMetaspaceFreeRatio=2 -XX:ZCollectionInterval=30 -XX:ZUncommitDelay=10 -XX:+ClassUnloading -XX:+ClassUnloadingWithConcurrentMark -XX:-AlwaysPreTouch -XX:-ZProactive -XX:-DisableExplicitGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -Xms4m -jar C:\Users\subhro\Desktop\xdm-app_5.jar
//xdm-app -Xms5m -XX:MaxHeapFree=5m -XX:MaximumYoungGenerationSizePercent=5
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

        val homeDir = System.getProperty("user.home")
        val configDir = "$homeDir${File.separatorChar}.xdm-app"

        val f = File(configDir)
        f.mkdirs()

        val appDB = AppDB(configDir)
        val taskDB = TaskInfoDB(configDir)

        AppContext.apply {
            db = appDB
            app = AppControllerServiceImpl()
            config = AppConfigServiceImpl()
            queue = QueueServiceImpl()
            platform = PlatformServiceImpl()
            downloader = DownloadHostController(appDB = appDB, taskInfoDB = taskDB, configDir = configDir)
            videoTracker = VideoTrackerImpl()
            taskInfoDB = taskDB
        }.init(args, configDir)
    }
}
