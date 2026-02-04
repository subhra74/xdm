package xdm.app.controllers

import xdm.app.AppContext
import xdm.app.data.AppDB
import xdm.app.data.DbRecord
import xdm.app.data.RecordStatus
import xdm.core.downloaders.*
import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.HttpChunkController
import xdm.core.downloaders.web.http.HttpTaskContext
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloader
import xdm.core.media.muxer.impl.FFmpegMuxer
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.CoreUtils
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.getFileName
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DownloadHostController(val appDB: AppDB, val taskInfoDB: TaskInfoDB, private val configDir: String) {
    private val activeSessions = ConcurrentHashMap<Long, DownloaderTask>()
    private val downloadHost = object : DownloadHost {
        override fun onDownloadStart(id: Long) {
            // "Not yet implemented"
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
                            DownloadType.Dash -> TODO()
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
            activeSessions[event.id]?.let {
                synchronized(appDB) {
                    appDB.getById(event.id)?.let {
                        it.downloaded = event.downloaded
                        it.progress = event.progress
                        it.speed = event.speed
                        it.eta = event.eta
                    }
                }
                AppContext.app.updateDownloadInView(event.id)
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
            }
        }

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

                DownloadType.Dash -> TODO()
                DownloadType.Hds -> TODO()
                DownloadType.Hss -> TODO()
                DownloadType.Torrent -> TODO()
            }

            Logger.error("Task not found!")
            return CommitResult.Failed
        }

    }

    fun stopDownload(id: Long) {
        activeSessions[id]?.stop()
    }

    fun resumeDownload(id: Long) {
        try {
            appDB.getById(id)?.let {
                val controller: DownloaderTask
                when (it.downloadType) {
                    DownloadType.Http -> controller = HttpChunkController(
                        id, configDir, HttpClientImpl(100), downloadHost,
                    )

                    DownloadType.Hls -> controller = HlsDownloader(
                        id = id,
                        configDir = AppContext.configDir,
                        http = HttpClientImpl(100),
                        host = downloadHost,
                        muxer = FFmpegMuxer()
                    )

                    DownloadType.Dash -> TODO()
                    DownloadType.Hds -> TODO()
                    DownloadType.Hss -> TODO()
                    DownloadType.Torrent -> TODO()
                }

                activeSessions[id] = controller
                it.status = RecordStatus.DOWNLOADING
                appDB.savePausedRecords()
                appDB.saveActiveRecords()
                controller.resume()
            }
        } catch (error: Exception) {
            Logger.error("XDM", "Error while resume", error)
        }
    }

    fun addHttpDownload(task: HttpDownloadTaskInfo) {
        taskInfoDB.saveHttpTask(task)
        val controller = HttpChunkController(
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
        AppContext.app.addDownloadInView(task.id);
        controller.start()
    }

    fun addVideoDownload(videoId: Long, fileName: String, folder: String?, autoSelectFolder: Boolean) {
        AppContext.videoTracker.getHttpVideo(videoId)?.let { source ->
            source.fileName = fileName
            source.isAutoSelectFolder = (folder == null)
            source.folder = folder
            //downloader.startDownload(source, true, -1)
        }

        AppContext.videoTracker.getHlsVideo(videoId)?.let { source ->
            Logger.info(source)
            source.fileName = fileName
            source.autoCategorize = (folder == null)
            source.userSelectedDownloadFolder = folder
            addHlsDownload(source)
        }
    }

    private fun addHlsDownload(task: HlsDownloadTaskInfo) {
        task.tempDir = AppContext.appConfig.tempDir + File.separator + task.id
        taskInfoDB.saveHlsTask(task)
        val controller = HlsDownloader(
            taskInfo = task,
            http = HttpClientImpl(100),
            muxer = FFmpegMuxer(),
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
        AppContext.app.addDownloadInView(task.id);
        controller.start()
    }
}