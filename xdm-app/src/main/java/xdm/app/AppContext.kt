package xdm.app

import xdm.core.downloaders.TaskInfoDB
import xdm.core.util.Logger
import xdm.app.utils.AutoStart
import xdm.integration.BrowserIntegration
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object AppContext {

    lateinit var db: AppDB
    lateinit var app: IAppInstance
    lateinit var downloader: DownloadManager
    lateinit var config: IAppConfig
    lateinit var platform: IPlatformInvoke
    lateinit var queue: IQueueManager

    lateinit var videoTracker: ICapturedVideoTracker
    lateinit var defaultDownloadFolder: String

    /** The startup temp directory; [IAppConfig.tempFolder] defaults to it and may override it. */
    lateinit var tempDir: String
    lateinit var taskInfoDB: TaskInfoDB
    lateinit var configDir: String
    lateinit var scheduler: DownloadScheduler

    val hasScheduler: Boolean
        get() = ::scheduler.isInitialized

    var refreshLinkInProgress = AtomicBoolean(false)
    var refreshLinkId = AtomicLong(-1)

    fun init(args: Array<String>, configDir: String, tempDir: String) {
        this.configDir = configDir
        this.tempDir = tempDir
        val f = File(System.getProperty("user.home"), "Downloads")
        defaultDownloadFolder = if (f.exists()) f.absolutePath else System.getProperty("user.home")

        if (::db.isInitialized
            && ::app.isInitialized
            && ::downloader.isInitialized
            && ::config.isInitialized
            && ::platform.isInitialized
            && ::queue.isInitialized
            && ::videoTracker.isInitialized
            && ::scheduler.isInitialized
        ) {
            val firstRun = !File(configDir, AppConfig.CONFIG_FILE).exists()
            config.load()

            // Every download now writes here before being published, so it is the one folder that
            // has to exist before anything starts. Creating it also removes the old failure where
            // an HTTP download died on its first chunk because the download folder was missing.
            if (!File(config.tempFolder).mkdirs() && !File(config.tempFolder).isDirectory) {
                Logger.error("Unable to create temp folder ${config.tempFolder}")
            }

            if (firstRun) {
                Logger.info("First run: enabling start-on-login")
                config.runOnStartup = AutoStart.setEnabled(true)
                config.save()
            }

            Logger.info("Setting up look-and-feel theme: ${config.theme}")
            AppMain.setupTheme(config.theme)

            Logger.info("Setting up global authenticator...")
            config.applyAuthConfig()

            Logger.info("Loading translations...")
            I8N.loadTexts(config.lang)
            BrowserIntegration.start(
                {
                    db.loadRecords()
                    app.run(args)
                },
                {
                    println("Unable to start server")
                })
            return
        }
        throw IllegalStateException("All services are not initialized properly")
    }
}
