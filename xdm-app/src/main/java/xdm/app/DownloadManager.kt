package xdm.app

import xdm.app.utils.KeepAwake
import xdm.app.utils.categoryFolderFor
import xdm.app.utils.getFileFolder
import xdm.core.downloaders.*
import xdm.core.downloaders.web.getTempFileFolder
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.readContext
import xdm.core.downloaders.web.saveState
import xdm.core.downloaders.web.streaming.downloader.dash.DashDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.AtomicIO
import xdm.core.util.CoreUtils
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.getFileName
import xdm.core.util.getHeader
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

interface IDownloadManager {
    fun stopDownload(id: Long)
    fun resumeDownload(id: Long)
    fun deleteDownload(id: Long, fromDisk: Boolean)
    fun startHttpDownload(task: HttpDownloadTaskInfo, runNow: Boolean = true)
    fun addVideoDownload(videoId: Long, fileName: String, folder: String?, autoSelectFolder: Boolean)
    fun startHlsDownload(task: HlsDownloadTaskInfo)
    fun startDashDownload(task: DashDownloadTaskInfo)
    fun updateDownloadInfo(id: Long, task: HttpDownloadTaskInfo)
    fun getOriginPage(id: Long): String?
}

/** Builds the engine task for a persisted download, or returns null if its task info is missing. */
typealias DownloaderTaskFactory = (type: DownloadType, id: Long, host: DownloadHost) -> DownloaderTask?

class DownloadManager(
    val appDB: AppDB,
    val taskInfoDB: TaskInfoDB,
    private val configDir: String,
    taskFactory: DownloaderTaskFactory? = null,
) : IDownloadManager {
    private val taskFactory: DownloaderTaskFactory = taskFactory ?: ::createTask

    /** Downloads waiting for a free slot. Guarded by its own monitor; only [pumpQueue] starts tasks. */
    private val queue = ArrayDeque<QueueItem>()
    private val toDelete = mutableSetOf<Long>()
    private val activeSessions = ConcurrentHashMap<Long, DownloaderTask>()
    private val downloadHost = object : DownloadHost {
        override fun onDownloadActivated(id: Long) {
            activeSessions[id]?.let {
                synchronized(appDB) {
                    appDB.getById(id)?.let { e ->
                        e.status = RecordStatus.DOWNLOADING
                    }
                }
                AppContext.app.updateDownloadInView(id)
            }
        }

        override fun onDownloadInit(data: DownloadStatusInfo.InitInfo, downloadType: DownloadType) {
            activeSessions[data.id]?.let {
                synchronized(appDB) {
                    appDB.getById(data.id)?.let { e ->
                        when (downloadType) {
                            DownloadType.Http -> taskInfoDB.getHttpTask(data.id)?.let { t ->
                                val newFileName = if (data.videoExt != null) {
                                    t.fileName + data.videoExt
                                } else {
                                    getFileName(
                                        t.fileName,
                                        t.respectFileName,
                                        data.url,
                                        data.contentDisposition,
                                        data.contentType
                                    )
                                }
                                //TODO: Check if only ext to be updated
                                e.fileName = newFileName
                                t.fileName = newFileName
                                data.fileSize?.let { e.size = it }
                                taskInfoDB.saveHttpTask(t)
                            }

                            DownloadType.Hls -> {}
                            DownloadType.Dash -> {}
                            DownloadType.Torrent -> TODO()
                        }
                        appDB.saveActiveRecords()
                    }
                }
                AppContext.app.updateDownloadInView(data.id)
            }
        }

        override fun onDownloadProgress(event: DownloadStatusInfo.ProgressInfo) {
            var size: Long = -1
            var fileName: String? = null
            activeSessions[event.id]?.let {
                synchronized(appDB) {
                    appDB.getById(event.id)?.let {
                        it.downloaded = event.downloaded
                        it.progress = event.progress
                        it.speed = event.speed
                        it.eta = event.eta
                        size = it.size
                        fileName = it.fileName
                    }
                }
                AppContext.app.updateDownloadInView(event.id)
                AppContext.app.updateProgressWindow(
                    event.id, fileName, event.downloaded, size, event.speed, event.eta, event.progress, event.segments
                )
            }
        }

        override fun onAssembleStart(id: Long) {
            activeSessions[id]?.let {
                var fileName: String? = null
                synchronized(appDB) {
                    appDB.getById(id)?.let { e ->
                        e.status = RecordStatus.ASSEMBLING
                        e.progress = 0
                        e.speed = 0.0f
                        e.eta = 0
                        fileName = e.fileName
                    }
                }
                AppContext.app.updateDownloadInView(id)
                AppContext.app.updateProgressWindow(id, fileName, 0, -1, 0.0f, 0, 0, listOf())
            }
        }

        override fun onAssembleProgress(event: DownloadStatusInfo.AssembleInfo) {
            var downloaded: Long = 0
            var size: Long = -1
            var fileName: String? = null
            activeSessions[event.id]?.let {
                synchronized(appDB) {
                    appDB.getById(event.id)?.let { e ->
                        e.status = RecordStatus.ASSEMBLING
                        e.progress = event.progress
                        e.speed = 0.0f
                        e.eta = 0
                        downloaded = e.downloaded
                        size = e.size
                        fileName = e.fileName
                    }
                }
                AppContext.app.updateDownloadInView(event.id)
                AppContext.app.updateProgressWindow(
                    event.id, fileName, downloaded, size, 0.0f, 0, event.progress, listOf()
                )
            }
        }

        override fun onDownloadSuccess(event: DownloadStatusInfo.FinalInfo) {
            activeSessions.remove(event.id)?.let {
                synchronized(appDB) {
                    appDB.getById(event.id)?.let { e ->
                        e.status = RecordStatus.FINISHED
                        e.fileName = event.finalFileName
                        e.size = event.fileSize
                        appDB.saveActiveRecords()
                        appDB.saveFinishedRecords()
                    }
                }
                AppContext.app.updateDownloadInView(event.id)
                AppContext.app.hideProgressWindow(event.id)
                if (AppContext.config.showDownloadCompleteWindow) {
                    AppContext.app.showDownloadCompleteWindow(
                        event.id, event.finalOutputFolder, event.finalFileName, event.fileSize
                    )
                }
                runPostDownloadActions(event)
                pumpQueue()
            }
        }

        override fun onDownloadFailed(id: Long, error: DownloadError) {
            activeSessions.remove(id)?.let {
                synchronized(appDB) {
                    appDB.getById(id)?.let { e ->
                        e.status = RecordStatus.PAUSED
                        appDB.saveActiveRecords()
                        appDB.savePausedRecords()
                    }
                }
                AppContext.app.updateDownloadInView(id)
                AppContext.app.showProgressError(id, error)
                pumpQueue()
            }
        }

        override fun onDownloadPaused(id: Long, event: PauseEvent) {
            Logger.info("Download paused $id")
            activeSessions.remove(id)?.let {
                synchronized(appDB) {
                    appDB.getById(id)?.let { e ->
                        e.status = RecordStatus.PAUSED
                        appDB.saveActiveRecords()
                        appDB.savePausedRecords()
                    }
                }
                AppContext.app.updateDownloadInView(id)
                AppContext.app.hideProgressWindow(id)
                if (toDelete.contains(id)) {
                    toDelete.remove(id)
                    deleteAfterStopped(id, it)
                }
                pumpQueue()
            }
        }

        override val appDir: String
            get() = AppContext.configDir
        override val applySpeedLimit: Boolean
            get() = AppContext.config.speedLimiterEnabled
        override val speedLimit: Int
            get() = if (AppContext.config.speedLimit > 0) AppContext.config.speedLimit else -1

        override fun getTempDir(id: Long, url: String, contentType: String?, contentDisposition: String?): String {
            taskInfoDB.getHttpTask(id)?.let { t ->
                return t.defaultDownloadFolder
            }
            return AppContext.defaultDownloadFolder
        }

        /**
         * Moves the finished file into [folderPath] under a unique name derived from [fileName]. Never
         * overwrites an existing file: if one appears between choosing the name and moving, the next
         * unique name is tried. Works across drives (see [FileUtils.moveFile]).
         */
        private fun renameFile(
            fileName: String, folderPath: String, tmpFilePath: String, callback: (
                finalName: String, folder: String
            ) -> Unit
        ): CommitResult {
            val folder = File(folderPath)
            if (!folder.isDirectory && !folder.mkdirs()) {
                Logger.error("XDM", "Unable to create output dir: $folderPath")
                return CommitResult.Failed(DownloadError.OutputWriteError)
            }
            val tmpFile = File(tmpFilePath)
            var error: DownloadError = DownloadError.OutputWriteError
            repeat(3) {
                val finalName = FileUtils.getUniqueFileName(folder.absolutePath, fileName)
                val outFile = File(folder, finalName)
                error = FileUtils.moveFile(tmpFile, outFile) ?: run {
                    Logger.info("Success moving file: $tmpFile -> ${outFile.absolutePath}")
                    callback(finalName, folder.absolutePath)
                    return CommitResult.Success(fileName = finalName, outputDir = folder.absolutePath)
                }
                if (!outFile.exists()) return CommitResult.Failed(error)
            }
            return CommitResult.Failed(error)
        }

        override fun commitOutputFile(id: Long, tmpFilePath: String, downloadType: DownloadType): CommitResult {
            when (downloadType) {
                DownloadType.Http -> taskInfoDB.getHttpTask(id)?.let { t ->
                    return renameFile(t.fileName, outputFolder(t.fileName, t.defaultDownloadFolder, t.autoCategorize), tmpFilePath) { finalName, finalFolder ->
                        t.fileName = finalName
                        t.defaultDownloadFolder = finalFolder
                        t.autoCategorize = false
                        taskInfoDB.saveHttpTask(t)
                    }
                }

                DownloadType.Hls -> taskInfoDB.getHlsTask(id)?.let { t ->
                    return renameFile(t.fileName, outputFolder(t.fileName, t.defaultDownloadFolder, t.autoCategorize), tmpFilePath) { finalName, finalFolder ->
                        t.fileName = finalName
                        t.defaultDownloadFolder = finalFolder
                        t.autoCategorize = false
                        taskInfoDB.saveHlsTask(t)
                    }
                }

                DownloadType.Dash -> taskInfoDB.getDashTask(id)?.let { t ->
                    return renameFile(t.fileName, outputFolder(t.fileName, t.defaultDownloadFolder, t.autoCategorize), tmpFilePath) { finalName, finalFolder ->
                        t.fileName = finalName
                        t.defaultDownloadFolder = finalFolder
                        t.autoCategorize = false
                        taskInfoDB.saveDashTask(t)
                    }
                }

                DownloadType.Torrent -> {}
            }

            Logger.error("XDM", "Task not found for commit: $id")
            return CommitResult.Failed(DownloadError.InternalError)
        }

        override fun outputFilePath(id: Long, downloadType: DownloadType, ext: String): String? =
            streamingOutputPath(id, downloadType, ext)
    }

    /**
     * Streaming downloads mux straight into their destination folder (category subfolder included) as
     * a hidden `.<id>.xdm-part<ext>` file, so the commit is a same-folder rename even when the temp
     * folder is on another drive. The name depends only on the id, so a retry reuses it. Null for
     * HTTP, which keeps its temp file in the download folder.
     */
    private fun streamingOutputPath(id: Long, downloadType: DownloadType, ext: String): String? {
        val task: StreamingDownloadTaskInfo = when (downloadType) {
            DownloadType.Hls -> taskInfoDB.getHlsTask(id)
            DownloadType.Dash -> taskInfoDB.getDashTask(id)
            else -> null
        } ?: return null
        val folder = outputFolder(task.fileName, task.defaultDownloadFolder, task.autoCategorize)
        return File(folder, ".$id.xdm-part$ext").absolutePath
    }

    private fun outputFolder(fileName: String, folder: String, autoCategorize: Boolean) =
        if (autoCategorize) categoryFolderFor(fileName, folder) else folder

    override fun stopDownload(id: Long) {
        activeSessions[id]?.let {
            it.stop()
            return
        }
        val removed = synchronized(queue) { queue.removeAll { it.id == id } }
        if (removed) {
            appDB.getById(id)?.let {
                it.status = RecordStatus.PAUSED
                AppContext.app.updateDownloadInView(id)
            }
        }
    }

    override fun resumeDownload(id: Long) {
        activeSessions[id]?.let {
            Logger.info("Attempt made to resume already running download: $id")
            return
        }

        appDB.getById(id)?.let {
            synchronized(queue) {
                if (queue.any { q -> q.id == id }) return
                it.status = RecordStatus.READY
                appDB.saveActiveRecords()
                appDB.savePausedRecords()
                AppContext.app.updateDownloadInView(id)
                queue.add(QueueItem(id, true))
            }
            pumpQueue()
        }
    }

    /** The real engine task for [type], built from its persisted task info. */
    private fun createTask(type: DownloadType, id: Long, host: DownloadHost): DownloaderTask? = when (type) {
        DownloadType.Http -> taskInfoDB.getHttpTask(id)?.let {
            HttpDownloaderTask(it, host, newHttpClient(), configDir, AppContext.config)
        }

        DownloadType.Hls -> taskInfoDB.getHlsTask(id)?.let {
            HlsDownloaderTask(
                taskInfo = it, http = newHttpClient(), muxer = TransmuxingMuxer(AppContext.configDir),
                host = host, configDir = AppContext.configDir, config = AppContext.config
            )
        }

        DownloadType.Dash -> taskInfoDB.getDashTask(id)?.let {
            DashDownloaderTask(
                taskInfo = it, http = newHttpClient(), muxer = TransmuxingMuxer(AppContext.configDir),
                host = host, configDir = AppContext.configDir, config = AppContext.config
            )
        }

        DownloadType.Torrent -> null
    }

    /**
     * Starts or resumes one queued download. Must be called with the [queue] lock held so the
     * [activeSessions] size check in [pumpQueue] and the insert here are atomic.
     * Returns false if the download could not be started (record or task info missing, or the task
     * could not be built).
     */
    private fun launch(item: QueueItem): Boolean {
        val id = item.id
        return try {
            val rec = appDB.getById(id) ?: return false
            val controller = taskFactory(rec.downloadType, id, downloadHost) ?: run {
                Logger.error("XDM", "Task info missing for download $id")
                return false
            }
            activeSessions[id] = controller
            syncKeepAwake()
            rec.status = RecordStatus.DOWNLOADING
            appDB.saveActiveRecords()
            appDB.savePausedRecords()
            AppContext.app.updateDownloadInView(id)
            if (AppContext.config.showDownloadProgressWindow) {
                AppContext.app.showProgressWindow(id, rec.fileName)
            }
            if (item.resume) controller.resume() else controller.start()
            true
        } catch (error: Exception) {
            Logger.error("XDM", "Unable to start download $id", error)
            activeSessions.remove(id)
            syncKeepAwake()
            false
        }
    }

    /** A queued download that could not start: show it as paused instead of leaving it READY. */
    private fun markNotStarted(id: Long) {
        appDB.getById(id)?.let {
            it.status = RecordStatus.PAUSED
            appDB.saveActiveRecords()
            appDB.savePausedRecords()
            AppContext.app.updateDownloadInView(id)
        }
    }

    /** Adds a new download to the end of the queue and starts as many queued downloads as allowed. */
    private fun enqueue(id: Long, resume: Boolean) {
        synchronized(queue) { queue.add(QueueItem(id, resume)) }
        pumpQueue()
    }

    private fun deleteRecord(id: Long) {
        val index = appDB.indexById(id)
        if (index != null) {
            appDB.removeItem(id)
            AppContext.app.deleteDownloadInView(index)
        }
    }

    override fun deleteDownload(id: Long, fromDisk: Boolean) {
        try {
            appDB.getById(id)?.let { rec ->
                if (rec.status == RecordStatus.FINISHED) {
                    deleteRecord(id)
                    if (fromDisk) {
                        val (fileName: String?, folder: String?) = getFileFolder(rec) ?: return
                        if (fileName != null && folder != null) {
                            val fileToDelete = File(folder, fileName)
                            val deleted = fileToDelete.delete()
                            Logger.info("XDM", "Delete file $fileToDelete $deleted")
                        }
                    }
                } else if (rec.status == RecordStatus.PAUSED || rec.status == RecordStatus.READY) {
                    synchronized(queue) {
                        queue.find { it.id == id }?.let {
                            queue.remove(it)
                        }
                    }
                    deleteRecord(id)
                    getTempFileFolder(id, configDir).onSuccess {
                        val (tempFolder, tempFile) = it
                        if (rec.downloadType == DownloadType.Http) {
                            val file = File(tempFolder, tempFile)
                            Logger.info("XDM", "Delete file $file")
                            val ret = file.delete()
                            Logger.info("XDM", "Delete file $ret")
                        } else {
                            FileUtils.deleteFolder(tempFolder)
                        }
                    }.onFailure { Logger.error("XDM", "Error deleting file", it) }
                    // Partial muxed output of a streaming download lives in the destination folder.
                    // Resolve it before the task info is removed.
                    listOf(".mp4", ".mkv").forEach { ext ->
                        streamingOutputPath(id, rec.downloadType, ext)?.let { File(it).delete() }
                    }
                } else if (rec.status == RecordStatus.DOWNLOADING) {
                    toDelete.add(id)
                    stopDownload(id)
                }
            }
        } catch (error: Exception) {
            Logger.error("XDM", "Error while delete", error)
        }
    }

    /**
     * A download id identifies its record, `task-<id>.info`, `<id>.state` and temp files, so it must be
     * unique. Returns [id] if no record uses it yet, otherwise a fresh id (logged).
     */
    private fun uniqueDownloadId(id: Long): Long {
        if (appDB.getById(id) == null) return id
        val fresh = CoreUtils.uniqueId()
        Logger.info("XDM", "Download id $id is already in use; using $fresh")
        return fresh
    }

    override fun startHttpDownload(task: HttpDownloadTaskInfo, runNow: Boolean) {
        val id = uniqueDownloadId(task.id)
        if (id != task.id) return startHttpDownload(task.copy(id = id), runNow)
        Logger.info("Adding new download: $task")
        taskInfoDB.saveHttpTask(task)
        appDB.addActive(
            DbRecord(
                id = task.id,
                size = 0,
                downloaded = 0,
                progress = 0,
                date = System.currentTimeMillis(),
                fileName = task.fileName,
                eta = 0,
                speed = 0.0f,
                status = if (runNow) RecordStatus.READY else RecordStatus.PAUSED,
                selected = false,
                downloadType = DownloadType.Http
            )
        )
        if (runNow) {
            appDB.saveActiveRecords()
        } else {
            appDB.savePausedRecords()
        }
        AppContext.app.addDownloadInView(task.id)
        if (runNow) {
            enqueue(task.id, resume = false)
        }
    }

    override fun addVideoDownload(videoId: Long, fileName: String, folder: String?, autoSelectFolder: Boolean) {
        // Each download of a detected video is a new download: copy the tracker's entry with a fresh
        // id instead of reusing (and mutating) it, so downloading the same video twice cannot share
        // records, task info, state or temp files.
        val tracker = AppContext.videoTracker
        tracker.getHttpVideo(videoId)?.let { source ->
            startHttpDownload(
                source.copy(
                    id = CoreUtils.uniqueId(), fileName = fileName, autoCategorize = autoSelectFolder,
                    defaultDownloadFolder = folder ?: source.defaultDownloadFolder,
                )
            )
            return
        }

        tracker.getHlsVideo(videoId)?.let { source ->
            startHlsDownload(
                source.copy(
                    id = CoreUtils.uniqueId(), fileName = fileName, autoCategorize = autoSelectFolder,
                    defaultDownloadFolder = folder ?: source.defaultDownloadFolder,
                )
            )
            return
        }

        tracker.getDashVideo(videoId)?.let { source ->
            startDashDownload(
                source.copy(
                    id = CoreUtils.uniqueId(), fileName = fileName, autoCategorize = autoSelectFolder,
                    defaultDownloadFolder = folder ?: source.defaultDownloadFolder,
                )
            )
        }
    }

    override fun startHlsDownload(task: HlsDownloadTaskInfo) {
        val id = uniqueDownloadId(task.id)
        // Copy rather than set tempDir on the caller's object.
        return startHls(task.copy(id = id, tempDir = AppContext.config.tempFolder + File.separator + id))
    }

    private fun startHls(task: HlsDownloadTaskInfo) {
        taskInfoDB.saveHlsTask(task)
        appDB.addActive(
            DbRecord(
                id = task.id,
                size = 0,
                downloaded = 0,
                progress = 0,
                date = System.currentTimeMillis(),
                fileName = task.fileName,
                eta = 0,
                speed = 0.0f,
                status = RecordStatus.READY,
                selected = false,
                downloadType = DownloadType.Hls
            )
        )
        appDB.saveActiveRecords()
        AppContext.app.addDownloadInView(task.id)
        enqueue(task.id, resume = false)
    }

    override fun startDashDownload(task: DashDownloadTaskInfo) {
        val id = uniqueDownloadId(task.id)
        // Copy rather than set tempDir on the caller's object.
        return startDash(task.copy(id = id, tempDir = AppContext.config.tempFolder + File.separator + id))
    }

    private fun startDash(task: DashDownloadTaskInfo) {
        taskInfoDB.saveDashTask(task)
        appDB.addActive(
            DbRecord(
                id = task.id,
                size = 0,
                downloaded = 0,
                progress = 0,
                date = System.currentTimeMillis(),
                fileName = task.fileName,
                eta = 0,
                speed = 0.0f,
                status = RecordStatus.READY,
                selected = false,
                downloadType = DownloadType.Dash
            )
        )
        appDB.saveActiveRecords()
        AppContext.app.addDownloadInView(task.id)
        enqueue(task.id, resume = false)
    }

    override fun getOriginPage(id: Long): String? {
        taskInfoDB.getHttpTask(id)?.let {
            if (it.origin != null) return it.origin!!
            val referer = getHeader("Referer", it.headers)
            if (referer != null) return referer
            return it.url
        }
        return null
    }

    override fun updateDownloadInfo(id: Long, task: HttpDownloadTaskInfo) {
        taskInfoDB.getHttpTask(id)?.let {
            it.url = task.url
            it.headers = task.headers
            it.cookie = task.cookie
            taskInfoDB.saveHttpTask(it)
            Logger.info("Task ${task.id} updated")
        }
        // Read first, then save: never rewrite the state file from inside its own read.
        AtomicIO.readTransacted("$id.state", configDir) { fs -> readContext(fs, downloadHost) }
            .onSuccess { context ->
                context.url = task.url
                context.headers = task.headers
                context.cookie = task.cookie
                saveState(context, configDir)
                Logger.info("Context ${context.id} updated")
            }
            .onFailure { Logger.error(it) }
    }

    private fun newHttpClient() =
        HttpClientImpl(
            100, AppContext.config.toProxy(), AppContext.config.ignoreCertErrors, AppContext.config.readTimeoutSeconds
        )

    private fun deleteAfterStopped(id: Long, task: DownloaderTask) {
        try {
            task.deleteTemp()
            deleteRecord(id)
        } catch (error: Exception) {
            Logger.error("XDM", "Error while resume", error)
        }
    }

    /**
     * Keeps the machine awake while any download is in flight. Driven off [activeSessions]; call
     * after every transition that adds or removes a session. Idempotent and gated by config.
     */
    private fun syncKeepAwake() {
        if (AppContext.config.keepAwake && activeSessions.isNotEmpty()) {
            KeepAwake.acquire()
        } else {
            KeepAwake.release()
        }
    }

    /**
     * The only place downloads are started: starts queued downloads in order while fewer than
     * `maxParallelDownloads` are active. Called after enqueueing and whenever a session actually
     * ends (inside the `activeSessions.remove(id)?.let {}` guards), so duplicate engine callbacks
     * cannot start extra downloads. A download that cannot start is marked paused and skipped.
     * Lock order is queue -> appDB; callers must not hold the appDB lock.
     */
    private fun pumpQueue() {
        synchronized(queue) {
            while (activeSessions.size < AppContext.config.maxParallelDownloads && queue.isNotEmpty()) {
                val item = queue.removeFirst()
                if (!launch(item)) markNotStarted(item.id)
            }
            syncKeepAwake()
            if (queue.isEmpty() && activeSessions.isEmpty()) {
                Logger.info("No pending download")
                if (AppContext.config.haltAfterDownload) {
                    Logger.info("Attempting shutdown after all downloads")
                    AppContext.platform.shutdownPC()
                }
            }
        }
    }

    /** Runs the configured antivirus scan / custom command against a freshly completed file. */
    private fun runPostDownloadActions(event: DownloadStatusInfo.FinalInfo) {
        val filePath = File(event.finalOutputFolder, event.finalFileName).absolutePath
        try {
            if (AppContext.config.runVirusScan) {
                AppContext.platform.runVirusScan(filePath)
            }
            if (AppContext.config.runCommand) {
                AppContext.platform.runCustomCommand(filePath)
            }
        } catch (error: Exception) {
            Logger.error("XDM", "Error running post-download actions", error)
        }
    }
}