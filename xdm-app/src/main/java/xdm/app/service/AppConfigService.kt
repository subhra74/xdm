package xdm.app.service

import xdm.app.ui.components.SortKey

interface AppConfigService {
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
