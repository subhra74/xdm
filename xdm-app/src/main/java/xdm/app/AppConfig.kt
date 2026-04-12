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

    override fun load() {}

    override val recentFolders: List<String>
        get() = mutableListOf(text("ND_AUTO_CAT"), defaultDownloadFolder)
}
