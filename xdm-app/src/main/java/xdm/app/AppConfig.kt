package xdm.app

import xdm.app.I8N.text
import xdm.app.ui.components.SortKey
import xdm.core.CoreConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    var autoSelectFolder: Boolean
    var folderIndex: Int
    var sortKey: SortKey
    var sortAscending: Boolean
    var minVideoSize: Long
    var lang: String
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
    var customCommand: String
    var virusScannerPath: String
    var virusScannerArgs: String
    fun applyAuthConfig()
}

class AppConfig(private val configDir: String) : IAppConfig {
    override var autoSelectFolder = false
    override var folderIndex = 0
    override var sortKey: SortKey = SortKey.DATE
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
    override var customCommand: String = ""
    override var virusScannerPath: String = ""
    override var virusScannerArgs: String = ""

    override fun applyAuthConfig() {
        Authenticator.setDefault(DefaultAuthenticator())
    }

    override fun load() {
        val configFile = File(configDir, "xdm-app.config")
        if (configFile.exists()) {
            FileInputStream(configFile).use { fs ->
                DataInputStream(fs).use { ds ->
                    load(ds)
                }
            }
        }
    }

    override fun save() {
        val configFile = File(configDir, "xdm-app.config")
        FileOutputStream(configFile).use { fs ->
            DataOutputStream(fs).use { ds ->
                save(ds)
            }
        }
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
    }

    private fun load(input: DataInputStream) {
        showDownloadCompleteWindow = input.readBoolean()
        runVirusScan = input.readBoolean()
        runCommand = input.readBoolean()
        showDownloadProgressWindow = input.readBoolean()
        defaultDownloadFolder = input.readUTF()
        tempFolder = input.readUTF()
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
        get() = mutableListOf(text("ND_AUTO_CAT"), defaultDownloadFolder)

    override fun toProxy(): Proxy? {
        if (!useProxy) return null
        if (socksProxy) {
            return Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress(proxyHost, proxyPort))
        } else {
            return Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress(proxyHost, proxyPort))
        }
    }
}
