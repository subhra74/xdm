package xdm.app

import xdm.app.I8N.text
import xdm.app.ui.components.SortKey
import java.io.File

interface IAppConfig {
    fun load()
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
    var autoSelectFolder: Boolean
    var folderIndex: Int
    var sortKey: SortKey?
    var sortAscending: Boolean
    var minVideoSize: Long
    var lang: String
    var speedLimiterEnabled: Boolean
    var speedLimit: Int
    var startDownloadAutomatically: Boolean
    var overwriteExistingFiles: Boolean
    var fileExtensions: List<String>
    var videoExtensions: List<String>
    var blockedHosts: List<String>
    var getServerTime: Boolean
    var maxSegments: Int
    var maxRetries: Int
    var useProxy: Boolean
    var proxyHost: String
    var proxyPort: Int
    var proxyUser: String
    var proxyPass: String
    var haltAfterDownload: Boolean
    var keepAwake: Boolean
    var customCommand: String
    var virusScannerPath: String
    var virusScannerArgs: String
}

class AppConfig : IAppConfig {
    override var autoSelectFolder = false
    override var folderIndex = 0
    override var sortKey: SortKey? = SortKey.DATE
    override var sortAscending = false
    override var minVideoSize: Long = 1024
    override var lang: String = "en"
    override var maxParallelDownloads: Int = 1
    override var showDownloadCompleteWindow: Boolean = true
    override var runVirusScan: Boolean = false
    override var runCommand: Boolean = false
    override var showDownloadProgressWindow: Boolean = true
    override var defaultDownloadFolder: String = File(System.getProperty("user.home"), "Downloads").absolutePath
    override var tempFolder: String = File(System.getProperty("user.home"), ".temp").absolutePath
    override var autoRenameOnConflict: Boolean = true
    override var shutdownAfterAllDone: Boolean = false
    override var speedLimiterEnabled: Boolean = false
    override var speedLimit: Int = 100
    override var startDownloadAutomatically: Boolean = false
    override var overwriteExistingFiles: Boolean = false
    override var fileExtensions: List<String> = listOf("3GP", "7Z", "AVI", "BZ2", "DEB", "DOC", "DOCX", "EXE", "ISO", "DMG",
        "MSI", "PDF", "PPT", "PPTX", "RAR", "RPM", "XLS", "XLSX", "TAR", "JAR", "ZIP", "XZ", "PKG"
    )
    override var videoExtensions: List<String> = listOf("MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
        "MOV", "MPG", "MPEG", "OPUS", "MPD")
    override var blockedHosts: List<String> = listOf("update.microsoft.com", "windowsupdate.com", "thwawte.com")
    override var getServerTime: Boolean = true
    override var maxSegments: Int = 8
    override var maxRetries: Int = 5
    override var useProxy: Boolean = false
    override var proxyHost: String = ""
    override var proxyPort: Int = 8080
    override var proxyUser: String = ""
    override var proxyPass: String = ""
    override var haltAfterDownload: Boolean = false
    override var keepAwake: Boolean = true
    override var customCommand: String = ""
    override var virusScannerPath: String = ""
    override var virusScannerArgs: String = ""

    override fun load() {}

    override val recentFolders: List<String>
        get() = mutableListOf(text("ND_AUTO_CAT"), defaultDownloadFolder)
}
