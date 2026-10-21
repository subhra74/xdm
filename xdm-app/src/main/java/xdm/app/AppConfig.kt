package xdm.app

import xdm.app.ui.components.SortKey
import xdm.core.CoreConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import java.io.File
import java.net.Authenticator
import java.net.Proxy

/** How the main download list selects and opens rows (Settings > Appearance). */
enum class ListSelectionMode {
    /** Check box tiles, click the title to open. */
    MODERN,

    /** Click selects, double-click opens, right-click menu, as in a file manager. */
    CLASSIC,
}

/** What the app does when a download finishes. */
enum class DownloadCompleteNotification {
    /** The full [xdm.app.ui.screens.DownloadCompleteWindow] dialog. */
    DIALOG,

    /** A system tray balloon/notification; clicking it opens the main window. */
    NOTIFICATION,

    /** Nothing at all. */
    NONE,
}

interface IAppConfig : CoreConfig {
    fun load()
    fun save()
    /** What to show when a download finishes. */
    var downloadCompleteNotification: DownloadCompleteNotification
    /**
     * Legacy view of [downloadCompleteNotification]: true iff the mode is [DownloadCompleteNotification.DIALOG].
     * Kept because it is the first field of the on-disk config format.
     */
    var showDownloadCompleteWindow: Boolean
    var runVirusScan: Boolean
    var runCommand: Boolean
    var showDownloadProgressWindow: Boolean
    var defaultDownloadFolder: String
    var tempFolder: String
    var autoRenameOnConflict: Boolean
    var shutdownAfterAllDone: Boolean
    var maxParallelDownloads: Int
    /**
     * The only remembered category choice: true after the user hand-picked None, so rules preselect
     * nothing for later browser captures; hand-picking a category clears it. Settings shows it inverted
     * as "categorize browser downloads automatically".
     */
    var rememberNone: Boolean
    /** The folder last hand-picked while None was selected; null means [defaultDownloadFolder]. */
    var noneFolder: String?
    /** The last [MAX_FOLDER_HISTORY] hand-chosen folders, most recent first, deduplicated. */
    var folderHistory: List<String>
    var sortKey: SortKey
    var sortAscending: Boolean
    /** User-editable categories. Replaced as a whole on every change, never mutated in place. */
    var categories: List<DownloadCategory>
    /** The categories shipped with the app, used by "Restore defaults". */
    val defaultCategories: List<DownloadCategory>
    var minVideoSize: Long
    var lang: String
    var theme: String
    override var speedLimiterEnabled: Boolean
    override var speedLimit: Int
    var startDownloadAutomatically: Boolean
    var overwriteExistingFiles: Boolean
    var fileExtensions: List<String>
    var defFileExtensions: List<String>
    var videoExtensions: List<String>
    var defVideoExtensions: List<String>
    var blockedHosts: List<String>
    var defBlockedHosts: List<String>
    var getServerTime: Boolean
    override var maxSegments: Int
    override var maxRetries: Int
    override var useProxy: Boolean
    override var proxyHost: String
    override var proxyPort: Int
    override var proxyUser: String
    override var proxyPass: String
    var haltAfterDownload: Boolean
    var keepAwake: Boolean
    var runOnStartup: Boolean
    var customCommand: String
    var virusScannerPath: String
    var virusScannerArgs: String
    /** When true, TLS certificate and hostname checks are skipped for downloads (insecure). */
    var ignoreCertErrors: Boolean
    /** Seconds without data before a connection's read times out and is retried (Advanced settings). */
    override var readTimeoutSeconds: Int
    /**
     * When true, an HLS/DASH manifest the browser keeps re-requesting is fetched once per tab in a
     * short window instead of on every request (Advanced settings). Off by default.
     */
    var skipDuplicateManifests: Boolean
    /**
     * When true, finished downloads are marked as coming from the internet (Zone.Identifier on
     * Windows, com.apple.quarantine on macOS), as a browser would. On by default. See QUARANTINE.md.
     */
    var markDownloadedFiles: Boolean
    /** Batch dialog mode last chosen when opened from the browser's "Download all": true = one batch. */
    var batchAsOneFromBrowser: Boolean
    /** Batch dialog mode last chosen when opened from the clipboard menu: true = one batch. */
    var batchAsOneFromClipboard: Boolean
    /**
     * Linux: use the JetBrains Runtime's native Wayland toolkit on a Wayland session instead of
     * XWayland (Advanced settings, next start). On by default. See [xdm.app.utils.WaylandToolkit].
     */
    var nativeWayland: Boolean
    var listSelectionMode: ListSelectionMode
    fun applyAuthConfig()

    /**
     * Called (on whatever thread made the change) when the categories or the default download folder
     * change, so open dialogs can refresh. Listeners marshal to the EDT themselves.
     */
    fun addChangeListener(listener: () -> Unit)
    fun removeChangeListener(listener: () -> Unit)
}

/** The None folder: the last folder hand-picked under None, else the default download folder. */
val IAppConfig.resolvedNoneFolder: String
    get() = noneFolder ?: defaultDownloadFolder

class AppConfig(private val configDir: String) : IAppConfig {
    companion object {
        /** Largest "Only detect videos larger than" choice in Settings, in MB. */
        const val MAX_MIN_VIDEO_SIZE_MB = 100L
        const val CONFIG_FILE = "xdm-app.config"
        val LEGACY_TEMP_FOLDER: String = File(System.getProperty("user.home"), ".temp").absolutePath
        const val MIN_READ_TIMEOUT_SECONDS = 5
        const val MAX_READ_TIMEOUT_SECONDS = 600
        const val MAX_FOLDER_HISTORY = 10
    }

    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    override fun addChangeListener(listener: () -> Unit) {
        changeListeners.add(listener)
    }

    override fun removeChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }

    private fun fireChanged() = changeListeners.forEach { runCatching { it() } }

    // Read on the browser-integration thread (auto-start captures) and written on the EDT: each is an
    // immutable value replaced in one write, so a reader sees the old value or the new one.
    @Volatile
    override var rememberNone = false

    @Volatile
    override var noneFolder: String? = null

    @Volatile
    override var folderHistory: List<String> = emptyList()
    override var sortKey: SortKey = SortKey.DATE
    override var sortAscending = false
    /** In KB; Settings shows it in MB. */
    override var minVideoSize: Long = 1024
    override var lang: String = "en"
    override var theme: String = "dark"
    override var maxParallelDownloads: Int = 1
    override var downloadCompleteNotification: DownloadCompleteNotification = DownloadCompleteNotification.DIALOG
    override var showDownloadCompleteWindow: Boolean
        // No backing field: the old boolean is just a projection of the three-way mode, so a
        // config written by an older build (which only knows the boolean) still round-trips.
        get() = downloadCompleteNotification == DownloadCompleteNotification.DIALOG
        set(value) {
            downloadCompleteNotification =
                if (value) DownloadCompleteNotification.DIALOG else DownloadCompleteNotification.NONE
        }
    override var runVirusScan: Boolean = false
    override var runCommand: Boolean = false
    override var showDownloadProgressWindow: Boolean = true
    override var defaultDownloadFolder: String = File(System.getProperty("user.home"), "Downloads").absolutePath
        set(value) {
            if (field == value) return
            field = value
            // None downloads follow a new default at once; the hand-picked None folder would otherwise
            // make the setting silently stop mattering.
            noneFolder = null
            fireChanged()
        }
    // Under the config dir on purpose, not java.io.tmpdir: on most Linux distributions /tmp is
    // tmpfs and RAM-backed, and OS temp cleaners delete stale files, which would purge a download
    // left paused for a week. See FILE_PLACEMENT.md.
    override var tempFolder: String = File(configDir, "tmp").absolutePath

    // Declared after defaultDownloadFolder on purpose: Kotlin initializes properties in
    // declaration order, and seeding the built-ins reads that folder. This runs on first
    // run only — a config on disk replaces the list wholesale.
    @Volatile
    override var categories: List<DownloadCategory> = DownloadCategory.defaults(defaultDownloadFolder)
        set(value) {
            field = value.toList()
            fireChanged()
        }
    override val defaultCategories: List<DownloadCategory>
        get() = DownloadCategory.defaults(defaultDownloadFolder)

    override var autoRenameOnConflict: Boolean = true
    override var shutdownAfterAllDone: Boolean = false
    override var speedLimiterEnabled: Boolean = false
    override var speedLimit: Int = 100
    override var startDownloadAutomatically: Boolean = false
    override var overwriteExistingFiles: Boolean = false
    override var defFileExtensions: List<String> = listOf(
        "3GP", "7Z", "AVI", "BZ2", "DEB", "DOC", "DOCX", "EXE", "ISO", "DMG", "MSI", "PDF", "PPT", "PPTX", "RAR", "RPM",
        "XLS", "XLSX", "TAR", "JAR", "ZIP", "XZ", "PKG", "GZ", "TGZ", "ZST", "CAB", "APK", "XAPK", "APPIMAGE",
        "MSIX", "BIN", "IMG", "EPUB", "ODT", "ODS", "ODP", "MP4", "MKV", "WEBM", "MOV", "M4V", "WMV", "FLV",
        "MPG", "MPEG", "MP3", "M4A", "AAC", "FLAC", "WAV", "OGG", "OPUS"
    )
    override var fileExtensions: List<String> = defFileExtensions
    override var defVideoExtensions: List<String> = listOf(
        "MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
        "MOV", "MPG", "MPEG", "OPUS", "MPD", "M4V", "M4A", "OGV", "OGA", "WAV", "FLAC", "3GP", "AVI", "WMV"
    )
    override var videoExtensions: List<String> = defVideoExtensions
    override var defBlockedHosts: List<String> = listOf("update.microsoft.com", "windowsupdate.com", "thwawte.com")
    override var blockedHosts: List<String> = defBlockedHosts
    override var getServerTime: Boolean = true
    override var maxSegments: Int = 8
    override var maxRetries: Int = 5
    override var useProxy: Boolean = false
    override var socksProxy: Boolean = false
    override var proxyHost: String = ""
    override var proxyPort: Int = 8080
    override var proxyUser: String = ""
    /** Kept in memory only: [save] writes an empty string in its slot. */
    override var proxyPass: String = ""
    override var haltAfterDownload: Boolean = false
    override var keepAwake: Boolean = true
    override var runOnStartup: Boolean = false
    override var customCommand: String = ""
    override var virusScannerPath: String = ""
    override var virusScannerArgs: String = ""
    override var ignoreCertErrors: Boolean = false
    override var readTimeoutSeconds: Int = CoreConfig.DEFAULT_READ_TIMEOUT_SECONDS
    override var skipDuplicateManifests: Boolean = false
    override var markDownloadedFiles: Boolean = true
    override var batchAsOneFromBrowser: Boolean = true
    override var batchAsOneFromClipboard: Boolean = false
    override var nativeWayland: Boolean = true
    override var listSelectionMode: ListSelectionMode = ListSelectionMode.CLASSIC

    override fun applyAuthConfig() {
        Authenticator.setDefault(DefaultAuthenticator())
    }

    override fun load() {
        if (!AtomicIO.exists(CONFIG_FILE, configDir)) return
        AtomicIO.readTransacted(CONFIG_FILE, configDir) { load(it) }
            .onFailure { Logger.error("Unable to load config, using defaults: $it") }
        // Older builds saved the proxy password. Keep it for this run, but rewrite the file without
        // it; twice, because a save moves the previous file to `.bak2`.
        if (proxyPass.isNotEmpty()) {
            save()
            save()
        }
    }

    override fun save() {
        AtomicIO.writeTransacted(CONFIG_FILE, configDir, ownerOnly = true) { save(it) }
            .onFailure { Logger.error("Unable to save config: $it") }
    }

    private fun save(out: DataOutputStream) {
        out.writeBoolean(showDownloadCompleteWindow)
        out.writeBoolean(runVirusScan)
        out.writeBoolean(runCommand)
        out.writeBoolean(showDownloadProgressWindow)
        out.writeUTF(defaultDownloadFolder)
        out.writeUTF(tempFolder)
        out.writeBoolean(autoRenameOnConflict)
        out.writeBoolean(shutdownAfterAllDone)
        out.writeInt(maxParallelDownloads)
        out.writeBoolean(rememberNone)
        out.writeUTF(noneFolder ?: "")
        out.writeInt(sortKey.ordinal)
        out.writeBoolean(sortAscending)
        out.writeLong(minVideoSize)
        out.writeUTF(lang)
        out.writeBoolean(speedLimiterEnabled)
        out.writeInt(speedLimit)
        out.writeBoolean(startDownloadAutomatically)
        out.writeBoolean(overwriteExistingFiles)
        writeStringList(out, fileExtensions)
        writeStringList(out, videoExtensions)
        writeStringList(out, blockedHosts)
        out.writeBoolean(getServerTime)
        out.writeInt(maxSegments)
        out.writeInt(maxRetries)
        out.writeBoolean(useProxy)
        out.writeBoolean(socksProxy)
        out.writeUTF(proxyHost)
        out.writeInt(proxyPort)
        out.writeUTF(proxyUser)
        out.writeUTF("") // proxyPass: never written to disk
        out.writeBoolean(haltAfterDownload)
        out.writeBoolean(keepAwake)
        out.writeUTF(customCommand)
        out.writeUTF(virusScannerPath)
        out.writeUTF(virusScannerArgs)
        out.writeBoolean(runOnStartup)
        out.writeUTF(theme)
        writeStringList(out, folderHistory)
        out.writeBoolean(ignoreCertErrors)
        out.writeInt(readTimeoutSeconds)
        writeCategories(out, categories)
        // Appended after the self-contained category block so an older build, which stops
        // reading here, is unaffected.
        out.writeInt(downloadCompleteNotification.ordinal)
        out.writeBoolean(skipDuplicateManifests)
        out.writeBoolean(markDownloadedFiles)
        out.writeBoolean(batchAsOneFromBrowser)
        out.writeBoolean(batchAsOneFromClipboard)
        out.writeBoolean(nativeWayland)
        out.writeInt(listSelectionMode.ordinal)
    }

    private fun load(input: DataInputStream) {
        showDownloadCompleteWindow = input.readBoolean()
        runVirusScan = input.readBoolean()
        runCommand = input.readBoolean()
        showDownloadProgressWindow = input.readBoolean()
        defaultDownloadFolder = input.readUTF()
        tempFolder = input.readUTF().let {
            // Configs written before the temp folder moved under the config dir carry the old
            // default; move them rather than stranding downloads in ~/.temp.
            if (it == LEGACY_TEMP_FOLDER) File(configDir, "tmp").absolutePath else it
        }
        autoRenameOnConflict = input.readBoolean()
        shutdownAfterAllDone = input.readBoolean()
        maxParallelDownloads = input.readInt()
        rememberNone = input.readBoolean()
        noneFolder = input.readUTF().ifEmpty { null }
        sortKey = SortKey.entries[input.readInt()]
        sortAscending = input.readBoolean()
        minVideoSize = input.readLong().let {
            // Settings used to save its MB choice (1..100) here unconverted, as if it were KB.
            if (it in 1..MAX_MIN_VIDEO_SIZE_MB) it * 1024 else it
        }
        lang = input.readUTF()
        speedLimiterEnabled = input.readBoolean()
        speedLimit = input.readInt()
        startDownloadAutomatically = input.readBoolean()
        overwriteExistingFiles = input.readBoolean()
        fileExtensions = readStringList(input)
        videoExtensions = readStringList(input)
        blockedHosts = readStringList(input)
        getServerTime = input.readBoolean()
        maxSegments = input.readInt()
        maxRetries = input.readInt()
        useProxy = input.readBoolean()
        socksProxy = input.readBoolean()
        proxyHost = input.readUTF()
        proxyPort = input.readInt()
        proxyUser = input.readUTF()
        proxyPass = input.readUTF()
        haltAfterDownload = input.readBoolean()
        keepAwake = input.readBoolean()
        customCommand = input.readUTF()
        virusScannerPath = input.readUTF()
        virusScannerArgs = input.readUTF()
        // Trailing fields: tolerate config files written before they existed.
        try {
            runOnStartup = input.readBoolean()
            theme = input.readUTF()
        } catch (e: java.io.EOFException) {
            // older config without these trailing fields; keep defaults
            return
        }
        try {
            folderHistory = readStringList(input).take(MAX_FOLDER_HISTORY)
        } catch (e: java.io.EOFException) {
            return
        }
        try {
            ignoreCertErrors = input.readBoolean()
        } catch (e: java.io.EOFException) {
            // config written before ignoreCertErrors existed; keep the secure default
            return
        }
        try {
            readTimeoutSeconds = input.readInt().coerceIn(MIN_READ_TIMEOUT_SECONDS, MAX_READ_TIMEOUT_SECONDS)
        } catch (e: java.io.EOFException) {
            // config written before readTimeoutSeconds existed; keep the default
            return
        }
        // The category block is last and self-contained: a stale or damaged one falls back to
        // the built-ins instead of failing the whole load and resetting every other setting.
        val categoriesRead = runCatching { readCategories(input) }
            .onSuccess { categories = it }
            .onFailure {
                Logger.error("Unable to read categories, using defaults: $it")
                // Re-seed from the folder this config actually carries, not the one the
                // constructor guessed before the file was read.
                categories = DownloadCategory.defaults(defaultDownloadFolder)
            }
            .isSuccess
        // Written after the categories, so it is only readable when they parsed cleanly and
        // left the stream aligned. Until then the legacy boolean read above already picked
        // DIALOG or NONE, which is the right answer for a config from an older build.
        if (!categoriesRead) return
        val notificationRead = runCatching { DownloadCompleteNotification.entries[input.readInt()] }
            .onSuccess { downloadCompleteNotification = it }
            .isSuccess
        if (!notificationRead) return
        // Config written before this field existed: keep the default (off).
        val skipDupRead = runCatching { input.readBoolean() }
            .onSuccess { skipDuplicateManifests = it }
            .isSuccess
        if (!skipDupRead) return
        // Config written before this field existed: keep the default (on).
        val markRead = runCatching { input.readBoolean() }
            .onSuccess { markDownloadedFiles = it }
            .isSuccess
        if (!markRead) return
        // Config written before the batch dialog modes existed: keep the defaults.
        val batchRead = runCatching {
            batchAsOneFromBrowser = input.readBoolean()
            batchAsOneFromClipboard = input.readBoolean()
        }.isSuccess
        if (!batchRead) return
        // Config written before this field existed: keep the default (on).
        val waylandRead = runCatching { nativeWayland = input.readBoolean() }.isSuccess
        if (!waylandRead) return
        // Config written before this field existed: keep the default (Classic).
        runCatching { ListSelectionMode.entries[input.readInt()] }.onSuccess { listSelectionMode = it }
    }

    private fun writeCategories(out: DataOutputStream, list: List<DownloadCategory>) {
        out.writeInt(list.size)
        list.forEach { cat ->
            out.writeUTF(cat.id)
            out.writeUTF(cat.name)
            writeStringList(out, cat.extensions.toList())
            out.writeBoolean(cat.matchAll)
            writeStringList(out, cat.urlPatterns)
            writeStringList(out, cat.fileNamePatterns)
            out.writeUTF(cat.folder)
            out.writeBoolean(cat.predefined)
            out.writeInt(cat.color ?: -1)
        }
    }

    private fun readCategories(input: DataInputStream): List<DownloadCategory> {
        val size = input.readInt()
        val list = mutableListOf<DownloadCategory>()
        repeat(size) {
            list.add(
                DownloadCategory(
                    id = input.readUTF(),
                    name = input.readUTF(),
                    extensions = LinkedHashSet(readStringList(input)),
                    matchAll = input.readBoolean(),
                    urlPatterns = readStringList(input),
                    fileNamePatterns = readStringList(input),
                    folder = input.readUTF(),
                    predefined = input.readBoolean(),
                    color = input.readInt().takeIf { it >= 0 },
                )
            )
        }
        return list
    }

    private fun writeStringList(out: DataOutputStream, list: List<String>) {
        out.writeInt(list.size)
        list.forEach { out.writeUTF(it) }
    }

    private fun readStringList(input: DataInputStream): List<String> {
        val size = input.readInt()
        val list = mutableListOf<String>()
        repeat(size) {
            list.add(input.readUTF())
        }
        return list
    }

    override fun toProxy(): Proxy? {
        if (!useProxy) return null
        if (socksProxy) {
            return Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress(proxyHost, proxyPort))
        } else {
            return Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress(proxyHost, proxyPort))
        }
    }
}
