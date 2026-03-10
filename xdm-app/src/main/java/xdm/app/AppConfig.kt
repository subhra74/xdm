package xdm.app

import xdm.app.ui.components.SortKey
import xdman.ui.res.StringResource
import java.io.File

interface IAppConfig {
    fun load()
    fun shouldShowDownloadCompleteWindow(): Boolean
    fun shouldRunVirusScan(): Boolean
    fun shouldRunCommand(): Boolean
    fun shouldShowDownloadProgressWindow(): Boolean
    val defaultDownloadFolder: String?
    val tempFolder: String?
    fun shouldAutoRenameOnConflict(): Boolean
    fun shouldShutdownAfterAllDone(): Boolean
    val maxParallelDownloads: Int
    val recentFolders: List<String?>?
    var autoSelectFolder: Boolean
    val folderIndex: Int
    var sortKey: SortKey?
    var sortAscending: Boolean
    var minVideoSize: Long
}

class AppConfig : IAppConfig {
    override var autoSelectFolder = false
    override var folderIndex = 0
    override var sortKey: SortKey? = SortKey.DATE
    override var sortAscending = false
    override var minVideoSize: Long = 1024
    override val maxParallelDownloads: Int = 4

    override fun load() {}

    override fun shouldShowDownloadCompleteWindow(): Boolean {
        return true
    }

    override fun shouldRunVirusScan(): Boolean {
        return false
    }

    override fun shouldRunCommand(): Boolean {
        return false
    }

    override fun shouldShowDownloadProgressWindow(): Boolean {
        return true
    }

    override val defaultDownloadFolder: String?
        get() = File(System.getProperty("user.home"), "Downloads").absolutePath

    override val tempFolder: String?
        get() = File(System.getProperty("user.home"), ".temp").absolutePath

    override fun shouldAutoRenameOnConflict(): Boolean {
        return true
    }

    override fun shouldShutdownAfterAllDone(): Boolean {
        return false
    }

    override val recentFolders: List<String?>?
        get() = mutableListOf(StringResource.get("ND_AUTO_CAT"), defaultDownloadFolder)
}
