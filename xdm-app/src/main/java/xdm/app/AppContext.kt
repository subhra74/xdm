package xdm.app

import xdm.app.recording.RecordingSession
import xdm.app.utils.AppLauncher
import xdm.app.utils.AutoStart
import xdm.app.utils.WaylandToolkit
import xdm.app.utils.linux.X11UiScale
import xdm.core.downloaders.TaskInfoDB
import xdm.core.network.http.HttpAuth
import xdm.core.util.Logger
import xdm.integration.BrowserIntegration
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

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

    /** True when this launch found no saved config, i.e. XDM is running for the first time. */
    var firstRun = false
        private set

    /** Answers 401 / 407 for every download client; asks the user through a Swing dialog. */
    val httpAuth: HttpAuth by lazy { HttpAuth(SwingCredentialPrompt) }

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
            firstRun = !File(configDir, AppConfig.CONFIG_FILE).exists()
            config.load()

            // Before anything touches AWT: the toolkit is fixed once it loads.
            WaylandToolkit.apply(config.nativeWayland)
            if (!WaylandToolkit.isActive) X11UiScale.apply()

            // Every download now writes here before being published, so it is the one folder that
            // has to exist before anything starts. Creating it also removes the old failure where
            // an HTTP download died on its first chunk because the download folder was missing.
            if (!File(config.tempFolder).mkdirs() && !File(config.tempFolder).isDirectory) {
                Logger.error("Unable to create temp folder ${config.tempFolder}")
            }

            if (firstRun) {
                // Only a packaged build registers itself. Under `java -jar` or an IDE the entry
                // would point at a JVM and a build-output jar, and outlive the checkout.
                if (AutoStart.isInstallerOwned) {
                    // The MSI created it (or, for another user of a per-machine install, did not);
                    // either way the app writes nothing until the user asks.
                    config.runOnStartup = AutoStart.isEnabled()
                } else if (AppLauncher.isPackaged) {
                    Logger.info("First run: enabling start-on-login")
                    config.runOnStartup = AutoStart.setEnabled(true)
                } else {
                    Logger.info("First run outside a packaged build: not enabling start-on-login")
                }
                config.save()
            } else {
                // Entries written by older versions start XDM without --minimized. No-op on Windows.
                AutoStart.sync()
            }

            // xdm-app:// is registered by the installers (MSI, Linux packages' .desktop file, macOS
            // Info.plist), never by the app (PACKAGING.md §5.4).

            Logger.info("Setting up look-and-feel theme: ${config.theme}")
            AppMain.setupTheme(config.theme)

            Logger.info("Setting up global authenticator...")
            config.applyAuthConfig()

            Logger.info("Loading translations...")
            I8N.loadTexts(config.lang)

            // Taking the integration port is also how XDM decides it is the only instance, so this
            // happens before the UI exists - but after the theme and translations, so both paths
            // out of here can talk to the user. The accept loop starts only once the services and
            // the window are up; connections that arrive meanwhile wait in the listen backlog.
            when (BrowserIntegration.acquire(args)) {
                BrowserIntegration.Acquired.Primary -> {
                    db.loadRecords()
                    app.run(args)
                    // Class-list recording only (packaging/recording); the class isn't touched otherwise.
                    System.getProperty(RecordingSession.PROPERTY)?.let { RecordingSession.start(it) }
                    BrowserIntegration.serve()
                }

                BrowserIntegration.Acquired.AnotherInstance -> {
                    Logger.info("XDM is already running; asked it to show its window")
                    exitProcess(0)
                }

                BrowserIntegration.Acquired.PortTaken -> {
                    Logger.error("Port ${BrowserIntegration.PORT} is in use by another program")
                    app.showFatalError(
                        I8N.text("ERR_PORT_IN_USE")?.replace("%s", "${BrowserIntegration.PORT}")
                            ?: "Another program is using port ${BrowserIntegration.PORT}, which XDM needs." +
                            " Please close that program and start XDM again."
                    )
                    exitProcess(1)
                }
            }
            return
        }
        throw IllegalStateException("All services are not initialized properly")
    }
}
