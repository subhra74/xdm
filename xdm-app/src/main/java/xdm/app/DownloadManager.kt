package xdm.app

import xdm.app.utils.KeepAwake
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

class DownloadManager(val appDB: AppDB, val taskInfoDB: TaskInfoDB, private val configDir: String) : IDownloadManager {
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
                            DownloadType.Hds -> TODO()
                            DownloadType.Hss -> TODO()
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
            }
            processNextQueue()
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
            }
            processNextQueue()
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
            }
            processNextQueue()
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

        private fun renameFile(
            fileName: String, folderPath: String, tmpFilePath: String, callback: (
                finalName: String, folder: String
            ) -> Unit
        ): CommitResult {
            val folder = File(folderPath)
            if (!folder.exists() && !folder.mkdirs()) {
                Logger.info("Unable to create output dir: ${folderPath}")
                return CommitResult.Failed
            }

            val finalName = FileUtils.getUniqueFileName(folder.absolutePath, fileName)
            val outFile = File(folder, finalName)
            val tmpFile = File(tmpFilePath)

            return if (tmpFile.renameTo(outFile)) {
                Logger.info("Success renaming file: $tmpFile -> ${outFile.absolutePath}")
                callback(finalName, folder.absolutePath)
                CommitResult.Success(fileName = finalName, outputDir = folder.absolutePath)
            } else {
                Logger.info("Failed renaming file")
                CommitResult.Failed
            }
        }

        override fun commitOutputFile(id: Long, tmpFilePath: String, downloadType: DownloadType): CommitResult {
            when (downloadType) {
                DownloadType.Http -> taskInfoDB.getHttpTask(id)?.let { t ->
                    return renameFile(t.fileName, t.defaultDownloadFolder, tmpFilePath) { finalName, finalFolder ->
                        Logger.info("Success renaming file")
                        t.fileName = finalName
                        t.defaultDownloadFolder = finalFolder
                        taskInfoDB.saveHttpTask(t)
                    }
                }

                DownloadType.Hls -> taskInfoDB.getHlsTask(id)?.let { t ->
                    return renameFile(t.fileName, t.defaultDownloadFolder, tmpFilePath) { finalName, finalFolder ->
                        Logger.info("Success renaming file")
                        t.fileName = finalName
                        t.defaultDownloadFolder = finalFolder
                        taskInfoDB.saveHlsTask(t)
                    }
                }

                DownloadType.Dash -> taskInfoDB.getDashTask(id)?.let { t ->
                    return renameFile(t.fileName, t.defaultDownloadFolder, tmpFilePath) { finalName, finalFolder ->
                        Logger.info("Success renaming file")
                        t.fileName = finalName
                        t.defaultDownloadFolder = finalFolder
                        taskInfoDB.saveDashTask(t)
                    }
                }

                DownloadType.Hds -> TODO()
                DownloadType.Hss -> TODO()
                DownloadType.Torrent -> TODO()
            }

            Logger.error("Task not found!")
            return CommitResult.Failed
        }

    }

    override fun stopDownload(id: Long) {
        activeSessions[id]?.let {
            it.stop()
            return
        }
        val queued = queue.find { it.id == id }
        if (queued != null) {
            queue.remove(queued)
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
            it.status = RecordStatus.READY
            appDB.saveActiveRecords()
            appDB.savePausedRecords()
            AppContext.app.updateDownloadInView(id)

            if (activeSessions.size >= AppContext.config.maxParallelDownloads) {
                synchronized(queue) {
                    queue.add(QueueItem(id, true))
                }
            } else {
                resumeImmediately(id)
            }
        }


    }

    private fun resumeImmediately(id: Long) {
        var fileName: String?
        try {
            appDB.getById(id)?.let {
                fileName = it.fileName
                val controller: DownloaderTask
                when (it.downloadType) {
                    DownloadType.Http -> controller = HttpDownloaderTask(
                        taskInfoDB.getHttpTask(id)!!,
                        downloadHost,
                        HttpClientImpl(100, AppContext.config.toProxy()),
                        configDir,
                        AppContext.config
                    )

                    DownloadType.Hls -> controller = HlsDownloaderTask(
                        taskInfo = taskInfoDB.getHlsTask(id)!!,
                        http = HttpClientImpl(100, AppContext.config.toProxy()),
                        muxer = TransmuxingMuxer(AppContext.configDir),
                        host = downloadHost,
                        configDir = AppContext.configDir,
                        config = AppContext.config
                    )

                    DownloadType.Dash -> controller = DashDownloaderTask(
                        taskInfo = taskInfoDB.getDashTask(id)!!,
                        http = HttpClientImpl(100, AppContext.config.toProxy()),
                        muxer = TransmuxingMuxer(AppContext.configDir),
                        host = downloadHost,
                        configDir = AppContext.configDir,
                        config = AppContext.config
                    )

                    DownloadType.Hds -> TODO()
                    DownloadType.Hss -> TODO()
                    DownloadType.Torrent -> TODO()
                }

                activeSessions[id] = controller
                syncKeepAwake()
                it.status = RecordStatus.DOWNLOADING
                appDB.savePausedRecords()
                appDB.saveActiveRecords()
                controller.resume()
                if (AppContext.config.showDownloadProgressWindow) {
                    AppContext.app.showProgressWindow(id, fileName)
                }
            }
        } catch (error: Exception) {
            Logger.error("XDM", "Error while resume", error)
        }
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
                } else if (rec.status == RecordStatus.DOWNLOADING) {
                    toDelete.add(id)
                    stopDownload(id)
                }
            }
        } catch (error: Exception) {
            Logger.error("XDM", "Error while delete", error)
        }
    }

    override fun startHttpDownload(task: HttpDownloadTaskInfo, runNow: Boolean) {
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
            if (activeSessions.size >= AppContext.config.maxParallelDownloads) {
                synchronized(queue) {
                    queue.add(QueueItem(task.id, false))
                }
            } else {
                startHttpTask(task)
            }
        }
    }

    private fun startHttpTask(id: Long) {
        taskInfoDB.getHttpTask(id)?.let {
            startHttpTask(it)
        }
    }

    private fun startHttpTask(task: HttpDownloadTaskInfo) {
        val controller = HttpDownloaderTask(
            task, downloadHost,
            HttpClientImpl(
                100, AppContext.config.toProxy()
            ),
            configDir,
            AppContext.config
        )
        activeSessions[task.id] = controller
        syncKeepAwake()
        if (AppContext.config.showDownloadProgressWindow) {
            AppContext.app.showProgressWindow(task.id, task.fileName)
        }

        appDB.getById(task.id)?.let {
            it.status = RecordStatus.DOWNLOADING
            AppContext.app.updateDownloadInView(task.id)
        }
        controller.start()
    }

    override fun addVideoDownload(videoId: Long, fileName: String, folder: String?, autoSelectFolder: Boolean) {
        AppContext.videoTracker.getHttpVideo(videoId)?.let { source ->
            source.fileName = fileName
            source.autoCategorize = (folder == null)
            source.userSelectedDownloadFolder = folder
            startHttpDownload(source);
        }

        AppContext.videoTracker.getHlsVideo(videoId)?.let { source ->
            Logger.info(source)
            source.fileName = fileName
            source.autoCategorize = (folder == null)
            source.userSelectedDownloadFolder = folder
            startHlsDownload(source)
        }

        AppContext.videoTracker.getDashVideo(videoId)?.let { source ->
            Logger.info(source)
            source.fileName = fileName
            source.autoCategorize = (folder == null)
            source.userSelectedDownloadFolder = folder
            startDashDownload(source)
        }
    }

    override fun startHlsDownload(task: HlsDownloadTaskInfo) {
        task.tempDir = AppContext.config.tempFolder + File.separator + task.id
        taskInfoDB.saveHlsTask(task)
        val controller = HlsDownloaderTask(
            taskInfo = task,
            http = HttpClientImpl(100, AppContext.config.toProxy()),
            muxer = TransmuxingMuxer(AppContext.configDir),
            host = downloadHost,
            configDir = AppContext.configDir, AppContext.config
        )
        activeSessions[task.id] = controller
        syncKeepAwake()
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
        if (AppContext.config.showDownloadProgressWindow) {
            AppContext.app.showProgressWindow(task.id, task.fileName)
        }
        controller.start()
    }

    override fun startDashDownload(task: DashDownloadTaskInfo) {
        println("Not implemented yet")
        task.tempDir = AppContext.config.tempFolder + File.separator + task.id
        taskInfoDB.saveDashTask(task)
        val controller = DashDownloaderTask(
            taskInfo = task,
            http = HttpClientImpl(100, AppContext.config.toProxy()),
            muxer = TransmuxingMuxer(AppContext.configDir),
            host = downloadHost,
            configDir = AppContext.configDir, AppContext.config
        )
        activeSessions[task.id] = controller
        syncKeepAwake()
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
        if (AppContext.config.showDownloadProgressWindow) {
            AppContext.app.showProgressWindow(task.id, task.fileName)
        }
        controller.start()
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
        AtomicIO.readTransacted("$id.state", configDir) { fs ->
            try {
                val context = readContext(fs, downloadHost)
                context.url = task.url
                context.headers = task.headers
                context.cookie = task.cookie
                saveState(context, configDir)
                Logger.info("Context ${context.id} updated")
            } catch (e: Exception) {
                Logger.error(e)
                throw e
            }
        }.onFailure { Logger.error(it) }
    }

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

    private fun processNextQueue() {
        syncKeepAwake()
        synchronized(queue) {
            if (queue.isNotEmpty()) {
                val (id, resume) = queue.removeFirst()
                if (resume) {
                    resumeImmediately(id)
                } else {
                    appDB.getById(id)?.let {
                        if (it.downloadType == DownloadType.Http) {
                            startHttpTask(id)
                        }
                    }
                }
            } else {
                Logger.info("No pending download")
                // Only shut down once every download has actually finished, not merely when
                // the queue drains while parallel downloads are still in flight.
                if (AppContext.config.haltAfterDownload && activeSessions.isEmpty()) {
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