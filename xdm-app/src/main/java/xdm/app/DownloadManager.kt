package xdm.app

import xdm.app.utils.DownloadSource
import xdm.app.utils.KeepAwake
import xdm.app.utils.SoundPlayer
import xdm.app.utils.WorkingSetTrim
import xdm.app.utils.getFileFolder
import xdm.app.utils.isUsableBatchFolder
import xdm.core.CoreConfig
import xdm.core.downloaders.*
import xdm.core.downloaders.web.batch.BatchDownloaderTask
import xdm.core.downloaders.web.batch.deleteBatchFiles
import xdm.core.downloaders.web.batch.deleteBatchPartFiles
import xdm.core.downloaders.web.batch.deleteBatchTracks
import xdm.core.downloaders.web.batch.loadBatchState
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.readContext
import xdm.core.downloaders.web.saveState
import xdm.core.downloaders.web.streaming.downloader.dash.DashDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsKeyStore
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.storage.StorageFile
import xdm.core.storage.StorageFolder
import xdm.core.storage.uniqueFileName
import xdm.core.util.AtomicIO
import xdm.core.util.CoreUtils
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.MovePhase
import xdm.core.util.getFileName
import xdm.core.util.getHeader
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** What Change folder would do for a download (docs/download-categories.md §6.1). */
enum class ChangeFolderPlan {
    /** The download is running, queued or moving: Change folder is not offered. */
    NOT_ALLOWED,
    /** The chosen folder is the current one: nothing to do. */
    SAME,
    /** Copy the part file over (or, with nothing on disk yet, just record the folder). */
    MOVE,
    /**
     * The part file is not there (e.g. its drive is disconnected): nothing to copy. The user reconnects
     * the drive and resumes, or restarts the download.
     */
    PART_UNAVAILABLE,
}

/** How a Change folder ended. */
enum class ChangeFolderResult { DONE, CANCELLED, NO_SPACE, FAILED }

interface IDownloadManager {
    fun stopDownload(id: Long)
    fun resumeDownload(id: Long)
    fun deleteDownload(id: Long, fromDisk: Boolean)
    fun deleteDownloads(ids: Collection<Long>, fromDisk: Boolean)
    fun startHttpDownload(task: HttpDownloadTaskInfo, runNow: Boolean = true)
    /** [maxPiece] is the segment count picked in the dialog; null keeps the captured video's own. */
    /** [subtitle] is one of the stream's tracks, saved beside the video; null saves none. */
    fun addVideoDownload(
        videoId: Long, fileName: String, categoryId: String?, targetFolder: String, maxPiece: Int? = null,
        subtitle: SubtitleTrack? = null,
    )
    fun startHlsDownload(task: HlsDownloadTaskInfo)
    fun startDashDownload(task: DashDownloadTaskInfo)

    /**
     * Adds a batch. Its folder (`<folder>/<name>`) is created here and must be missing or empty;
     * returns false, adding nothing, when it is not.
     */
    fun startBatchDownload(task: BatchDownloadTaskInfo): Boolean

    /** Downloads again the files of a finished batch that failed; the finished ones are kept. */
    fun retryFailedFiles(id: Long)
    fun updateDownloadInfo(id: Long, task: HttpDownloadTaskInfo)
    fun updateDownloadLink(id: Long, url: String, headers: HeaderMap?, cookie: String?)
    fun getOriginPage(id: Long): String?

    /**
     * The page the download was found on: its origin, or its `Referer`. Unlike [getOriginPage]
     * this does not fall back to the download URL — a null means there is no page to reopen, and
     * the link can only be refreshed by hand.
     */
    fun getRefererPage(id: Long): String?

    /** What [changeFolder] would do for [id] and [folder]. */
    fun changeFolderPlan(id: Long, folder: String): ChangeFolderPlan

    /** Moves a stopped download to [folder] in the background; [done] runs on the moving thread when it ends. */
    fun changeFolder(id: Long, folder: String, done: (ChangeFolderResult) -> Unit)

    /** True while Change folder is moving [id]; it cannot be deleted or resumed meanwhile. */
    fun isMoving(id: Long): Boolean

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

    /** URL and ETag hashes of the HTTP downloads, for the New Download dialog's duplicate check. */
    val duplicates = DuplicateIndex(configDir, appDB)

    /** Downloads waiting for a free slot. Guarded by its own monitor; only [pumpQueue] starts tasks. */
    private val queue = ArrayDeque<QueueItem>()
    /** Active downloads the user deleted: purged once their task reports it has stopped. */
    private val toDelete: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    /** Of [toDelete], the batches whose downloaded files go too. */
    private val deleteFilesOnStop: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * Downloads whose publish the user asked to stop. The copy runs on the downloader's thread and
     * cannot see the task's stop flag from here, so the cancel request is tracked alongside it.
     */
    private val moveCancelled: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Downloads Change folder is moving right now. */
    private val moving: MutableSet<Long> = ConcurrentHashMap.newKeySet()
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
                            DownloadType.Batch -> {}
                            DownloadType.Torrent -> TODO()
                        }
                        appDB.saveActiveRecords()
                    }
                }
                AppContext.app.updateDownloadInView(data.id)
                warnIfVolumeIsShort(data.id, data.fileSize, downloadType)
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
                    event.id, fileName, event.downloaded, size, event.speed, event.eta, event.progress, event.segments,
                    if (event.filesTotal > 0) BatchFileCounts(event.filesDone, event.failedFiles, event.filesTotal) else null
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
                // The working folder has served its purpose; leaving it behind would litter the
                // temp folder with one empty directory per completed download.
                deleteTempFolders(event.id)
                // Before the record turns FINISHED, so nothing (the dialog, "Open", the virus scan,
                // the custom command) can reach the file unmarked.
                if (AppContext.config.markDownloadedFiles) markDownloaded(event)
                synchronized(appDB) {
                    appDB.getById(event.id)?.let { e ->
                        e.status = RecordStatus.FINISHED
                        e.fileName = event.finalFileName
                        e.size = event.fileSize
                        e.failedFiles = event.failedFiles
                        appDB.saveActiveRecords()
                        appDB.saveFinishedRecords()
                    }
                }
                AppContext.app.updateDownloadInView(event.id)
                AppContext.app.hideProgressWindow(event.id)
                when (AppContext.config.downloadCompleteNotification) {
                    DownloadCompleteNotification.DIALOG -> AppContext.app.showDownloadCompleteWindow(
                        event.id, event.finalOutputFolder, event.finalFileName, event.fileSize
                    )

                    DownloadCompleteNotification.NOTIFICATION ->
                        AppContext.app.showDownloadCompleteNotification(event.finalOutputFolder, event.finalFileName)

                    DownloadCompleteNotification.NONE -> {}
                }
                SoundPlayer.playIfEnabled(SoundPlayer.Sound.COMPLETE)
                runPostDownloadActions(event)
                if (toDelete.remove(event.id)) {
                    // Finished before the stop took effect: keep the file, drop the record as asked.
                    deleteRecord(event.id)
                    deleteMetadata(event.id)
                }
                pumpQueue()
            }
        }

        override fun onDownloadFailed(id: Long, error: DownloadError) {
            activeSessions.remove(id)?.let {
                synchronized(appDB) {
                    appDB.getById(id)?.let { e ->
                        e.status = RecordStatus.ERROR
                        e.speed = 0.0f
                        e.eta = 0
                        appDB.saveActiveRecords()
                        appDB.savePausedRecords()
                    }
                }
                AppContext.app.updateDownloadInView(id)
                if (toDelete.remove(id)) {
                    deleteAfterStopped(id, it)
                } else {
                    AppContext.app.showProgressError(id, error)
                    SoundPlayer.playIfEnabled(SoundPlayer.Sound.FAILED)
                }
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
                if (toDelete.remove(id)) {
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

        /**
         * Kept in the interface (unused by HTTP, which derives its part file from the task's target folder):
         * the per-download working folder under the temp folder.
         */
        override fun getTempDir(id: Long, url: String, contentType: String?, contentDisposition: String?): String =
            tempDirFor(id).absolutePath

        /**
         * Publishes [part] under its real name in the folder it is already in (the task's target folder).
         * The final name is resolved now ([resolveConflict]), so the clash window is milliseconds. A
         * same-folder rename never copies; one that fails (a Windows sharing violation from antivirus or
         * sync, a name taken in the meantime) is retried, and on failure the part file is left as it is.
         */
        private fun publishInFolder(
            fileName: String, part: StorageFile, callback: (finalName: String, folder: String) -> Unit
        ): CommitResult {
            val folder = part.folder
            repeat(PUBLISH_ATTEMPTS) { attempt ->
                if (attempt > 0) Thread.sleep(PUBLISH_RETRY_MS * attempt)
                val (finalName, replace) = resolveConflict(folder, fileName)
                    ?: return CommitResult.Failed(DownloadError.OutputWriteError)
                try {
                    folder.rename(part.name, finalName, replace)
                    folder.sync()
                    Logger.info("Published $part -> $finalName")
                    callback(finalName, folder.location)
                    return CommitResult.Success(fileName = finalName, outputDir = folder.location)
                } catch (e: Exception) {
                    Logger.error("XDM", "Unable to publish $part as $finalName (attempt ${attempt + 1})", e)
                }
            }
            return CommitResult.Failed(DownloadError.OutputWriteError)
        }

        /**
         * Applies the conflict settings when the destination name is taken: overwrite wins over
         * auto-rename, and with neither enabled the publish fails rather than silently clobbering
         * or renaming. Returns the name to use and whether it replaces an existing file, or null
         * when the conflict cannot be resolved.
         */
        private fun resolveConflict(folder: StorageFolder, fileName: String): Pair<String, Boolean>? {
            if (!folder.exists(fileName)) return fileName to false
            val config = AppContext.config
            return when {
                config.overwriteExistingFiles -> fileName to true
                config.autoRenameOnConflict -> uniqueFileName(fileName) { folder.exists(it) } to false
                else -> {
                    Logger.error("XDM", "$fileName exists and both overwrite and auto-rename are off")
                    null
                }
            }
        }

        override fun commitOutputFile(id: Long, part: StorageFile, downloadType: DownloadType): CommitResult {
            when (downloadType) {
                DownloadType.Http -> taskInfoDB.getHttpTask(id)?.let { t ->
                    return publishInFolder(t.fileName, part) { finalName, _ ->
                        t.fileName = finalName
                        taskInfoDB.saveHttpTask(t)
                    }
                }

                DownloadType.Hls -> taskInfoDB.getHlsTask(id)?.let { t ->
                    return publishInFolder(t.fileName, part) { finalName, _ ->
                        t.fileName = finalName
                        taskInfoDB.saveHlsTask(t)
                    }
                }

                DownloadType.Dash -> taskInfoDB.getDashTask(id)?.let { t ->
                    return publishInFolder(t.fileName, part) { finalName, _ ->
                        t.fileName = finalName
                        taskInfoDB.saveDashTask(t)
                    }
                }

                // A batch renames each file into place itself; it never commits through here.
                DownloadType.Batch, DownloadType.Torrent -> {}
            }

            Logger.error("XDM", "Task not found for commit: $id")
            return CommitResult.Failed(DownloadError.InternalError)
        }

        override fun outputFile(id: Long, downloadType: DownloadType, ext: String): StorageFile? =
            streamingOutputFile(id, downloadType, ext)
    }

    /**
     * Streaming downloads mux straight into their target folder as `.<hexid>.xdm-part<ext>`, so the
     * commit is a same-folder rename. Derived from the task info every time; the target folder only
     * changes through Change folder. Null for HTTP, which writes its part file directly.
     */
    private fun streamingOutputFile(id: Long, downloadType: DownloadType, ext: String): StorageFile? {
        val task: StreamingDownloadTaskInfo = when (downloadType) {
            DownloadType.Hls -> taskInfoDB.getHlsTask(id)
            DownloadType.Dash -> taskInfoDB.getDashTask(id)
            else -> null
        } ?: return null
        return downloadHost.storage.folder(task.targetFolder).file(partFileName(id) + ext)
    }

    /**
     * Warns once, at the start, when the volume the bytes land on cannot hold the file: the target folder
     * for HTTP, which writes there directly, and the temp folder for HLS/DASH, whose segments go there
     * first.
     *
     * It warns rather than refusing: a server's Content-Length is sometimes wrong, and blocking a
     * download over a bad number is worse than letting it try and fail honestly.
     */
    private fun warnIfVolumeIsShort(id: Long, fileSize: Long?, type: DownloadType) {
        val size = fileSize ?: return
        if (size <= 0) return
        val (folder, isTemp) = when (type) {
            DownloadType.Http -> (taskInfoDB.getHttpTask(id)?.targetFolder ?: return) to false
            else -> tempDirFor(id).absolutePath to true
        }
        val free = runCatching { File(folder).usableSpace }.getOrNull()?.takeIf { it > 0 } ?: return
        if (free >= size) return
        Logger.error("XDM", "Volume of $folder has $free bytes free, download $id needs $size")
        AppContext.app.showSpaceWarning(folder, size, free, isTemp)
    }

    /**
     * Shows that the move of [id] entered [phase], and returns whether it may go on. The cancel check
     * and the switch to [MovePhase.FINALIZING] share the [appDB] lock with [stopDownload]'s check, so a
     * cancel either stops the move or is refused: it can never land after the point of no return.
     */
    private fun enterMovePhase(id: Long, phase: MovePhase): Boolean {
        val progress = if (phase == MovePhase.FINALIZING) 100 else 0
        val shown = synchronized(appDB) {
            if (moveCancelled.contains(id)) return false
            if (!moving.contains(id)) return@synchronized false
            appDB.getById(id)?.let {
                it.status = RecordStatus.MOVING
                it.movePhase = phase
                it.progress = progress
                it.speed = 0.0f
                it.eta = 0
                if (phase == MovePhase.PREPARING) appDB.saveActiveRecords()
                true
            } ?: false
        }
        if (shown) {
            AppContext.app.updateDownloadInView(id)
            AppContext.app.updateMoveProgress(id, phase, progress)
        }
        return true
    }

    private fun updateMoveProgress(id: Long, percent: Int) {
        val changed = synchronized(appDB) {
            appDB.getById(id)?.let {
                if (it.status != RecordStatus.MOVING || it.progress == percent) false else {
                    it.progress = percent
                    true
                }
            } ?: false
        }
        if (changed) {
            AppContext.app.updateDownloadInView(id)
            AppContext.app.updateMoveProgress(id, MovePhase.COPYING, percent)
        }
    }

    /** The per-download working folder, created on demand. */
    private fun tempDirFor(id: Long) = File(AppContext.config.tempFolder, id.toString()).apply { mkdirs() }

    /**
     * Deletes a download's HLS/DASH segment folder: the one its task info recorded at creation, and the
     * one under the current temp folder (they differ when the temp folder was changed meanwhile). HTTP has
     * no working folder; its part file is in the target folder.
     */
    private fun deleteTempFolders(id: Long) {
        val recorded = (taskInfoDB.getHlsTask(id)?.tempDir ?: taskInfoDB.getDashTask(id)?.tempDir)
        listOfNotNull(recorded, File(AppContext.config.tempFolder, id.toString()).absolutePath).distinct().forEach {
            val folder = File(it)
            if (folder.name == id.toString() && folder.exists()) FileUtils.deleteFolder(folder.absolutePath)
        }
    }

    override fun stopDownload(id: Long) {
        // Also cancels a Change folder move; the part file stays where it was. Once the move is
        // finalizing it can no longer be undone, so the stop is refused and it finishes.
        val finalizing = synchronized(appDB) {
            val rec = appDB.getById(id)
            (rec?.status == RecordStatus.MOVING && rec.movePhase == MovePhase.FINALIZING).also {
                if (!it) moveCancelled.add(id)
            }
        }
        if (finalizing) {
            Logger.info("XDM", "Download $id is finalizing its move and cannot be paused")
            return
        }
        if (moving.contains(id)) return
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

        if (moving.contains(id)) {
            Logger.info("Download $id is being moved and cannot be resumed yet")
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

        DownloadType.Batch -> taskInfoDB.getBatchTask(id)?.let {
            BatchDownloaderTask(it, newHttpClient(), host, configDir, AppContext.config) {
                TransmuxingMuxer(AppContext.configDir)
            }
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
        // Starting again clears any earlier cancel, so a republish is not stopped before it begins.
        moveCancelled.remove(id)
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

    override fun deleteDownload(id: Long, fromDisk: Boolean) = deleteDownloads(listOf(id), fromDisk)

    /**
     * Deletes [ids] in one pass: the idle ones are removed from the list together (each list file
     * is saved once, not once per download) and purged, the running ones are stopped and purged
     * once they report it. With [fromDisk], completed files are deleted from the download folder too.
     */
    override fun deleteDownloads(ids: Collection<Long>, fromDisk: Boolean) {
        // A download being moved cannot be deleted: its file is half way between two folders.
        val wanted = ids.filterTo(HashSet()) { it !in moving }
        // Under the queue lock so a queued download cannot be launched while it is deleted.
        val (removed, running) = synchronized(queue) {
            queue.removeAll { it.id in wanted && !activeSessions.containsKey(it.id) }
            appDB.removeWhere { it.id in wanted && !activeSessions.containsKey(it.id) } to
                    wanted.filter { activeSessions.containsKey(it) }
        }
        // Downloading or assembling: stop first, purge in onDownloadPaused/Failed.
        running.forEach {
            if (fromDisk) deleteFilesOnStop.add(it)
            toDelete.add(it)
            stopDownload(it)
        }
        purgeRemoved(removed, fromDisk)
    }

    /**
     * Removes every download that is not in progress (finished, paused, failed) and matches
     * [filter], together with its task info, state and temp data. Downloads that are running,
     * assembling or queued are always kept. With [fromDisk], completed files are deleted from the
     * download folder too. Returns the number of removed records.
     */
    fun clearInactive(fromDisk: Boolean = false, filter: (DbRecord) -> Boolean = { true }): Int {
        val removed = synchronized(queue) {
            val queued = queue.map { it.id }.toSet()
            appDB.removeWhere { rec ->
                !activeSessions.containsKey(rec.id) && rec.id !in queued && rec.id !in toDelete
                        && rec.status != RecordStatus.DOWNLOADING
                        && rec.status != RecordStatus.ASSEMBLING
                        && rec.status != RecordStatus.READY
                        && rec.id !in moving
                        && filter(rec)
            }
        }
        purgeRemoved(removed, fromDisk)
        return removed.size
    }

    /** Purges records already taken out of [appDB], then refreshes the list view once. */
    private fun purgeRemoved(removed: List<DbRecord>, fromDisk: Boolean) {
        if (removed.isEmpty()) return
        removed.forEach { rec ->
            try {
                // Resolved first: the path is read from the task info that the purge deletes.
                val files = if (fromDisk && rec.status == RecordStatus.FINISHED && rec.downloadType != DownloadType.Batch)
                    listOfNotNull(finishedFile(rec), subtitleSidecar(rec)) else emptyList()
                purgeFiles(rec, fromDisk)
                files.forEach { Logger.info("XDM", "Delete file $it ${it.delete()}") }
            } catch (error: Exception) {
                Logger.error("XDM", "Error while deleting ${rec.id}", error)
            }
        }
        AppContext.app.downloadsRemovedInView()
    }

    /** Where a completed download's file is: its task info holds the final name and folder. */
    private fun finishedFile(rec: DbRecord): File? {
        val (fileName, folder) = getFileFolder(rec) ?: return null
        return if (fileName != null && folder != null) File(folder, fileName) else null
    }

    /** The `.srt` a finished HLS/DASH download saved beside its video, if it picked a subtitle. */
    private fun subtitleSidecar(rec: DbRecord): File? {
        val track = when (rec.downloadType) {
            DownloadType.Hls -> taskInfoDB.getHlsTask(rec.id)?.subtitles
            DownloadType.Dash -> taskInfoDB.getDashTask(rec.id)?.subtitles
            else -> null
        }?.firstOrNull() ?: return null
        val video = finishedFile(rec) ?: return null
        return File(video.parentFile, subtitleFileName(video.name, track.language)).takeIf { it.isFile }
    }

    /**
     * Deletes what a download that is not running left behind: its temp data (and a streaming
     * download's partial output), then `task-<id>.info`, `<id>.state` and its schedule. Finished
     * downloads have no temp data left, so only their metadata goes.
     */
    private fun purgeFiles(rec: DbRecord, fromDisk: Boolean = false) {
        val id = rec.id
        if (rec.downloadType == DownloadType.Batch) {
            // Its state holds the batch folder, not a temp folder: the generic cleanup below must not run.
            purgeBatchFiles(id, fromDisk)
        } else if (rec.status != RecordStatus.FINISHED) {
            // HLS/DASH segments live in the temp folder; every partial file sits in the target folder
            // under a name derived from the id, so the paths come from the task info (read before the
            // metadata goes).
            deleteTempFolders(id)
            partFiles(id, rec.downloadType).forEach { if (it.isFile) Logger.info("XDM", "Delete file $it ${it.delete()}") }
        }
        deleteMetadata(id)
    }

    /**
     * Deletes a batch's files: with [fromDisk] every file it downloaded, otherwise only its unfinished
     * part files. The folder goes only if that leaves it empty. Must run before the metadata is deleted.
     */
    private fun purgeBatchFiles(id: Long, fromDisk: Boolean) {
        // Merged items' tracks, in the temp folder: never wanted once the batch is gone.
        taskInfoDB.getBatchTask(id)?.let { deleteBatchTracks(it) }
        val state = loadBatchState(id, configDir).getOrNull()
        if (fromDisk && state != null) {
            deleteBatchFiles(state)
        } else {
            (state?.batchFolder ?: taskInfoDB.getBatchTask(id)?.batchFolder)?.let { deleteBatchPartFiles(it) }
        }
    }

    /**
     * Whether [a] and [b] are the same folder, also when spelled differently (letter case on macOS and
     * Windows, a symlink). Falls back to comparing absolute paths while either does not exist yet.
     */
    private fun sameFolder(a: String, b: String): Boolean {
        val fa = File(a)
        val fb = File(b)
        if (fa.absolutePath == fb.absolutePath) return true
        return runCatching { Files.isSameFile(fa.toPath(), fb.toPath()) }.getOrDefault(false)
    }

    /** The partial files a download may have in its target folder (and a Change folder scratch file). */
    private fun partFiles(id: Long, type: DownloadType): List<File> = when (type) {
        DownloadType.Http -> taskInfoDB.getHttpTask(id)?.let { t ->
            listOf(File(t.targetFolder, partFileName(id)), File(t.targetFolder, "${partFileName(id)}.$id.part"))
        }.orEmpty()

        DownloadType.Hls, DownloadType.Dash -> listOf(".mp4", ".mkv").mapNotNull { ext ->
            streamingOutputFile(id, type, ext)?.localPath?.let { File(it) }
        }

        else -> emptyList()
    }

    /** Removes the per-download files in the config dir and any schedule entry for [id]. */
    private fun deleteMetadata(id: Long) {
        taskInfoDB.deleteRecord(id)
        listOf("$id.state", "$id.state.bak1", "$id.state.bak2").forEach { File(configDir, it).delete() }
        HlsKeyStore.delete(id, configDir)
        if (AppContext.hasScheduler && AppContext.scheduler.contains(id)) {
            AppContext.scheduler.removeEntry(id)
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
        Logger.info("Adding new download: ${task.id} ${task.url}")
        taskInfoDB.saveHttpTask(task)
        duplicates.add(task.id, task.url, task.etag)
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
                downloadType = DownloadType.Http,
                categoryId = task.categoryId,
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

    override fun addVideoDownload(
        videoId: Long, fileName: String, categoryId: String?, targetFolder: String, maxPiece: Int?,
        subtitle: SubtitleTrack?,
    ) {
        // Each download of a detected video is a new download: copy the tracker's entry with a fresh
        // id instead of reusing (and mutating) it, so downloading the same video twice cannot share
        // records, task info, state or temp files.
        val tracker = AppContext.videoTracker
        tracker.getHttpVideo(videoId)?.let { source ->
            startHttpDownload(
                source.copy(
                    id = CoreUtils.uniqueId(), fileName = fileName, categoryId = categoryId,
                    targetFolder = targetFolder,
                    maxPiece = maxPiece ?: source.maxPiece,
                )
            )
            return
        }

        // The tracker's entry lists every subtitle the stream has; the download keeps the picked one.
        tracker.getHlsVideo(videoId)?.let { source ->
            startHlsDownload(
                source.copy(
                    id = CoreUtils.uniqueId(), fileName = fileName, categoryId = categoryId,
                    targetFolder = targetFolder,
                    maxPiece = maxPiece ?: source.maxPiece,
                    subtitles = listOfNotNull(subtitle),
                )
            )
            return
        }

        tracker.getDashVideo(videoId)?.let { source ->
            startDashDownload(
                source.copy(
                    id = CoreUtils.uniqueId(), fileName = fileName, categoryId = categoryId,
                    targetFolder = targetFolder,
                    maxPiece = maxPiece ?: source.maxPiece,
                    subtitles = listOfNotNull(subtitle),
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
                downloadType = DownloadType.Hls,
                categoryId = task.categoryId,
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
                downloadType = DownloadType.Dash,
                categoryId = task.categoryId,
            )
        )
        appDB.saveActiveRecords()
        AppContext.app.addDownloadInView(task.id)
        enqueue(task.id, resume = false)
    }

    override fun startBatchDownload(task: BatchDownloadTaskInfo): Boolean {
        val id = uniqueDownloadId(task.id)
        if (id != task.id) return startBatchDownload(task.copy(id = id))
        // Checked again here: another batch may have taken the name since the dialog checked it.
        val folder = File(task.batchFolder)
        if (!isUsableBatchFolder(folder) || !(folder.isDirectory || folder.mkdirs())) {
            Logger.error("XDM", "Batch folder is not usable: $folder")
            return false
        }
        Logger.info("Adding batch download: ${task.id} ${task.items.size} files -> $folder")
        taskInfoDB.saveBatchTask(task)
        appDB.addActive(
            DbRecord(
                id = task.id,
                size = task.items.sumOf { it.knownSize ?: 0 },
                downloaded = 0,
                progress = 0,
                date = System.currentTimeMillis(),
                fileName = task.name,
                eta = 0,
                speed = 0.0f,
                status = RecordStatus.READY,
                selected = false,
                downloadType = DownloadType.Batch
            )
        )
        appDB.saveActiveRecords()
        AppContext.app.addDownloadInView(task.id)
        enqueue(task.id, resume = false)
        return true
    }

    override fun retryFailedFiles(id: Long) {
        synchronized(queue) {
            if (activeSessions.containsKey(id) || queue.any { it.id == id }) return
            val rec = appDB.getById(id) ?: return
            if (rec.downloadType != DownloadType.Batch || rec.status != RecordStatus.FINISHED || rec.failedFiles == 0) return
            // The task retries every file that is not finished, which here is exactly the failed ones.
            rec.status = RecordStatus.READY
            rec.failedFiles = 0
            appDB.saveActiveRecords()
            appDB.saveFinishedRecords()
            AppContext.app.updateDownloadInView(id)
            queue.add(QueueItem(id, true))
        }
        pumpQueue()
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

    override fun getRefererPage(id: Long): String? {
        taskInfoDB.getHttpTask(id)?.let {
            if (it.origin != null) return it.origin!!
            return getHeader("Referer", it.headers)
        }
        return null
    }

    override fun updateDownloadInfo(id: Long, task: HttpDownloadTaskInfo) =
        applyLinkUpdate(id, task.url, task.headers, task.cookie)

    override fun updateDownloadLink(id: Long, url: String, headers: HeaderMap?, cookie: String?) =
        applyLinkUpdate(id, url, headers, cookie)

    /** Writes a refreshed link to both the task info and the resume state of a paused download. */
    private fun applyLinkUpdate(id: Long, url: String, headers: HeaderMap?, cookie: String?) {
        taskInfoDB.getHttpTask(id)?.let {
            it.url = url
            it.headers = headers
            it.cookie = cookie
            taskInfoDB.saveHttpTask(it)
            duplicates.updateUrl(id, url)
            Logger.info("Task $id updated")
        }
        // Read first, then save: never rewrite the state file from inside its own read.
        AtomicIO.readTransacted("$id.state", configDir) { fs -> readContext(fs, downloadHost, "", "") }
            .onSuccess { context ->
                context.url = url
                context.headers = headers
                context.cookie = cookie
                saveState(context, configDir)
                Logger.info("Context ${context.id} updated")
            }
            .onFailure { Logger.error(it) }
    }

    override fun isMoving(id: Long): Boolean = id in moving

    override fun changeFolderPlan(id: Long, folder: String): ChangeFolderPlan {
        val rec = appDB.getById(id) ?: return ChangeFolderPlan.NOT_ALLOWED
        if (!rec.status.isStopped || id in moving || activeSessions.containsKey(id)) return ChangeFolderPlan.NOT_ALLOWED
        if (synchronized(queue) { queue.any { it.id == id } }) return ChangeFolderPlan.NOT_ALLOWED
        val newFolder = File(folder).absolutePath
        return when (rec.downloadType) {
            DownloadType.Http -> {
                val task = taskInfoDB.getHttpTask(id) ?: return ChangeFolderPlan.NOT_ALLOWED
                if (sameFolder(task.targetFolder, newFolder)) return ChangeFolderPlan.SAME
                val hasProgress = File(configDir, "$id.state").isFile
                if (!hasProgress || File(task.targetFolder, partFileName(id)).isFile) ChangeFolderPlan.MOVE
                else ChangeFolderPlan.PART_UNAVAILABLE
            }

            DownloadType.Hls, DownloadType.Dash -> {
                val task: StreamingDownloadTaskInfo = taskInfoDB.getHlsTask(id) ?: taskInfoDB.getDashTask(id)
                    ?: return ChangeFolderPlan.NOT_ALLOWED
                if (sameFolder(task.targetFolder, newFolder)) ChangeFolderPlan.SAME else ChangeFolderPlan.MOVE
            }

            else -> ChangeFolderPlan.NOT_ALLOWED
        }
    }

    override fun changeFolder(id: Long, folder: String, done: (ChangeFolderResult) -> Unit) {
        val newFolder = File(folder).absolutePath
        // Claimed under both locks, so a resume, delete or queue start cannot slip in meanwhile.
        val previous = synchronized(queue) {
            if (activeSessions.containsKey(id) || queue.any { it.id == id }) return done(ChangeFolderResult.FAILED)
            synchronized(appDB) {
                val rec = appDB.getById(id)
                if (rec == null || !rec.status.isStopped || !moving.add(id)) return done(ChangeFolderResult.FAILED)
                rec.status
            }
        }
        moveCancelled.remove(id)
        Thread {
            val result = runCatching { moveTo(id, newFolder) }
                .onFailure { Logger.error("XDM", "Change folder failed for $id", it) }
                .getOrDefault(ChangeFolderResult.FAILED)
            synchronized(appDB) {
                moving.remove(id)
                moveCancelled.remove(id)
                appDB.getById(id)?.let {
                    it.status = if (result == ChangeFolderResult.DONE) RecordStatus.PAUSED else previous
                    it.movePhase = null
                    it.progress = if (it.size > 0) (it.downloaded * 100 / it.size).toInt() else 0
                    appDB.saveActiveRecords()
                    appDB.savePausedRecords()
                }
            }
            AppContext.app.updateDownloadInView(id)
            done(result)
        }.apply { name = "change-folder-$id" }.start()
    }

    /**
     * The work of [changeFolder], on its own thread. HTTP always copies (the usual reason to move is a
     * full or failing drive, so the new folder is on another one): copy the part file into place, then
     * record the new folder, then delete the old file. A crash at any point leaves the record pointing
     * at a complete file, so nothing needs recovering; at worst a stray copy is left behind.
     */
    private fun moveTo(id: Long, newFolder: String): ChangeFolderResult {
        val folder = File(newFolder)
        if (!folder.isDirectory && !folder.mkdirs()) return ChangeFolderResult.FAILED
        taskInfoDB.getHlsTask(id)?.let { t ->
            // Segments stay in the temp folder; a partial mux in the old folder is simply muxed again.
            partFiles(id, DownloadType.Hls).forEach { it.delete() }
            t.targetFolder = newFolder
            taskInfoDB.saveHlsTask(t)
            return ChangeFolderResult.DONE
        }
        taskInfoDB.getDashTask(id)?.let { t ->
            partFiles(id, DownloadType.Dash).forEach { it.delete() }
            t.targetFolder = newFolder
            taskInfoDB.saveDashTask(t)
            return ChangeFolderResult.DONE
        }
        val task = taskInfoDB.getHttpTask(id) ?: return ChangeFolderResult.FAILED
        // The same folder spelled differently (case, a symlink): copying onto itself and then deleting
        // the "old" file would delete the only copy.
        if (sameFolder(task.targetFolder, newFolder)) return ChangeFolderResult.DONE
        val source = File(task.targetFolder, partFileName(id))
        if (!File(configDir, "$id.state").isFile || !source.isFile) {
            // Nothing written yet: only the folder changes.
            task.targetFolder = newFolder
            taskInfoDB.saveHttpTask(task)
            return ChangeFolderResult.DONE
        }
        val total = source.length().coerceAtLeast(1)
        val target = File(folder, partFileName(id))
        val error = FileUtils.copyFile(
            source, target, id = id, replaceExisting = true,
            phase = { enterMovePhase(id, it) },
            progress = { copied ->
                updateMoveProgress(id, (copied * 100 / total).toInt())
                !moveCancelled.contains(id)
            },
        )
        return when (error) {
            null -> {
                task.targetFolder = newFolder
                // The old file goes only once the record provably points at the copy; otherwise the copy
                // goes and the download stays where it was.
                val recorded = taskInfoDB.saveHttpTask(task) &&
                        taskInfoDB.getHttpTask(id)?.targetFolder == newFolder
                if (!recorded) {
                    Logger.error("XDM", "Could not record the new folder of $id; keeping it in ${source.parent}")
                    target.delete()
                    return ChangeFolderResult.FAILED
                }
                if (!source.delete()) Logger.error("XDM", "Moved, but could not delete the old part file $source")
                ChangeFolderResult.DONE
            }

            DownloadError.Cancelled -> ChangeFolderResult.CANCELLED
            DownloadError.DiskSpaceError -> ChangeFolderResult.NO_SPACE
            else -> ChangeFolderResult.FAILED
        }
    }

    private fun newHttpClient() =
        HttpClientImpl(
            CoreConfig.MAX_SEGMENTS, AppContext.config.toProxy(), AppContext.config.ignoreCertErrors, AppContext.config.readTimeoutSeconds,
            AppContext.config.proxyUser, AppContext.config.proxyPass,
            auth = AppContext.httpAuth, serverAuth = true,
        )

    private fun deleteAfterStopped(id: Long, task: DownloaderTask) {
        try {
            // deleteTemp resolves a streaming download's output path from its task info, so it
            // must run before the metadata is removed.
            task.deleteTemp()
            if (deleteFilesOnStop.remove(id) && task is BatchDownloaderTask) purgeBatchFiles(id, fromDisk = true)
            deleteRecord(id)
            deleteMetadata(id)
        } catch (error: Exception) {
            Logger.error("XDM", "Error while delete", error)
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
    /** True while a download is running or waiting in the queue. Takes the queue lock only. */
    fun hasPendingDownloads(): Boolean = synchronized(queue) { queue.isNotEmpty() || activeSessions.isNotEmpty() }

    private fun pumpQueue() {
        synchronized(queue) {
            while (activeSessions.size < AppContext.config.maxParallelDownloads && queue.isNotEmpty()) {
                val item = queue.removeFirst()
                if (!launch(item)) markNotStarted(item.id)
            }
            syncKeepAwake()
            if (queue.isEmpty() && activeSessions.isEmpty()) {
                Logger.info("No pending download")
                WorkingSetTrim.request("all downloads finished")
                if (AppContext.config.haltAfterDownload) {
                    Logger.info("Attempting shutdown after all downloads")
                    AppContext.platform.shutdownPC()
                }
            }
        }
    }

    /** Marks a freshly completed file as downloaded from the internet. A failure is only logged. */
    private fun markDownloaded(event: DownloadStatusInfo.FinalInfo) {
        val file = File(event.finalOutputFolder, event.finalFileName)
        val type = synchronized(appDB) { appDB.getById(event.id)?.downloadType }
        if (type == DownloadType.Batch) return markBatchDownloaded(event.id)
        val source = when (type) {
            DownloadType.Http -> taskInfoDB.getHttpTask(event.id)?.let { DownloadSource(it.url, it.origin) }
            DownloadType.Hls -> taskInfoDB.getHlsTask(event.id)?.let { DownloadSource(it.url, it.origin) }
            DownloadType.Dash -> taskInfoDB.getDashTask(event.id)?.let { DownloadSource(it.url, it.origin) }
            else -> null
        } ?: DownloadSource(null, null)
        try {
            if (!AppContext.platform.markDownloadedFile(file, source)) {
                Logger.info("Download ${event.id} finished but could not be marked as downloaded: $file")
            }
        } catch (e: Exception) {
            Logger.error("Error marking $file as downloaded", e)
        }
    }

    /** Marks each file a batch finished with its own link as the source. */
    private fun markBatchDownloaded(id: Long) {
        val task = taskInfoDB.getBatchTask(id) ?: return
        val state = loadBatchState(id, configDir).getOrNull() ?: return
        for (f in state.files) {
            val name = f.finalName ?: continue
            if (f.status.get() != ChunkStatus.Finished) continue
            val file = File(state.batchFolder, name)
            try {
                AppContext.platform.markDownloadedFile(file, DownloadSource(task.items[f.index].url, task.origin))
            } catch (e: Exception) {
                Logger.error("Error marking $file as downloaded", e)
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

    private companion object {
        /** How many times a publish rename is tried, waiting a little longer each time. */
        const val PUBLISH_ATTEMPTS = 5
        const val PUBLISH_RETRY_MS = 200L
    }
}
