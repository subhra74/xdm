package xdm.app


import kotlinx.serialization.json.Json
import xdm.app.service.*
import xdm.core.util.Logger
import xdm.integration.BrowserIntegration
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists

object AppContext {

    lateinit var db: DownloadsDBService
    lateinit var app: AppControllerService
    lateinit var downloader: DownloadsControllerService
    lateinit var config: AppConfigService
    lateinit var platform: PlatformService
    lateinit var queue: QueueService
    lateinit var appConfig: AppConfig
    lateinit var videoTracker: VideoTracker

    fun init(args: Array<String>) {
        appConfig = loadConfig()
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
                    db.load()
                    app.run(args)
                },
                {
                    println("Unable to start server")
                })
            return
        }
        throw IllegalStateException("All services are not initialized properly")
    }

    private fun loadConfig(): AppConfig {
        val configDir = Paths.get(System.getProperty("user.home"), CONFIG_DIR)
        val configFile = configDir.resolve(CONFIG_FILE)
        if (!configDir.exists()) {
            Files.createDirectories(configDir)
        }
        var config: AppConfig? = null
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
            config = AppConfig()
            try {
                Files.writeString(configFile, Json.encodeToString(config))
            } catch (ex: Exception) {
                Logger.info(ex)
            }
        }
        return config
    }
}
