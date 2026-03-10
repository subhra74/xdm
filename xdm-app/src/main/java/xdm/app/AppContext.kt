package xdm.app


import kotlinx.serialization.json.Json
import xdm.core.downloaders.TaskInfoDB
import xdm.core.util.Logger
import xdm.integration.BrowserIntegration
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists

object AppContext {

    lateinit var db: AppDB
    lateinit var app: IAppInstance
    lateinit var downloader: DownloadManager
    lateinit var config: IAppConfig
    lateinit var platform: IPlatformInvoke
    lateinit var queue: IQueueManager
    lateinit var appConfig: AppConfigData
    lateinit var videoTracker: ICapturedVideoTracker
    lateinit var defaultDownloadFolder: String
    lateinit var taskInfoDB: TaskInfoDB
    lateinit var configDir: String

    fun init(args: Array<String>, configDir: String, tempDir: String) {
        this.configDir = configDir
        val f = File(System.getProperty("user.home"), "Downloads")
        defaultDownloadFolder = if (f.exists()) f.absolutePath else System.getProperty("user.home")
        appConfig = loadConfig(configDir, tempDir)
        if (::db.isInitialized
            && ::app.isInitialized
            && ::downloader.isInitialized
            && ::config.isInitialized
            && ::platform.isInitialized
            && ::queue.isInitialized
            && ::videoTracker.isInitialized
        ) {
            config.load()
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

    private fun loadConfig(configDir: String, tempDir: String): AppConfigData {
        val configFile = Paths.get(configDir).resolve(CONFIG_FILE)
        var config: AppConfigData? = null
        if (configFile.exists()) {
            try {
                Logger.info("Loading config from file")
                config = Json.decodeFromString(Files.readString(configFile))
                Logger.info("Loading config from file.. done")
            } catch (ex: Exception) {
                //do nothing
            }
        }
        if (config == null) {
            Logger.info("Using default config")
            config = AppConfigData(tempDir)
            try {
                Files.writeString(configFile, Json.encodeToString(config))
            } catch (ex: Exception) {
                Logger.info(ex)
            }
        }
        return config
    }
}
