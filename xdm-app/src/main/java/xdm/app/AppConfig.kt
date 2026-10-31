package xdm.app

import xdm.app.ui.components.SortKey
import xdm.core.CoreConfig
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import xdm.core.util.NotTaggedException
import xdm.core.util.StableId
import xdm.core.util.TagReader
import xdm.core.util.TagWriter
import xdm.core.util.TaggedFile
import xdm.core.util.byStableId
import java.io.File
import java.net.Authenticator
import java.net.Proxy

/** How the main download list selects and opens rows (Settings > Appearance). */
enum class ListSelectionMode(override val id: Int) : StableId {
    /** Check box tiles, click the title to open. */
    MODERN(1),

    /** Click selects, double-click opens, right-click menu, as in a file manager. */
    CLASSIC(2),
}

/** What the app does when a download finishes. */
enum class DownloadCompleteNotification(override val id: Int) : StableId {
    /** The full [xdm.app.ui.screens.DownloadCompleteWindow] dialog. */
    DIALOG(1),

    /** A system tray balloon/notification; clicking it opens the main window. */
    NOTIFICATION(2),

    /** Nothing at all. */
    NONE(3),
}

interface IAppConfig : CoreConfig {
    fun load()
    fun save()
    /** What to show when a download finishes. */
    var downloadCompleteNotification: DownloadCompleteNotification
    /**
     * Legacy view of [downloadCompleteNotification]: true iff the mode is [DownloadCompleteNotification.DIALOG].
     * Not saved; kept for the "don't show again" check box of the dialog.
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
    /**
     * Linux: always use Swing's file chooser instead of the desktop's own dialog through the XDG portal
     * (Advanced settings). Off by default. See [xdm.app.utils.linux.PortalFileChooser].
     */
    var swingFileChooser: Boolean
    var listSelectionMode: ListSelectionMode
    /** Play a sound when a download finishes or fails; ignored while [downloadCompleteNotification] is NONE. */
    var playSound: Boolean
    /** A user-chosen WAV played when a download finishes; blank means the built-in one. */
    var completeSoundFile: String
    /** A user-chosen WAV played when a download fails; blank means the built-in one. */
    var failedSoundFile: String
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

// Field numbers of the config file (see TaggedFile). Never reuse or retype a number; a field that
// is no longer saved keeps its number reserved. 29 is unused: the proxy password is never saved.
private const val F_RUN_VIRUS_SCAN = 1
private const val F_RUN_COMMAND = 2
private const val F_SHOW_PROGRESS_WINDOW = 3
private const val F_DEFAULT_DOWNLOAD_FOLDER = 4
private const val F_TEMP_FOLDER = 5
private const val F_AUTO_RENAME = 6
private const val F_SHUTDOWN_AFTER_ALL_DONE = 7
private const val F_MAX_PARALLEL_DOWNLOADS = 8
private const val F_REMEMBER_NONE = 9
private const val F_NONE_FOLDER = 10
private const val F_SORT_KEY = 11
private const val F_SORT_ASCENDING = 12
private const val F_MIN_VIDEO_SIZE = 13
private const val F_LANG = 14
private const val F_SPEED_LIMITER_ENABLED = 15
private const val F_SPEED_LIMIT = 16
private const val F_START_AUTOMATICALLY = 17
private const val F_OVERWRITE_EXISTING = 18
private const val F_FILE_EXTENSIONS = 19
private const val F_VIDEO_EXTENSIONS = 20
private const val F_BLOCKED_HOSTS = 21
private const val F_GET_SERVER_TIME = 22
private const val F_MAX_SEGMENTS = 23
private const val F_MAX_RETRIES = 24
private const val F_USE_PROXY = 25
private const val F_SOCKS_PROXY = 26
private const val F_PROXY_HOST = 27
private const val F_PROXY_PORT = 28
private const val F_PROXY_USER = 30
private const val F_HALT_AFTER_DOWNLOAD = 31
private const val F_KEEP_AWAKE = 32
private const val F_CUSTOM_COMMAND = 33
private const val F_VIRUS_SCANNER_PATH = 34
private const val F_VIRUS_SCANNER_ARGS = 35
private const val F_RUN_ON_STARTUP = 36
private const val F_THEME = 37
private const val F_FOLDER_HISTORY = 38
private const val F_IGNORE_CERT_ERRORS = 39
private const val F_READ_TIMEOUT_SECONDS = 40
private const val F_CATEGORIES = 41
private const val F_COMPLETE_NOTIFICATION = 42
private const val F_SKIP_DUPLICATE_MANIFESTS = 43
private const val F_MARK_DOWNLOADED_FILES = 44
private const val F_BATCH_AS_ONE_FROM_BROWSER = 45
private const val F_BATCH_AS_ONE_FROM_CLIPBOARD = 46
private const val F_NATIVE_WAYLAND = 47
private const val F_LIST_SELECTION_MODE = 48
private const val F_PLAY_SOUND = 49
private const val F_SOUND_COMPLETE_FILE = 50
private const val F_SOUND_FAILED_FILE = 51
private const val F_SWING_FILE_CHOOSER = 52

// Inside F_CATEGORIES: one F_CATEGORY record per category, in order.
private const val F_CATEGORY = 1
private const val CAT_ID = 1
private const val CAT_NAME = 2
private const val CAT_EXTENSIONS = 3
private const val CAT_MATCH_ALL = 4
private const val CAT_URL_PATTERNS = 5
private const val CAT_FILE_NAME_PATTERNS = 6
private const val CAT_FOLDER = 7
private const val CAT_PREDEFINED = 8
private const val CAT_COLOR = 9

class AppConfig(private val configDir: String) : IAppConfig {
    companion object {
        /** Largest "Only detect videos larger than" choice in Settings, in MB. */
        const val MAX_MIN_VIDEO_SIZE_MB = 100L
        const val CONFIG_FILE = "xdm-app.config"
        const val MIN_READ_TIMEOUT_SECONDS = 5
        const val MAX_READ_TIMEOUT_SECONDS = 600
        const val MAX_FOLDER_HISTORY = 10
        const val MAX_PARALLEL_DOWNLOADS = 256
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
    override var maxParallelDownloads: Int = 3
    override var downloadCompleteNotification: DownloadCompleteNotification = DownloadCompleteNotification.DIALOG
    override var playSound: Boolean = false
    override var completeSoundFile: String = ""
    override var failedSoundFile: String = ""
    override var showDownloadCompleteWindow: Boolean
        // No backing field: just a projection of the three-way mode.
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
    override var getServerTime: Boolean = false
    override var maxSegments: Int = 8
    override var maxRetries: Int = 5
    override var useProxy: Boolean = false
    override var socksProxy: Boolean = false
    override var proxyHost: String = ""
    override var proxyPort: Int = 8080
    override var proxyUser: String = ""
    /** Kept in memory only: [save] never writes it. */
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
    override var swingFileChooser: Boolean = false
    override var listSelectionMode: ListSelectionMode = ListSelectionMode.CLASSIC

    override fun applyAuthConfig() {
        Authenticator.setDefault(DefaultAuthenticator())
    }

    override fun load() {
        if (!AtomicIO.exists(CONFIG_FILE, configDir)) return
        AtomicIO.readTransacted(CONFIG_FILE, configDir) { load(TaggedFile.read(it)) }
            .onFailure {
                if (it is NotTaggedException) {
                    // Written before the tagged format and not read. Deleted with its backups because
                    // older builds saved the proxy password in it.
                    Logger.info("Config is in an old format, using defaults")
                    AtomicIO.delete(CONFIG_FILE, configDir)
                } else {
                    Logger.error("Unable to load config, using defaults: $it")
                }
            }
    }

    override fun save() {
        AtomicIO.writeTransacted(CONFIG_FILE, configDir, ownerOnly = true) { out ->
            TaggedFile.write(out) { save(this) }
        }.onFailure { Logger.error("Unable to save config: $it") }
    }

    /**
     * Fields this build does not know, kept as read and written back on save, so running an older
     * build does not drop settings a newer one saved.
     */
    private var unknownFields = ByteArray(0)

    private fun save(w: TagWriter) {
        w.bool(F_RUN_VIRUS_SCAN, runVirusScan)
        w.bool(F_RUN_COMMAND, runCommand)
        w.bool(F_SHOW_PROGRESS_WINDOW, showDownloadProgressWindow)
        w.string(F_DEFAULT_DOWNLOAD_FOLDER, defaultDownloadFolder)
        w.string(F_TEMP_FOLDER, tempFolder)
        w.bool(F_AUTO_RENAME, autoRenameOnConflict)
        w.bool(F_SHUTDOWN_AFTER_ALL_DONE, shutdownAfterAllDone)
        w.int(F_MAX_PARALLEL_DOWNLOADS, maxParallelDownloads)
        w.bool(F_REMEMBER_NONE, rememberNone)
        w.stringOrNull(F_NONE_FOLDER, noneFolder)
        w.int(F_SORT_KEY, sortKey.id)
        w.bool(F_SORT_ASCENDING, sortAscending)
        w.long(F_MIN_VIDEO_SIZE, minVideoSize)
        w.string(F_LANG, lang)
        w.bool(F_SPEED_LIMITER_ENABLED, speedLimiterEnabled)
        w.int(F_SPEED_LIMIT, speedLimit)
        w.bool(F_START_AUTOMATICALLY, startDownloadAutomatically)
        w.bool(F_OVERWRITE_EXISTING, overwriteExistingFiles)
        w.strings(F_FILE_EXTENSIONS, fileExtensions)
        w.strings(F_VIDEO_EXTENSIONS, videoExtensions)
        w.strings(F_BLOCKED_HOSTS, blockedHosts)
        w.bool(F_GET_SERVER_TIME, getServerTime)
        w.int(F_MAX_SEGMENTS, maxSegments)
        w.int(F_MAX_RETRIES, maxRetries)
        w.bool(F_USE_PROXY, useProxy)
        w.bool(F_SOCKS_PROXY, socksProxy)
        w.string(F_PROXY_HOST, proxyHost)
        w.int(F_PROXY_PORT, proxyPort)
        w.string(F_PROXY_USER, proxyUser)
        // proxyPass: never written to disk
        w.bool(F_HALT_AFTER_DOWNLOAD, haltAfterDownload)
        w.bool(F_KEEP_AWAKE, keepAwake)
        w.string(F_CUSTOM_COMMAND, customCommand)
        w.string(F_VIRUS_SCANNER_PATH, virusScannerPath)
        w.string(F_VIRUS_SCANNER_ARGS, virusScannerArgs)
        w.bool(F_RUN_ON_STARTUP, runOnStartup)
        w.string(F_THEME, theme)
        w.strings(F_FOLDER_HISTORY, folderHistory)
        w.bool(F_IGNORE_CERT_ERRORS, ignoreCertErrors)
        w.int(F_READ_TIMEOUT_SECONDS, readTimeoutSeconds)
        w.message(F_CATEGORIES) { categories.forEach { writeCategory(this, it) } }
        w.int(F_COMPLETE_NOTIFICATION, downloadCompleteNotification.id)
        w.bool(F_SKIP_DUPLICATE_MANIFESTS, skipDuplicateManifests)
        w.bool(F_MARK_DOWNLOADED_FILES, markDownloadedFiles)
        w.bool(F_BATCH_AS_ONE_FROM_BROWSER, batchAsOneFromBrowser)
        w.bool(F_BATCH_AS_ONE_FROM_CLIPBOARD, batchAsOneFromClipboard)
        w.bool(F_NATIVE_WAYLAND, nativeWayland)
        w.int(F_LIST_SELECTION_MODE, listSelectionMode.id)
        w.bool(F_PLAY_SOUND, playSound)
        w.string(F_SOUND_COMPLETE_FILE, completeSoundFile)
        w.string(F_SOUND_FAILED_FILE, failedSoundFile)
        w.bool(F_SWING_FILE_CHOOSER, swingFileChooser)
        w.raw(unknownFields)
    }

    private fun load(r: TagReader) {
        val unknown = TagWriter()
        // Applied after the loop: setting the default folder clears noneFolder, and the built-in
        // categories are seeded from the folder this config carries.
        var defaultFolder = defaultDownloadFolder
        var none: String? = null
        var cats: List<DownloadCategory>? = null
        while (true) {
            when (r.next()) {
                -1 -> break
                F_RUN_VIRUS_SCAN -> runVirusScan = r.bool()
                F_RUN_COMMAND -> runCommand = r.bool()
                F_SHOW_PROGRESS_WINDOW -> showDownloadProgressWindow = r.bool()
                F_DEFAULT_DOWNLOAD_FOLDER -> defaultFolder = r.string()
                F_TEMP_FOLDER -> tempFolder = r.string()
                F_AUTO_RENAME -> autoRenameOnConflict = r.bool()
                F_SHUTDOWN_AFTER_ALL_DONE -> shutdownAfterAllDone = r.bool()
                F_MAX_PARALLEL_DOWNLOADS -> maxParallelDownloads = r.int()
                F_REMEMBER_NONE -> rememberNone = r.bool()
                F_NONE_FOLDER -> none = r.string()
                F_SORT_KEY -> SortKey.entries.toTypedArray().byStableId(r.int())?.let { sortKey = it }
                F_SORT_ASCENDING -> sortAscending = r.bool()
                F_MIN_VIDEO_SIZE -> minVideoSize = r.long()
                F_LANG -> lang = r.string()
                F_SPEED_LIMITER_ENABLED -> speedLimiterEnabled = r.bool()
                F_SPEED_LIMIT -> speedLimit = r.int()
                F_START_AUTOMATICALLY -> startDownloadAutomatically = r.bool()
                F_OVERWRITE_EXISTING -> overwriteExistingFiles = r.bool()
                F_FILE_EXTENSIONS -> fileExtensions = r.strings()
                F_VIDEO_EXTENSIONS -> videoExtensions = r.strings()
                F_BLOCKED_HOSTS -> blockedHosts = r.strings()
                F_GET_SERVER_TIME -> getServerTime = r.bool()
                F_MAX_SEGMENTS -> maxSegments = r.int()
                F_MAX_RETRIES -> maxRetries = r.int()
                F_USE_PROXY -> useProxy = r.bool()
                F_SOCKS_PROXY -> socksProxy = r.bool()
                F_PROXY_HOST -> proxyHost = r.string()
                F_PROXY_PORT -> proxyPort = r.int()
                F_PROXY_USER -> proxyUser = r.string()
                F_HALT_AFTER_DOWNLOAD -> haltAfterDownload = r.bool()
                F_KEEP_AWAKE -> keepAwake = r.bool()
                F_CUSTOM_COMMAND -> customCommand = r.string()
                F_VIRUS_SCANNER_PATH -> virusScannerPath = r.string()
                F_VIRUS_SCANNER_ARGS -> virusScannerArgs = r.string()
                F_RUN_ON_STARTUP -> runOnStartup = r.bool()
                F_THEME -> theme = r.string()
                F_FOLDER_HISTORY -> folderHistory = r.strings().take(MAX_FOLDER_HISTORY)
                F_IGNORE_CERT_ERRORS -> ignoreCertErrors = r.bool()
                F_READ_TIMEOUT_SECONDS ->
                    readTimeoutSeconds = r.int().coerceIn(MIN_READ_TIMEOUT_SECONDS, MAX_READ_TIMEOUT_SECONDS)
                F_CATEGORIES -> cats = readCategories(r.message())
                F_COMPLETE_NOTIFICATION -> DownloadCompleteNotification.entries.toTypedArray().byStableId(r.int())
                    ?.let { downloadCompleteNotification = it }
                F_SKIP_DUPLICATE_MANIFESTS -> skipDuplicateManifests = r.bool()
                F_MARK_DOWNLOADED_FILES -> markDownloadedFiles = r.bool()
                F_BATCH_AS_ONE_FROM_BROWSER -> batchAsOneFromBrowser = r.bool()
                F_BATCH_AS_ONE_FROM_CLIPBOARD -> batchAsOneFromClipboard = r.bool()
                F_NATIVE_WAYLAND -> nativeWayland = r.bool()
                F_LIST_SELECTION_MODE -> ListSelectionMode.entries.toTypedArray().byStableId(r.int())
                    ?.let { listSelectionMode = it }
                F_PLAY_SOUND -> playSound = r.bool()
                F_SOUND_COMPLETE_FILE -> completeSoundFile = r.string()
                F_SOUND_FAILED_FILE -> failedSoundFile = r.string()
                F_SWING_FILE_CHOOSER -> swingFileChooser = r.bool()
                else -> unknown.raw(r.rawField())
            }
        }
        defaultDownloadFolder = defaultFolder
        noneFolder = none
        categories = cats ?: DownloadCategory.defaults(defaultDownloadFolder)
        unknownFields = unknown.toByteArray()
    }

    private fun writeCategory(w: TagWriter, cat: DownloadCategory) = w.message(F_CATEGORY) {
        string(CAT_ID, cat.id)
        string(CAT_NAME, cat.name)
        strings(CAT_EXTENSIONS, cat.extensions.toList())
        bool(CAT_MATCH_ALL, cat.matchAll)
        strings(CAT_URL_PATTERNS, cat.urlPatterns)
        strings(CAT_FILE_NAME_PATTERNS, cat.fileNamePatterns)
        string(CAT_FOLDER, cat.folder)
        bool(CAT_PREDEFINED, cat.predefined)
        cat.color?.let { int(CAT_COLOR, it) }
    }

    private fun readCategories(r: TagReader): List<DownloadCategory> {
        val list = mutableListOf<DownloadCategory>()
        while (true) {
            when (r.next()) {
                -1 -> return list
                F_CATEGORY -> {
                    // A damaged category is dropped, not the rest of the config.
                    val cat = r.message()
                    runCatching { readCategory(cat) }
                        .onSuccess { it?.let(list::add) }
                        .onFailure { Logger.error("Unable to read a category: $it") }
                }
                else -> r.skip()
            }
        }
    }

    /** Null for a category without an id or a folder. */
    private fun readCategory(r: TagReader): DownloadCategory? {
        var id: String? = null
        var name = ""
        var extensions = emptyList<String>()
        var matchAll = false
        var urlPatterns = emptyList<String>()
        var fileNamePatterns = emptyList<String>()
        var folder: String? = null
        var predefined = false
        var color: Int? = null
        while (true) {
            when (r.next()) {
                -1 -> break
                CAT_ID -> id = r.string()
                CAT_NAME -> name = r.string()
                CAT_EXTENSIONS -> extensions = r.strings()
                CAT_MATCH_ALL -> matchAll = r.bool()
                CAT_URL_PATTERNS -> urlPatterns = r.strings()
                CAT_FILE_NAME_PATTERNS -> fileNamePatterns = r.strings()
                CAT_FOLDER -> folder = r.string()
                CAT_PREDEFINED -> predefined = r.bool()
                CAT_COLOR -> color = r.int()
                else -> r.skip()
            }
        }
        return DownloadCategory(
            id = id ?: return null,
            name = name,
            extensions = LinkedHashSet(extensions),
            matchAll = matchAll,
            urlPatterns = urlPatterns,
            fileNamePatterns = fileNamePatterns,
            folder = folder ?: return null,
            predefined = predefined,
            color = color,
        )
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
