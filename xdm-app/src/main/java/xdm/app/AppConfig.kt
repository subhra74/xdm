package xdm.app

import xdm.app.I8N.text
import xdm.app.ui.components.SortKey
import xdm.core.CoreConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import java.io.File
import java.net.Authenticator
import java.net.Proxy

interface IAppConfig : CoreConfig {
    fun load()
    fun save()
    var showDownloadCompleteWindow: Boolean
    var runVirusScan: Boolean
    var runCommand: Boolean
    var showDownloadProgressWindow: Boolean
    var defaultDownloadFolder: String
    var tempFolder: String
    var autoRenameOnConflict: Boolean
    var shutdownAfterAllDone: Boolean
    var maxParallelDownloads: Int
    val recentFolders: List<String>
    /** Folders picked via "Browse", most recent first; persisted. */
    var savedFolders: List<String>
    var autoSelectFolder: Boolean
    var folderIndex: Int
    var sortKey: SortKey
    var sortAscending: Boolean
    /** User-editable file categories, in match order (first match wins). */
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
    fun applyAuthConfig()
}

class AppConfig(private val configDir: String) : IAppConfig {
    companion object {
        const val CONFIG_FILE = "xdm-app.config"
        val LEGACY_TEMP_FOLDER: String = File(System.getProperty("user.home"), ".temp").absolutePath
        const val MIN_READ_TIMEOUT_SECONDS = 5
        const val MAX_READ_TIMEOUT_SECONDS = 600
    }

    override var autoSelectFolder = false
    override var folderIndex = 0
    override var savedFolders: List<String> = emptyList()
    override var sortKey: SortKey = SortKey.DATE
    override var sortAscending = false
    override var minVideoSize: Long = 1024
    override var lang: String = "en"
    override var theme: String = "dark"
    override var maxParallelDownloads: Int = 1
    override var showDownloadCompleteWindow: Boolean = true
    override var runVirusScan: Boolean = false
    override var runCommand: Boolean = false
    override var showDownloadProgressWindow: Boolean = true
    override var defaultDownloadFolder: String = File(System.getProperty("user.home"), "Downloads").absolutePath
    // Under the config dir on purpose, not java.io.tmpdir: on most Linux distributions /tmp is
    // tmpfs and RAM-backed, and OS temp cleaners delete stale files, which would purge a download
    // left paused for a week. See FILE_PLACEMENT.md.
    override var tempFolder: String = File(configDir, "tmp").absolutePath

    // Declared after defaultDownloadFolder on purpose: Kotlin initializes properties in
    // declaration order, and seeding the built-ins reads that folder. This runs on first
    // run only — a config on disk replaces the list wholesale.
    override var categories: List<DownloadCategory> = DownloadCategory.defaults(defaultDownloadFolder)
    override val defaultCategories: List<DownloadCategory>
        get() = DownloadCategory.defaults(defaultDownloadFolder)

    override var autoRenameOnConflict: Boolean = true
    override var shutdownAfterAllDone: Boolean = false
    override var speedLimiterEnabled: Boolean = false
    override var speedLimit: Int = 100
    override var startDownloadAutomatically: Boolean = false
    override var overwriteExistingFiles: Boolean = false
    override var defFileExtensions: List<String> = listOf(
        "3GP", "7Z", "AVI", "BZ2", "DEB", "DOC", "DOCX", "EXE", "ISO", "DMG",
        "MSI", "PDF", "PPT", "PPTX", "RAR", "RPM", "XLS", "XLSX", "TAR", "JAR", "ZIP", "XZ", "PKG"
    )
    override var fileExtensions: List<String> = defFileExtensions
    override var defVideoExtensions: List<String> = listOf(
        "MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
        "MOV", "MPG", "MPEG", "OPUS", "MPD"
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
    override var proxyPass: String = ""
    override var haltAfterDownload: Boolean = false
    override var keepAwake: Boolean = true
    override var runOnStartup: Boolean = false
    override var customCommand: String = ""
    override var virusScannerPath: String = ""
    override var virusScannerArgs: String = ""
    override var ignoreCertErrors: Boolean = false
    override var readTimeoutSeconds: Int = CoreConfig.DEFAULT_READ_TIMEOUT_SECONDS

    override fun applyAuthConfig() {
        Authenticator.setDefault(DefaultAuthenticator())
    }

    override fun load() {
        AtomicIO.readTransacted(CONFIG_FILE, configDir) { load(it) }
            .onFailure { Logger.error("Unable to load config, using defaults: $it") }
    }

    override fun save() {
        AtomicIO.writeTransacted(CONFIG_FILE, configDir) { save(it) }
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
        out.writeBoolean(autoSelectFolder)
        out.writeInt(folderIndex)
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
        out.writeUTF(proxyPass)
        out.writeBoolean(haltAfterDownload)
        out.writeBoolean(keepAwake)
        out.writeUTF(customCommand)
        out.writeUTF(virusScannerPath)
        out.writeUTF(virusScannerArgs)
        out.writeBoolean(runOnStartup)
        out.writeUTF(theme)
        writeStringList(out, savedFolders)
        out.writeBoolean(ignoreCertErrors)
        out.writeInt(readTimeoutSeconds)
        writeCategories(out, categories)
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
        autoSelectFolder = input.readBoolean()
        folderIndex = input.readInt()
        sortKey = SortKey.entries[input.readInt()]
        sortAscending = input.readBoolean()
        minVideoSize = input.readLong()
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
            savedFolders = readStringList(input)
        } catch (e: java.io.EOFException) {
            // config written before savedFolders existed
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
        runCatching { readCategories(input) }
            .onSuccess { categories = it }
            .onFailure {
                Logger.error("Unable to read categories, using defaults: $it")
                // Re-seed from the folder this config actually carries, not the one the
                // constructor guessed before the file was read.
                categories = DownloadCategory.defaults(defaultDownloadFolder)
            }
    }

    private fun writeCategories(out: DataOutputStream, list: List<DownloadCategory>) {
        out.writeInt(list.size)
        list.forEach { cat ->
            out.writeUTF(cat.id)
            out.writeUTF(cat.name)
            writeStringList(out, cat.extensions.toList())
            out.writeUTF(cat.folder)
            out.writeBoolean(cat.predefined)
            out.writeUTF(cat.icon)
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
                    folder = input.readUTF(),
                    predefined = input.readBoolean(),
                    icon = input.readUTF(),
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

    override val recentFolders: List<String>
        get() = (listOf(text("ND_AUTO_CAT"), defaultDownloadFolder) + savedFolders).distinct()

    override fun toProxy(): Proxy? {
        if (!useProxy) return null
        if (socksProxy) {
            return Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress(proxyHost, proxyPort))
        } else {
            return Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress(proxyHost, proxyPort))
        }
    }
}
