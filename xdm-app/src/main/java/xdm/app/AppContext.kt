package xdm.app


import kotlinx.serialization.json.Json
import xdm.core.downloaders.TaskInfoDB
import xdm.core.util.Logger
import xdm.integration.BrowserIntegration
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.exists

object AppContext {

    lateinit var db: AppDB
    lateinit var app: IAppInstance
    lateinit var downloader: DownloadManager
    lateinit var config: IAppConfig
    lateinit var platform: IPlatformInvoke
    lateinit var queue: IQueueManager

    lateinit var videoTracker: ICapturedVideoTracker
    lateinit var defaultDownloadFolder: String
    lateinit var taskInfoDB: TaskInfoDB
    lateinit var configDir: String

    var refreshLinkInProgress = AtomicBoolean(false)
    var refreshLinkId = AtomicLong(-1)

    fun init(args: Array<String>, configDir: String, tempDir: String) {
        this.configDir = configDir
        val f = File(System.getProperty("user.home"), "Downloads")
        defaultDownloadFolder = if (f.exists()) f.absolutePath else System.getProperty("user.home")

        if (::db.isInitialized
            && ::app.isInitialized
            && ::downloader.isInitialized
            && ::config.isInitialized
            && ::platform.isInitialized
            && ::queue.isInitialized
            && ::videoTracker.isInitialized
        ) {
            config.load()
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
