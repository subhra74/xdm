package xdm.app

import xdm.app.utils.getFileFolder
import xdm.core.downloaders.*
import xdm.core.downloaders.web.getTempFileFolder
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.readContext
import xdm.core.downloaders.web.saveState
import xdm.core.downloaders.web.streaming.downloader.dash.DashDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.media.muxer.impl.FFmpegMuxer
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.AtomicIO
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.getFileName
import xdm.core.util.getHeader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface IDownloadManager {
    fun stopDownload(id: Long)
    fun resumeDownload(id: Long)
    fun deleteDownload(id: Long, fromDisk: Boolean)
    fun addHttpDownload(task: HttpDownloadTaskInfo)
    fun addVideoDownload(videoId: Long, fileName: String, folder: String?, autoSelectFolder: Boolean)
    fun addHlsDownload(task: HlsDownloadTaskInfo)
    fun addDashDownload(task: DashDownloadTaskInfo)
    fun updateDownloadInfo(id: Long, task: HttpDownloadTaskInfo)
    fun getOriginPage(id: Long): String?
}

class DownloadManager(val appDB: AppDB, val taskInfoDB: TaskInfoDB, private val configDir: String) :
    IDownloadManager {
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
                            DownloadType.Http ->
                                taskInfoDB.getHttpTask(data.id)?.let { t ->
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
                    event.id,
                    fileName,
                    event.downloaded,
                    size,
                    event.speed,
                    event.eta,
                    event.progress,
                    event.segments
                )
            }
        }

        override fun onAssembleStart(id: Long) {
            //TODO("Not yet implemented")
        }

        override fun onAssembleProgress(event: DownloadStatusInfo.AssembleInfo) {
            //TODO("Not yet implemented")
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
                AppContext.app.showDownloadCompleteWindow(
                    event.id,
                    event.finalOutputFolder,
                    event.finalFileName,
                    event.fileSize
                )
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
            }
        }

        override val appDir: String
            get() = AppContext.configDir

        override fun getTempDir(id: Long, url: String, contentType: String?, contentDisposition: String?): String {
            taskInfoDB.getHttpTask(id)?.let { t ->
                return t.defaultDownloadFolder
            }
            return AppContext.defaultDownloadFolder
        }

        private fun renameFile(
            fileName: String,
            folderPath: String,
            tmpFilePath: String,
            callback: (
                finalName: String,
                folder: String
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
        activeSessions[id]?.stop()
    }

    override fun resumeDownload(id: Long) {
        var fileName: String? = null
        try {
            appDB.getById(id)?.let {
                fileName = it.fileName
                val controller: DownloaderTask
                when (it.downloadType) {
                    DownloadType.Http -> controller = HttpDownloaderTask(
                        id, configDir, HttpClientImpl(100), downloadHost,
                    )

                    DownloadType.Hls -> controller = HlsDownloaderTask(
                        id = id,
                        configDir = AppContext.configDir,
                        http = HttpClientImpl(100),
                        host = downloadHost,
                        muxer = FFmpegMuxer(AppContext.configDir)
                    )

                    DownloadType.Dash -> controller = DashDownloaderTask(
                        id = id,
                        configDir = AppContext.configDir,
                        http = HttpClientImpl(100),
                        host = downloadHost,
                        muxer = FFmpegMuxer(AppContext.configDir)
                    )

                    DownloadType.Hds -> TODO()
                    DownloadType.Hss -> TODO()
                    DownloadType.Torrent -> TODO()
                }

                activeSessions[id] = controller
                it.status = RecordStatus.DOWNLOADING
                appDB.savePausedRecords()
                appDB.saveActiveRecords()
                controller.resume()
                AppContext.app.showProgressWindow(id, fileName)
            }
        } catch (error: Exception) {
            Logger.error("XDM", "Error while resume", error)
        }
    }

    private fun deleteItem(id: Long) {
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
                    deleteItem(id)
                    if (fromDisk) {
                        val (fileName: String?, folder: String?) = getFileFolder(rec) ?: return
                        if (fileName != null && folder != null) {
                            val fileToDelete = File(folder, fileName)
                            val deleted = fileToDelete.delete()
                            Logger.info("XDM", "Delete file $fileToDelete $deleted")
                        }
                    }
                } else if (rec.status == RecordStatus.PAUSED) {
                    deleteItem(id)
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
                } else if (rec.status == RecordStatus.DOWNLOADING || rec.status == RecordStatus.READY) {
                    toDelete.add(id)
                    stopDownload(id)
                }
            }
        } catch (error: Exception) {
            Logger.error("XDM", "Error while delete", error)
        }
    }

    override fun addHttpDownload(task: HttpDownloadTaskInfo) {
        taskInfoDB.saveHttpTask(task)
        val controller = HttpDownloaderTask(
            task, downloadHost,
            configDir
        )
        activeSessions[task.id] = controller
        appDB.addActive(
            DbRecord(
                id = task.id,
                size = 0,
                downloaded = 0,
                progress = 0,
                date = System.currentTimeMillis(),
                fileName = task.fileName,
                eta = 0, speed = 0.0f,
                status = RecordStatus.READY,
                selected = false,
                downloadType = DownloadType.Http
            )
        )
        appDB.saveActiveRecords()
        AppContext.app.addDownloadInView(task.id)
        AppContext.app.showProgressWindow(task.id, task.fileName)
        controller.start()
    }

    override fun addVideoDownload(videoId: Long, fileName: String, folder: String?, autoSelectFolder: Boolean) {
        AppContext.videoTracker.getHttpVideo(videoId)?.let { source ->
            source.fileName = fileName
            source.autoCategorize = (folder == null)
            source.userSelectedDownloadFolder = folder
            addHttpDownload(source);
        }

        AppContext.videoTracker.getHlsVideo(videoId)?.let { source ->
            Logger.info(source)
            source.fileName = fileName
            source.autoCategorize = (folder == null)
            source.userSelectedDownloadFolder = folder
            addHlsDownload(source)
        }

        AppContext.videoTracker.getDashVideo(videoId)?.let { source ->
            Logger.info(source)
            source.fileName = fileName
            source.autoCategorize = (folder == null)
            source.userSelectedDownloadFolder = folder
            addDashDownload(source)
        }
    }

    override fun addHlsDownload(task: HlsDownloadTaskInfo) {
        task.tempDir = AppContext.appConfig.tempDir + File.separator + task.id
        taskInfoDB.saveHlsTask(task)
        val controller = HlsDownloaderTask(
            taskInfo = task,
            http = HttpClientImpl(100),
            muxer = FFmpegMuxer(AppContext.configDir),
            host = downloadHost,
            configDir = AppContext.configDir,
        )
        activeSessions[task.id] = controller
        appDB.addActive(
            DbRecord(
                id = task.id,
                size = 0,
                downloaded = 0,
                progress = 0,
                date = System.currentTimeMillis(),
                fileName = task.fileName,
                eta = 0, speed = 0.0f,
                status = RecordStatus.READY,
                selected = false,
                downloadType = DownloadType.Hls
            )
        )
        appDB.saveActiveRecords()
        AppContext.app.addDownloadInView(task.id)
        AppContext.app.showProgressWindow(task.id, task.fileName)
        controller.start()
    }

    override fun addDashDownload(task: DashDownloadTaskInfo) {
        println("Not implemented yet")
        task.tempDir = AppContext.appConfig.tempDir + File.separator + task.id
        taskInfoDB.saveDashTask(task)
        val controller = DashDownloaderTask(
            taskInfo = task,
            http = HttpClientImpl(100),
            muxer = FFmpegMuxer(AppContext.configDir),
            host = downloadHost,
            configDir = AppContext.configDir,
        )
        activeSessions[task.id] = controller
        appDB.addActive(
            DbRecord(
                id = task.id,
                size = 0,
                downloaded = 0,
                progress = 0,
                date = System.currentTimeMillis(),
                fileName = task.fileName,
                eta = 0, speed = 0.0f,
                status = RecordStatus.READY,
                selected = false,
                downloadType = DownloadType.Dash
            )
        )
        appDB.saveActiveRecords()
        AppContext.app.addDownloadInView(task.id)
        AppContext.app.showProgressWindow(task.id, task.fileName)
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
            deleteItem(id)
        } catch (error: Exception) {
            Logger.error("XDM", "Error while resume", error)
        }
    }
}