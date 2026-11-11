// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xdm.capture.CaptureContext
import xdm.core.CoreConfig
import xdm.core.downloaders.*
import xdm.core.downloaders.web.batch.BatchDownloaderTask
import xdm.core.downloaders.web.batch.deleteBatchFiles
import xdm.core.downloaders.web.batch.deleteBatchPartFiles
import xdm.core.downloaders.web.batch.deleteBatchTracks
import xdm.core.downloaders.web.batch.loadBatchState
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.loadDashState
import xdm.core.downloaders.web.readContext
import xdm.core.downloaders.web.saveState
import xdm.core.downloaders.web.streaming.downloader.dash.DashDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsKeyStore
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.media.muxer.MuxContainer
import xdm.core.media.muxer.Muxer
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.media.subtitle.Subtitles
import xdm.core.network.http.CertExceptions
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.storage.PlatformFiles
import xdm.core.storage.Storage
import xdm.core.storage.StorageFile
import xdm.core.storage.StorageFolder
import xdm.core.storage.TargetExistsException
import xdm.core.storage.asOutputStream
import xdm.core.storage.uniqueFileName
import xdm.core.util.AtomicIO
import xdm.core.util.CoreUtils
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.MovePhase
import xdm.core.util.getFileName
import xdm.core.util.getHeader
import xdm.manager.torrent.FilePriority
import xdm.manager.torrent.JniTorrentEngine
import xdm.manager.torrent.SeedLimits
import xdm.manager.torrent.SeedingListener
import xdm.manager.torrent.TorrentDownloaderTask
import xdm.manager.torrent.TorrentDetails
import xdm.manager.torrent.TorrentEngine
import xdm.manager.torrent.TorrentFlags
import xdm.manager.torrent.TorrentInfo
import xdm.manager.torrent.TorrentProxy
import xdm.manager.torrent.TorrentSeeder
import xdm.manager.torrent.TorrentService
import xdm.manager.torrent.TorrentSettings
import xdm.manager.torrent.TorrentStatus
import xdm.manager.torrent.TorrentTrackers
import java.io.File
import java.io.FileNotFoundException
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
    /** Returns the download's id: [task]'s, or a fresh one when that is already taken. */
    fun startHttpDownload(task: HttpDownloadTaskInfo, runNow: Boolean = true): Long
    /** [maxPiece] is the segment count picked in the dialog; null keeps the captured video's own. */
    /** [subtitle] is one of the stream's tracks, saved beside the video; null saves none. */
    /** [convertToSrt]: a captured WebVTT/TTML subtitle is saved as SRT; ignored for videos. */
    /** Returns the new download's id, or null when the tracker no longer has [videoId]. */
    fun addVideoDownload(
        videoId: Long, fileName: String, categoryId: String?, targetFolder: String, maxPiece: Int? = null,
        subtitle: SubtitleTrack? = null, convertToSrt: Boolean = false,
    ): Long?
    fun startHlsDownload(task: HlsDownloadTaskInfo): Long
    fun startDashDownload(task: DashDownloadTaskInfo): Long

    /**
     * Adds a batch. Its folder (`<folder>/<name>`) is created here and must be missing or empty;
     * returns false, adding nothing, when it is not.
     */
    fun startBatchDownload(task: BatchDownloadTaskInfo): Boolean

    /** Downloads again the files of a finished batch that failed; the finished ones are kept. */
    fun retryFailedFiles(id: Long)
    fun updateDownloadInfo(id: Long, task: HttpDownloadTaskInfo)
    fun updateDownloadLink(id: Long, url: String, headers: HeaderMap?, cookie: String?)

    /**
     * Gives a stopped DASH download whose tracks are each one resource cut into byte ranges (an
     * extractor's download) fresh links: every video piece gets [videoUrl], every audio piece
     * [audioUrl]. Updates the task info and the resume state. False when it is not such a download.
     */
    fun updateTrackLinks(id: Long, videoUrl: String?, audioUrl: String?, headers: HeaderMap?, cookie: String?): Boolean
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

/**
 * The download lifecycle shared by the desktop and Android apps: queue, start/stop/resume, publish,
 * delete, Change folder. The list itself lives in [store]; the app is reached through [EngineContext].
 */
class DownloadManager(
    val store: DownloadStore,
    val taskInfoDB: TaskInfoDB,
    private val configDir: String,
    taskFactory: DownloaderTaskFactory? = null,
    /** The BitTorrent engine; by default the native one when [PlatformHooks.loadTorrentLibrary] succeeds. */
    torrentEngineFactory: (() -> TorrentEngine?)? = null,
) : IDownloadManager {
    private val taskFactory: DownloaderTaskFactory = taskFactory ?: ::createTask
    private val config: EngineConfig get() = EngineContext.config
    private val ui: UiSink get() = EngineContext.ui
    private val hooks: PlatformHooks get() = EngineContext.hooks
    private val storage: Storage get() = EngineContext.storage

    /** URL and ETag hashes of the HTTP downloads, for the New Download dialog's duplicate check. */
    val duplicates = DuplicateIndex(configDir, store)

    /** Downloads waiting for a free slot. Guarded by its own monitor; only [pumpQueue] starts tasks. */
    private val queue = ArrayDeque<QueueItem>()
    /** Active downloads the user deleted: purged once their task reports it has stopped. */
    private val toDelete: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    /** Of [toDelete], the batches whose downloaded files go too. */
    private val deleteFilesOnStop: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * Certificates refused in each download's latest run, as "host|fingerprint"; what
     * [resumeTrustingRefused] would trust. In memory only: after a restart the download fails again and
     * refills it.
     */
    private val refused = ConcurrentHashMap<Long, MutableSet<String>>()

    /**
     * Downloads whose publish the user asked to stop. The copy runs on the downloader's thread and
     * cannot see the task's stop flag from here, so the cancel request is tracked alongside it.
     */
    private val moveCancelled: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Downloads Change folder is moving right now. */
    private val moving: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val activeSessions = ConcurrentHashMap<Long, DownloaderTask>()

    /** The BitTorrent session, started when the first torrent runs (or [TorrentService.warmUp]). */
    val torrents = TorrentService(
        configDir,
        torrentEngineFactory ?: { if (hooks.loadTorrentLibrary()) JniTorrentEngine() else null },
        ::torrentSessionSettings,
        keepOpen = { config.torrentKeepSession },
        addFlags = { if (config.torrentPex) 0 else TorrentFlags.DISABLE_PEX },
    )

    private val seeder = TorrentSeeder(
        torrents,
        { SeedLimits(config.torrentSeedRatioLimit, config.torrentSeedTimeLimitMinutes) },
        object : SeedingListener {
            override fun seedingStarted(id: Long) = setSeeding(id, true, null)
            override fun seedingProgress(id: Long, status: TorrentStatus) =
                setSeeding(id, true, TorrentDownloaderTask.torrentProgress(status, seeding = true))
            override fun seedingStopped(id: Long) = setSeeding(id, false, null)
            override fun seedingFailed(id: Long, message: String) = ui.seedingFailed(id)
        },
    )

    /** Sizes of subtitles converted to SRT at publish, which differ from the bytes downloaded. */
    private val convertedSizes = ConcurrentHashMap<Long, Long>()
    private val downloadHost = object : DownloadHost {
        override fun onDownloadActivated(id: Long) {
            activeSessions[id]?.let {
                synchronized(store) {
                    store.getById(id)?.let { e ->
                        e.status = RecordStatus.DOWNLOADING
                        // A fill a pause cut short is reported again if this session goes on with it.
                        e.allocating = -1
                    }
                }
                ui.downloadUpdated(id)
            }
        }

        override fun onAllocateProgress(id: Long, percent: Int) {
            activeSessions[id]?.let {
                synchronized(store) {
                    store.getById(id)?.let { e -> e.allocating = percent }
                }
                ui.downloadUpdated(id)
            }
        }

        override fun onDownloadInit(data: DownloadStatusInfo.InitInfo, downloadType: DownloadType) {
            activeSessions[data.id]?.let {
                synchronized(store) {
                    store.getById(data.id)?.let { e ->
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
                            // The name arrives with the metadata; the size is that of the selected files.
                            DownloadType.Torrent -> taskInfoDB.getTorrentTask(data.id)?.let { t ->
                                e.fileName = t.name
                                data.fileSize?.let { e.size = it }
                            }
                        }
                        store.update(e)
                    }
                }
                ui.downloadUpdated(data.id)
                warnIfVolumeIsShort(data.id, data.fileSize, downloadType)
            }
        }

        override fun onDownloadProgress(event: DownloadStatusInfo.ProgressInfo) {
            var size: Long = -1
            var fileName: String? = null
            activeSessions[event.id]?.let {
                synchronized(store) {
                    store.getById(event.id)?.let {
                        it.downloaded = event.downloaded
                        it.progress = event.progress
                        it.speed = event.speed
                        it.eta = event.eta
                        it.torrent = event.torrent
                        size = it.size
                        fileName = it.fileName
                    }
                }
                ui.downloadUpdated(event.id)
                ui.progress(
                    event.id, fileName, event.downloaded, size, event.speed, event.eta, event.progress, event.segments,
                    if (event.filesTotal > 0) BatchFileCounts(event.filesDone, event.failedFiles, event.filesTotal) else null
                )
                event.torrent?.let { ui.torrentProgress(event.id, it) }
            }
        }

        override fun onAssembleStart(id: Long) {
            activeSessions[id]?.let {
                var fileName: String? = null
                synchronized(store) {
                    store.getById(id)?.let { e ->
                        e.status = RecordStatus.ASSEMBLING
                        e.progress = 0
                        e.speed = 0.0f
                        e.eta = 0
                        fileName = e.fileName
                    }
                }
                ui.downloadUpdated(id)
                ui.progress(id, fileName, 0, -1, 0.0f, 0, 0, listOf())
            }
        }

        override fun onAssembleProgress(event: DownloadStatusInfo.AssembleInfo) {
            var downloaded: Long = 0
            var size: Long = -1
            var fileName: String? = null
            activeSessions[event.id]?.let {
                synchronized(store) {
                    store.getById(event.id)?.let { e ->
                        e.status = RecordStatus.ASSEMBLING
                        e.progress = event.progress
                        e.speed = 0.0f
                        e.eta = 0
                        downloaded = e.downloaded
                        size = e.size
                        fileName = e.fileName
                    }
                }
                ui.downloadUpdated(event.id)
                ui.progress(
                    event.id, fileName, downloaded, size, 0.0f, 0, event.progress, listOf()
                )
            }
        }

        override fun onDownloadSuccess(downloaded: DownloadStatusInfo.FinalInfo) {
            val event = convertedSizes.remove(downloaded.id)?.let { downloaded.copy(fileSize = it) } ?: downloaded
            activeSessions.remove(event.id)?.let {
                // The working folder has served its purpose; leaving it behind would litter the
                // temp folder with one empty directory per completed download.
                deleteTempFolders(event.id)
                // Before the record turns FINISHED, so nothing (the dialog, "Open", the virus scan,
                // the custom command) can reach the file unmarked.
                if (config.markDownloadedFiles) markDownloaded(event)
                synchronized(store) {
                    store.getById(event.id)?.let { e ->
                        e.status = RecordStatus.FINISHED
                        e.fileName = event.finalFileName
                        e.size = event.fileSize
                        e.failedFiles = event.failedFiles
                        if (!e.seeding) e.torrent = null
                        store.update(e)
                    }
                }
                ui.downloadUpdated(event.id)
                ui.hideProgress(event.id)
                when (config.downloadCompleteNotification) {
                    DownloadCompleteNotification.DIALOG -> ui.downloadCompleteDialog(
                        event.id, event.finalOutputFolder, event.finalFileName, event.fileSize
                    )

                    DownloadCompleteNotification.NOTIFICATION ->
                        ui.downloadCompleteNotification(event.finalOutputFolder, event.finalFileName)

                    DownloadCompleteNotification.NONE -> {}
                }
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
                synchronized(store) {
                    store.getById(id)?.let { e ->
                        e.status = RecordStatus.ERROR
                        e.speed = 0.0f
                        e.eta = 0
                        store.update(e)
                    }
                }
                ui.downloadUpdated(id)
                if (toDelete.remove(id)) {
                    deleteAfterStopped(id, it)
                } else if (!tryRefreshLink(id, error)) {
                    ui.downloadFailed(id, error)
                    hooks.downloadFailed(id)
                }
                pumpQueue()
            }
        }

        override fun onDownloadPaused(id: Long, event: PauseEvent) {
            Logger.info("Download paused $id")
            activeSessions.remove(id)?.let {
                synchronized(store) {
                    store.getById(id)?.let { e ->
                        e.status = RecordStatus.PAUSED
                        store.update(e)
                    }
                }
                ui.downloadUpdated(id)
                ui.hideProgress(id)
                if (toDelete.remove(id)) {
                    deleteAfterStopped(id, it)
                }
                pumpQueue()
            }
        }

        override val storage: Storage
            get() = this@DownloadManager.storage

        override val appDir: String
            get() = configDir
        override val applySpeedLimit: Boolean
            get() = config.speedLimiterEnabled
        override val speedLimit: Int
            get() = if (config.speedLimit > 0) config.speedLimit else -1

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
         *
         * A rename refused with [TargetExistsException] means the name is taken even if the folder did
         * not list it: on Android 11+ other apps' files are invisible to path lookups. That name is then
         * treated as taken from the next attempt on.
         */
        private fun publishInFolder(
            fileName: String, part: StorageFile, callback: (finalName: String, folder: String) -> Unit
        ): CommitResult {
            val folder = part.folder
            val refused = HashSet<String>()
            repeat(PUBLISH_ATTEMPTS) { attempt ->
                if (attempt > 0) Thread.sleep(PUBLISH_RETRY_MS * attempt)
                val (finalName, replace) = resolveConflict(folder, fileName, refused)
                    ?: return CommitResult.Failed(DownloadError.OutputWriteError)
                try {
                    folder.rename(part.name, finalName, replace)
                    folder.sync()
                    Logger.info("Published $part -> $finalName")
                    callback(finalName, folder.location)
                    return CommitResult.Success(fileName = finalName, outputDir = folder.location)
                } catch (e: TargetExistsException) {
                    Logger.error("XDM", "Unable to publish $part as $finalName: the name is taken", e)
                    refused.add(finalName)
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
        private fun resolveConflict(
            folder: StorageFolder, fileName: String, refused: Set<String> = emptySet()
        ): Pair<String, Boolean>? {
            val taken = { name: String -> name in refused || folder.exists(name) }
            if (!taken(fileName)) return fileName to false
            return when {
                // A refused name could not be replaced either: it is not ours to overwrite.
                config.overwriteExistingFiles && fileName !in refused -> fileName to true
                config.autoRenameOnConflict -> uniqueFileName(fileName, taken) to false
                else -> {
                    Logger.error("XDM", "$fileName exists and both overwrite and auto-rename are off")
                    null
                }
            }
        }

        override fun commitOutputFile(id: Long, part: StorageFile, downloadType: DownloadType, container: String?): CommitResult {
            // A muxed download is named after the container written (MuxContainer), which may not be the one picked up front.
            fun named(fileName: String) = container?.let { MuxContainer.withExtension(fileName, it) } ?: fileName
            when (downloadType) {
                DownloadType.Http -> taskInfoDB.getHttpTask(id)?.let { t ->
                    val name = if (t.convertToSrt) convertToSrt(id, t.fileName, part) else t.fileName
                    return publishInFolder(name, part) { finalName, _ ->
                        t.fileName = finalName
                        taskInfoDB.saveHttpTask(t)
                    }
                }

                DownloadType.Hls -> taskInfoDB.getHlsTask(id)?.let { t ->
                    return publishInFolder(named(t.fileName), part) { finalName, _ ->
                        t.fileName = finalName
                        taskInfoDB.saveHlsTask(t)
                    }
                }

                DownloadType.Dash -> taskInfoDB.getDashTask(id)?.let { t ->
                    return publishInFolder(named(t.fileName), part) { finalName, _ ->
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
     * Rewrites the finished part file of a captured subtitle as SRT and returns the name to publish
     * it under. Already SRT (a publish that failed after converting, retried on resume): only the
     * name changes. Not a subtitle, no cues, or any error: the file is published as downloaded.
     */
    private fun convertToSrt(id: Long, fileName: String, part: StorageFile): String {
        try {
            val length = part.length()
            if (length !in 1..MAX_SUBTITLE_BYTES) {
                Logger.info("XDM", "Subtitle $id is $length bytes, not converting it")
                return fileName
            }
            val text = Subtitles.decode(part.folder.openRead(part.name).use { it.readBytes() })
            val format = Subtitles.detect(text)
            if (format == Subtitles.Format.SRT) return Subtitles.srtFileName(fileName)
            val srt = Subtitles.toSrt(text)
            if (srt.isNullOrEmpty()) {
                Logger.info("XDM", "Download $id is not a convertible subtitle ($format), keeping it as is")
                return fileName
            }
            val bytes = Subtitles.srtBytes(srt)
            part.open(create = false).use { out ->
                out.setLength(0)
                out.write(bytes, 0, bytes.size)
                out.force()
            }
            convertedSizes[id] = bytes.size.toLong()
            Logger.info("XDM", "Converted subtitle $id from $format to SRT")
            return Subtitles.srtFileName(fileName)
        } catch (e: Exception) {
            Logger.error("XDM", "Unable to convert subtitle $id to SRT, keeping it as is", e)
            return fileName
        }
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
        return storage.folder(task.targetFolder).file(partFileName(id) + ext)
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
            DownloadType.Torrent -> (taskInfoDB.getTorrentTask(id)?.folder ?: return) to false
            DownloadType.Hls -> (taskInfoDB.getHlsTask(id)?.tempDir ?: tempDirFor(id).absolutePath) to true
            DownloadType.Dash -> (taskInfoDB.getDashTask(id)?.tempDir ?: tempDirFor(id).absolutePath) to true
            else -> tempDirFor(id).absolutePath to true
        }
        val free = runCatching { hooks.usableSpace(folder) }.getOrNull()?.takeIf { it > 0 } ?: return
        if (free >= size) return
        Logger.error("XDM", "Volume of $folder has $free bytes free, download $id needs $size")
        ui.spaceWarning(folder, size, free, isTemp)
    }

    /**
     * Shows that the move of [id] entered [phase], and returns whether it may go on. The cancel check
     * and the switch to [MovePhase.FINALIZING] share the [store] lock with [stopDownload]'s check, so a
     * cancel either stops the move or is refused: it can never land after the point of no return.
     */
    private fun enterMovePhase(id: Long, phase: MovePhase): Boolean {
        val progress = if (phase == MovePhase.FINALIZING) 100 else 0
        val shown = synchronized(store) {
            if (moveCancelled.contains(id)) return false
            if (!moving.contains(id)) return@synchronized false
            store.getById(id)?.let {
                it.status = RecordStatus.MOVING
                it.movePhase = phase
                it.progress = progress
                it.speed = 0.0f
                it.eta = 0
                if (phase == MovePhase.PREPARING) store.update(it)
                true
            } ?: false
        }
        if (shown) {
            ui.downloadUpdated(id)
            ui.moveProgress(id, phase, progress)
        }
        return true
    }

    private fun updateMoveProgress(id: Long, percent: Int) {
        val changed = synchronized(store) {
            store.getById(id)?.let {
                if (it.status != RecordStatus.MOVING || it.progress == percent) false else {
                    it.progress = percent
                    true
                }
            } ?: false
        }
        if (changed) {
            ui.downloadUpdated(id)
            ui.moveProgress(id, MovePhase.COPYING, percent)
        }
    }

    /** Where a new streaming download into [targetFolder] keeps its segments. */
    private fun streamingTempDir(targetFolder: String, id: Long): String =
        EngineContext.host.streamingTempFolder(targetFolder) + File.separator + id

    /** The per-download working folder, created on demand. */
    private fun tempDirFor(id: Long) = File(config.tempFolder, id.toString()).apply { mkdirs() }

    /**
     * Deletes a download's HLS/DASH segment folder: the one its task info recorded at creation, and the
     * one under the current temp folder (they differ when the temp folder was changed meanwhile). HTTP has
     * no working folder; its part file is in the target folder.
     */
    private fun deleteTempFolders(id: Long) {
        val recorded = (taskInfoDB.getHlsTask(id)?.tempDir ?: taskInfoDB.getDashTask(id)?.tempDir)
        listOfNotNull(recorded, File(config.tempFolder, id.toString()).absolutePath).distinct().forEach {
            val folder = File(it)
            if (folder.name == id.toString() && folder.exists()) FileUtils.deleteFolder(folder.absolutePath)
        }
    }

    override fun stopDownload(id: Long) {
        // Also cancels a Change folder move; the part file stays where it was. Once the move is
        // finalizing it can no longer be undone, so the stop is refused and it finishes.
        val finalizing = synchronized(store) {
            val rec = store.getById(id)
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
            store.getById(id)?.let {
                it.status = RecordStatus.PAUSED
                ui.downloadUpdated(id)
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
        store.getById(id)?.let {
            synchronized(queue) {
                if (queue.any { q -> q.id == id }) return
                it.status = RecordStatus.READY
                store.update(it)
                ui.downloadUpdated(id)
                queue.add(QueueItem(id, true))
            }
            pumpQueue()
        }
    }

    /** Whether [id]'s latest run was refused a certificate the user could trust ([resumeTrustingRefused]). */
    fun hasRefusedCerts(id: Long): Boolean = refused[id]?.isNotEmpty() == true

    /**
     * Trusts the certificates [id]'s latest run was refused, each for its host ([CertExceptions], shared
     * with every download and the browser), then resumes it: the user chose to trust them after a
     * certificate error.
     */
    fun resumeTrustingRefused(id: Long) {
        refused.remove(id)?.forEach {
            val host = it.substringBefore('|')
            val fingerprint = it.substringAfter('|')
            Logger.info("XDM", "Trusting certificate $fingerprint for $host (download $id)")
            CertExceptions.add(host, fingerprint)
        }
        resumeDownload(id)
    }

    /** The app's merger ([PlatformHooks.muxerFor]), else the built-in one. */
    private fun muxerFor(extractor: ExtractorSource?): Muxer =
        hooks.muxerFor(extractor) ?: TransmuxingMuxer(configDir)

    /** The real engine task for [type], built from its persisted task info. */
    private fun createTask(type: DownloadType, id: Long, host: DownloadHost): DownloaderTask? = when (type) {
        DownloadType.Http -> taskInfoDB.getHttpTask(id)?.let {
            HttpDownloaderTask(it, host, newHttpClient(id), configDir, config)
        }

        DownloadType.Hls -> taskInfoDB.getHlsTask(id)?.let {
            HlsDownloaderTask(
                taskInfo = it, http = newHttpClient(id), muxer = muxerFor(it.extractor),
                host = host, configDir = configDir, config = config
            )
        }

        DownloadType.Dash -> taskInfoDB.getDashTask(id)?.let {
            DashDownloaderTask(
                taskInfo = it, http = newHttpClient(id), muxer = muxerFor(it.extractor),
                host = host, configDir = configDir, config = config
            )
        }

        DownloadType.Batch -> taskInfoDB.getBatchTask(id)?.let {
            BatchDownloaderTask(it, newHttpClient(id), host, configDir, config) { muxerFor(null) }
        }

        DownloadType.Torrent -> taskInfoDB.getTorrentTask(id)?.let {
            TorrentDownloaderTask(it, host, torrents, taskInfoDB) { taskId, key ->
                // A download deleted while it ran is not seeded.
                if (config.torrentSeedAfterFinish && taskId !in toDelete) {
                    seeder.adopt(taskId, key)
                    true
                } else false
            }
        }
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
            val rec = store.getById(id) ?: return false
            val controller = taskFactory(rec.downloadType, id, downloadHost) ?: run {
                Logger.error("XDM", "Task info missing for download $id")
                return false
            }
            activeSessions[id] = controller
            syncKeepAwake()
            rec.status = RecordStatus.DOWNLOADING
            store.update(rec)
            ui.downloadUpdated(id)
            if (config.showDownloadProgressWindow) {
                ui.showProgress(id, rec.fileName)
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
        store.getById(id)?.let {
            it.status = RecordStatus.PAUSED
            store.update(it)
            ui.downloadUpdated(id)
        }
    }

    /** Adds a new download to the end of the queue and starts as many queued downloads as allowed. */
    private fun enqueue(id: Long, resume: Boolean) {
        synchronized(queue) { queue.add(QueueItem(id, resume)) }
        pumpQueue()
    }

    private fun deleteRecord(id: Long) {
        store.remove(id)?.let { ui.downloadRemoved(id, it) }
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
            store.removeWhere { it.id in wanted && !activeSessions.containsKey(it.id) } to
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
            store.removeWhere { rec ->
                !activeSessions.containsKey(rec.id) && rec.id !in queued && rec.id !in toDelete
                        && rec.status != RecordStatus.DOWNLOADING
                        && rec.status != RecordStatus.ASSEMBLING
                        && rec.status != RecordStatus.READY
                        && rec.id !in moving
                        && !seeder.isSeeding(rec.id)
                        && filter(rec)
            }
        }
        purgeRemoved(removed, fromDisk)
        return removed.size
    }

    /** Purges records already taken out of [store], then refreshes the list view once. */
    private fun purgeRemoved(removed: List<DbRecord>, fromDisk: Boolean) {
        if (removed.isEmpty()) return
        removed.forEach { rec ->
            try {
                // Resolved first: the path is read from the task info that the purge deletes.
                // Batches and torrents delete their own files (purgeFiles).
                val files = if (fromDisk && rec.status == RecordStatus.FINISHED && rec.downloadType != DownloadType.Batch &&
                    rec.downloadType != DownloadType.Torrent)
                    listOfNotNull(finishedFile(rec), subtitleSidecar(rec)) else emptyList()
                purgeFiles(rec, fromDisk)
                files.forEach { Logger.info("XDM", "Delete file $it ${it.delete()}") }
            } catch (error: Exception) {
                Logger.error("XDM", "Error while deleting ${rec.id}", error)
            }
        }
        ui.downloadsRemoved()
    }

    /** The `.srt` a finished HLS/DASH download saved beside its video, if it picked a subtitle. */
    private fun subtitleSidecar(rec: DbRecord): StorageFile? {
        val track = when (rec.downloadType) {
            DownloadType.Hls -> taskInfoDB.getHlsTask(rec.id)?.subtitles
            DownloadType.Dash -> taskInfoDB.getDashTask(rec.id)?.subtitles
            else -> null
        }?.firstOrNull() ?: return null
        val video = finishedFile(rec) ?: return null
        return video.folder.file(subtitleFileName(video.name, track.language)).takeIf { it.exists }
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
        } else if (rec.downloadType == DownloadType.Torrent) {
            purgeTorrentFiles(rec, fromDisk)
        } else if (rec.status != RecordStatus.FINISHED) {
            // HLS/DASH segments live in the temp folder; every partial file sits in the target folder
            // under a name derived from the id, so the paths come from the task info (read before the
            // metadata goes).
            deleteTempFolders(id)
            partFiles(id, rec.downloadType).forEach { if (it.exists) Logger.info("XDM", "Delete file $it ${it.delete()}") }
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
     * Deletes a torrent's files through the engine (it knows the file list and the hidden `.parts` file): a
     * seeding torrent is removed from the session with its files when [fromDisk]; one that is not in the
     * session loses its data when it was unfinished (like an HTTP part file) or when [fromDisk]. Must run
     * before the metadata is deleted.
     */
    private fun purgeTorrentFiles(rec: DbRecord, fromDisk: Boolean) {
        if (seeder.discard(rec.id, deleteFiles = fromDisk)) return
        if (rec.status == RecordStatus.FINISHED && !fromDisk) return
        val task = taskInfoDB.getTorrentTask(rec.id) ?: return
        torrents.deleteData(task.key, torrents.readTorrent(rec.id), torrents.readResume(rec.id), task.folder)
    }

    /**
     * Whether [a] and [b] are the same folder, also when spelled differently (letter case on macOS and
     * Windows, a symlink). Falls back to comparing absolute paths while either does not exist yet.
     */
    private fun sameFolder(a: String, b: String): Boolean {
        if (a == b) return true
        if (!isPath(a) || !isPath(b)) return false
        val fa = File(a)
        val fb = File(b)
        if (fa.absolutePath == fb.absolutePath) return true
        return runCatching { PlatformFiles.ops.isSameFile(fa, fb) }.getOrDefault(false)
    }

    /** The partial files a download may have in its target folder (and a Change folder scratch file). */
    private fun partFiles(id: Long, type: DownloadType): List<StorageFile> = when (type) {
        DownloadType.Http -> taskInfoDB.getHttpTask(id)?.let { t ->
            val folder = storage.folder(t.targetFolder)
            listOf(folder.file(partFileName(id)), folder.file("${partFileName(id)}.$id.part"))
        }.orEmpty()

        DownloadType.Hls, DownloadType.Dash -> listOf(".mp4", ".mkv").mapNotNull { ext ->
            streamingOutputFile(id, type, ext)
        }

        else -> emptyList()
    }

    /** Removes the per-download files in the config dir and any schedule entry for [id]. */
    private fun deleteMetadata(id: Long) {
        taskInfoDB.deleteRecord(id)
        listOf("$id.state", "$id.state.bak1", "$id.state.bak2").forEach { File(configDir, it).delete() }
        HlsKeyStore.delete(id, configDir)
        TorrentService.deleteFiles(id, configDir)
        refused.remove(id)
        linkRefresher.forget(id)
        hooks.downloadDeleted(id)
    }

    /**
     * A download id identifies its record, `task-<id>.info`, `<id>.state` and temp files, so it must be
     * unique. Returns [id] if no record uses it yet, otherwise a fresh id (logged).
     */
    private fun uniqueDownloadId(id: Long): Long {
        if (store.getById(id) == null) return id
        val fresh = CoreUtils.uniqueId()
        Logger.info("XDM", "Download id $id is already in use; using $fresh")
        return fresh
    }

    override fun startHttpDownload(task: HttpDownloadTaskInfo, runNow: Boolean): Long {
        val id = uniqueDownloadId(task.id)
        if (id != task.id) return startHttpDownload(task.copy(id = id), runNow)
        Logger.info("Adding new download: ${task.id} ${task.url}")
        taskInfoDB.saveHttpTask(task)
        duplicates.add(task.id, task.url, task.etag)
        store.add(
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
        ui.downloadAdded(task.id)
        if (runNow) {
            enqueue(task.id, resume = false)
        }
        return task.id
    }

    override fun addVideoDownload(
        videoId: Long, fileName: String, categoryId: String?, targetFolder: String, maxPiece: Int?,
        subtitle: SubtitleTrack?, convertToSrt: Boolean,
    ): Long? {
        // Each download of a detected video is a new download: copy the tracker's entry with a fresh
        // id instead of reusing (and mutating) it, so downloading the same video twice cannot share
        // records, task info, state or temp files.
        val tracker = CaptureContext.tracker
        tracker.getHttpVideo(videoId)?.let { source ->
            return startHttpDownload(
                source.copy(
                    id = CoreUtils.uniqueId(), fileName = fileName, categoryId = categoryId,
                    targetFolder = targetFolder,
                    maxPiece = maxPiece ?: source.maxPiece,
                )
            )
        }

        tracker.getSubtitle(videoId)?.let { captured ->
            return startHttpDownload(
                captured.task.copy(
                    id = CoreUtils.uniqueId(), fileName = fileName, categoryId = categoryId,
                    targetFolder = targetFolder,
                    convertToSrt = convertToSrt && captured.format.convertible,
                )
            )
        }

        // The tracker's entry lists every subtitle the stream has; the download keeps the picked one.
        tracker.getHlsVideo(videoId)?.let { source ->
            return startHlsDownload(
                source.copy(
                    id = CoreUtils.uniqueId(), fileName = fileName, categoryId = categoryId,
                    targetFolder = targetFolder,
                    maxPiece = maxPiece ?: source.maxPiece,
                    subtitles = listOfNotNull(subtitle),
                )
            )
        }

        return tracker.getDashVideo(videoId)?.let { source ->
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

    override fun startHlsDownload(task: HlsDownloadTaskInfo): Long {
        val id = uniqueDownloadId(task.id)
        // Copy rather than set tempDir on the caller's object.
        return startHls(task.copy(id = id, tempDir = streamingTempDir(task.targetFolder, id)))
    }

    private fun startHls(task: HlsDownloadTaskInfo): Long {
        taskInfoDB.saveHlsTask(task)
        store.add(
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
        ui.downloadAdded(task.id)
        enqueue(task.id, resume = false)

        return task.id
    }

    override fun startDashDownload(task: DashDownloadTaskInfo): Long {
        val id = uniqueDownloadId(task.id)
        // Copy rather than set tempDir on the caller's object.
        return startDash(task.copy(id = id, tempDir = streamingTempDir(task.targetFolder, id)))
    }

    private fun startDash(task: DashDownloadTaskInfo): Long {
        taskInfoDB.saveDashTask(task)
        store.add(
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
        ui.downloadAdded(task.id)
        enqueue(task.id, resume = false)

        return task.id
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
        store.add(
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
        ui.downloadAdded(task.id)
        enqueue(task.id, resume = false)
        return true
    }

    override fun retryFailedFiles(id: Long) {
        synchronized(queue) {
            if (activeSessions.containsKey(id) || queue.any { it.id == id }) return
            val rec = store.getById(id) ?: return
            if (rec.downloadType != DownloadType.Batch || rec.status != RecordStatus.FINISHED || rec.failedFiles == 0) return
            // The task retries every file that is not finished, which here is exactly the failed ones.
            rec.status = RecordStatus.READY
            rec.failedFiles = 0
            store.update(rec)
            ui.downloadUpdated(id)
            queue.add(QueueItem(id, true))
        }
        pumpQueue()
    }

    /**
     * Adds a torrent download. [torrent] is the .torrent file (from a file, or the metadata fetched for a
     * magnet), kept as `<id>.torrent`; null starts from [TorrentDownloadTaskInfo.magnet] alone. Returns the
     * download's id, or null when the same torrent is already in the list (or the task could not be saved).
     */
    fun startTorrentDownload(task: TorrentDownloadTaskInfo, torrent: ByteArray?, runNow: Boolean = true): Long? {
        if (findTorrent(task.key) != null) {
            Logger.info("XDM", "Torrent ${task.key} is already in the list")
            return null
        }
        val id = uniqueDownloadId(task.id)
        var t = if (id != task.id) task.copy(id = id) else task
        // The user's extra trackers, for public torrents only (a private torrent's tracker must stay its only one).
        var bytes = torrent
        val tiers = if (config.torrentAddTrackers) TorrentTrackers.tiers(config.torrentTrackerList) else emptyList()
        if (tiers.isNotEmpty()) {
            if (bytes != null) {
                if (torrents.parse(bytes)?.isPrivate == false) bytes = TorrentTrackers.addToTorrent(bytes, tiers) ?: bytes
            } else {
                t.magnet?.let { t = t.copy(magnet = TorrentTrackers.addToMagnet(it, tiers)) }
            }
        }
        if (bytes != null && !torrents.saveTorrent(id, bytes)) return null
        if (!taskInfoDB.saveTorrentTask(t)) {
            TorrentService.deleteFiles(id, configDir)
            return null
        }
        Logger.info("Adding torrent: $id ${t.key} -> ${t.folder}")
        store.add(
            DbRecord(
                id = id,
                size = 0,
                downloaded = 0,
                progress = 0,
                date = System.currentTimeMillis(),
                fileName = t.name,
                eta = 0,
                speed = 0.0f,
                status = if (runNow) RecordStatus.READY else RecordStatus.PAUSED,
                selected = false,
                downloadType = DownloadType.Torrent,
                categoryId = t.categoryId,
            )
        )
        ui.downloadAdded(id)
        if (runNow) enqueue(id, resume = false)
        return id
    }

    /** The download id of the torrent with [key] in the list, or null. */
    fun findTorrent(key: String): Long? =
        store.filter { it.downloadType == DownloadType.Torrent }
            .firstOrNull { taskInfoDB.getTorrentTask(it.id)?.key == key }?.id

    /** Whether [id] is a torrent seeding right now. */
    fun isSeeding(id: Long): Boolean = seeder.isSeeding(id)

    /** Starts seeding a finished torrent by hand, until the seeding limits; false when it cannot. */
    fun startSeeding(id: Long): Boolean {
        val rec = store.getById(id) ?: return false
        if (rec.downloadType != DownloadType.Torrent || rec.status != RecordStatus.FINISHED || !torrents.available) return false
        val task = taskInfoDB.getTorrentTask(id) ?: return false
        // Seeding from resume data that says "complete" over missing files would only end in a file error.
        if (!torrentFilesPresent(id)) {
            Logger.info("Torrent", "Not seeding $id: its files are not on disk")
            return false
        }
        return seeder.start(id, task.key, task.folder)
    }

    /**
     * Whether [id]'s wanted files are on disk at their full size (padding and skipped files aside). Without
     * saved metadata only the torrent's own file or folder is checked. Reads the file system: keep it off hot paths.
     */
    fun torrentFilesPresent(id: Long): Boolean {
        val task = taskInfoDB.getTorrentTask(id) ?: return false
        val folder = storage.folder(task.folder)
        val info = torrents.torrentInfo(id)
            ?: return folder.file(task.name).exists || folder.child(task.name).exists()
        val p = task.filePriorities
        return info.files.withIndex().all { (i, f) ->
            val wanted = p.isEmpty() || (i < p.size && p[i] != FilePriority.SKIP)
            f.isPad || !wanted || folder.file(f.path).let { it.exists && it.length() >= f.size }
        }
    }

    /** Stops seeding [id]; its resume data is saved and it leaves the session. */
    fun stopSeeding(id: Long) = seeder.stop(id)

    /** The torrent window's live view of [id] while it is in the session (downloading or seeding), else null. */
    fun torrentDetails(id: Long): TorrentDetails? = taskInfoDB.getTorrentTask(id)?.let { torrents.details(it.key) }

    /** [id]'s files from its saved metadata, for when it is not in the session; null without metadata. */
    fun torrentFiles(id: Long): TorrentInfo? = torrents.torrentInfo(id)

    /**
     * Sets [id]'s file priorities (libtorrent's file order; empty means all normal). They are saved with the
     * download and applied at once while it runs. A finished torrent that now wants a file it skipped goes back
     * to downloading (seeding stops first); one that only drops files stays finished.
     */
    fun setTorrentFilePriorities(id: Long, priorities: ByteArray) {
        val task = taskInfoDB.getTorrentTask(id) ?: return
        val old = task.filePriorities
        task.filePriorities = priorities
        if (!taskInfoDB.saveTorrentTask(task)) return
        val status = synchronized(store) { store.getById(id)?.status } ?: return
        if (status == RecordStatus.FINISHED && wantsMore(old, priorities)) {
            Logger.info("Torrent", "Torrent $id wants more files: downloading again")
            if (seeder.isSeeding(id)) seeder.stop(id)
            resumeDownload(id)
            return
        }
        torrents.setFilePriorities(task.key, priorities)
    }

    /**
     * Adds trackers (the settings text format: one per line, blank line = next tier) to [id] while it is in the
     * session. False when it is not running, is private, or the text holds no tracker.
     */
    fun addTorrentTrackers(id: Long, text: String): Boolean {
        val task = taskInfoDB.getTorrentTask(id) ?: return false
        val tiers = TorrentTrackers.tiers(text).takeIf { it.isNotEmpty() } ?: return false
        return torrents.addTrackers(task.key, tiers)
    }

    /** Whether [new] wants a file [old] skipped (empty wants all). */
    private fun wantsMore(old: ByteArray, new: ByteArray): Boolean {
        fun wants(p: ByteArray, i: Int) = p.isEmpty() || (i < p.size && p[i] != FilePriority.SKIP)
        return (0 until maxOf(old.size, new.size)).any { !wants(old, it) && wants(new, it) }
    }

    /** Applies the BitTorrent settings (limits, port, DHT/LSD, port mapping, encryption) to the running session. */
    fun applyTorrentSettings() {
        torrents.applySettings(TorrentSettings.lines(torrentOptions() + torrentLimits()))
    }

    /**
     * For app exit: saves the torrents' resume data (waiting up to [timeoutMs] for it) and the session state,
     * then closes the session. Nothing to do when no torrent ran.
     */
    fun shutdown(timeoutMs: Long = 30_000) = torrents.shutdown(timeoutMs)

    private fun torrentLimits() = TorrentSettings.limits(
        config.torrentDownloadLimit.coerceAtLeast(0) * 1024, config.torrentUploadLimit.coerceAtLeast(0) * 1024,
    )

    private fun torrentOptions() = TorrentSettings.options(
        config.torrentPortMapping, config.torrentDht, config.torrentLsd, config.torrentEncryption, config.torrentPort,
        config.torrentPeerIdPrefix, config.torrentUserAgent,
    )

    private fun torrentSessionSettings(listenPort: Int?): String {
        val proxy = if (config.useProxy && config.proxyHost.isNotBlank() && config.proxyPort > 0) TorrentProxy(
            if (config.socksProxy) TorrentProxy.Type.SOCKS5 else TorrentProxy.Type.HTTP,
            config.proxyHost, config.proxyPort, config.proxyUser.takeIf { it.isNotEmpty() }, config.proxyPass,
        ) else null
        // A fixed port from the settings wins over the saved state's; options() sets it.
        return TorrentSettings.session(
            listenPort.takeIf { config.torrentPort <= 0 },
            config.torrentDownloadLimit.coerceAtLeast(0) * 1024, config.torrentUploadLimit.coerceAtLeast(0) * 1024,
            proxy, torrentOptions(),
        )
    }

    private fun setSeeding(id: Long, seeding: Boolean, progress: DownloadStatusInfo.TorrentProgress?) {
        synchronized(store) {
            store.getById(id)?.let {
                it.seeding = seeding
                it.torrent = progress
            }
        }
        ui.downloadUpdated(id)
        progress?.let { ui.torrentProgress(id, it) }
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

    override fun updateDownloadInfo(id: Long, task: HttpDownloadTaskInfo) {
        applyLinkUpdate(id, task.url, task.headers, task.cookie)
        // A new capture from the browser brings its own source link, if it came through a redirect.
        if (task.sourceUrl == null) return
        taskInfoDB.getHttpTask(id)?.let {
            it.sourceUrl = task.sourceUrl
            it.sourceCookie = task.sourceCookie
            taskInfoDB.saveHttpTask(it)
        }
        linkRefresher.forget(id)
    }

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

    override fun updateTrackLinks(
        id: Long, videoUrl: String?, audioUrl: String?, headers: HeaderMap?, cookie: String?,
    ): Boolean {
        if (activeSessions.containsKey(id)) return false
        val task = taskInfoDB.getDashTask(id) ?: return false
        if (task.extractor == null) return false
        fun relink(segments: List<DashSegment>, url: String?) =
            url?.toHttpUrlOrNull()?.let { u -> segments.map { it.copy(url = u) } } ?: segments
        task.videoSegments = relink(task.videoSegments, videoUrl)
        task.audioSegments = relink(task.audioSegments, audioUrl)
        task.headers = headers
        task.cookie = cookie
        task.url = videoUrl ?: audioUrl ?: task.url
        taskInfoDB.saveDashTask(task)
        // A download that never ran has no state yet: the task info is all there is.
        if (!AtomicIO.exists("$id.state", configDir)) return true
        val http = newHttpClient(id)
        try {
            loadDashState(id, configDir, http, downloadHost).onSuccess { context ->
                context.chunks.forEach {
                    when (it.tag) {
                        "VIDEO" -> videoUrl?.let { u -> it.url = u }
                        "AUDIO" -> audioUrl?.let { u -> it.url = u }
                    }
                }
                context.url = task.url
                context.headers = headers
                context.cookie = cookie
                saveState(context, configDir)
            }.onFailure {
                Logger.error("XDM", "Could not update the links of download $id", it)
                return false
            }
        } finally {
            http.close()
        }
        Logger.info("XDM", "Download $id has fresh links")
        return true
    }

    override fun isMoving(id: Long): Boolean = id in moving

    override fun changeFolderPlan(id: Long, folder: String): ChangeFolderPlan {
        val rec = store.getById(id) ?: return ChangeFolderPlan.NOT_ALLOWED
        if (!rec.status.isStopped || id in moving || activeSessions.containsKey(id)) return ChangeFolderPlan.NOT_ALLOWED
        if (synchronized(queue) { queue.any { it.id == id } }) return ChangeFolderPlan.NOT_ALLOWED
        val newFolder = normalizeLocation(folder)
        return when (rec.downloadType) {
            DownloadType.Http -> {
                val task = taskInfoDB.getHttpTask(id) ?: return ChangeFolderPlan.NOT_ALLOWED
                if (sameFolder(task.targetFolder, newFolder)) return ChangeFolderPlan.SAME
                val hasProgress = File(configDir, "$id.state").isFile
                if (!hasProgress || storage.folder(task.targetFolder).isFile(partFileName(id))) ChangeFolderPlan.MOVE
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
        val newFolder = normalizeLocation(folder)
        // Claimed under both locks, so a resume, delete or queue start cannot slip in meanwhile.
        val previous = synchronized(queue) {
            if (activeSessions.containsKey(id) || queue.any { it.id == id }) return done(ChangeFolderResult.FAILED)
            synchronized(store) {
                val rec = store.getById(id)
                if (rec == null || !rec.status.isStopped || !moving.add(id)) return done(ChangeFolderResult.FAILED)
                rec.status
            }
        }
        moveCancelled.remove(id)
        Thread {
            val result = runCatching { moveTo(id, newFolder) }
                .onFailure { Logger.error("XDM", "Change folder failed for $id", it) }
                .getOrDefault(ChangeFolderResult.FAILED)
            synchronized(store) {
                moving.remove(id)
                moveCancelled.remove(id)
                store.getById(id)?.let {
                    it.status = if (result == ChangeFolderResult.DONE) RecordStatus.PAUSED else previous
                    it.movePhase = null
                    it.progress = if (it.size > 0) (it.downloaded * 100 / it.size).toInt() else 0
                    store.update(it)
                }
            }
            ui.downloadUpdated(id)
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
        val folder = storage.folder(newFolder)
        if (!folder.create()) return ChangeFolderResult.FAILED
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
        val source = storage.folder(task.targetFolder).file(partFileName(id))
        if (!File(configDir, "$id.state").isFile || !source.exists) {
            // Nothing written yet: only the folder changes.
            task.targetFolder = newFolder
            taskInfoDB.saveHttpTask(task)
            return ChangeFolderResult.DONE
        }
        val total = source.length().coerceAtLeast(1)
        val target = folder.file(partFileName(id))
        val progress = { copied: Long ->
            updateMoveProgress(id, (copied * 100 / total).toInt())
            !moveCancelled.contains(id)
        }
        val sourcePath = source.localPath
        val targetPath = target.localPath
        val error = if (sourcePath != null && targetPath != null) {
            FileUtils.copyFile(
                File(sourcePath), File(targetPath), id = id, replaceExisting = true,
                phase = { enterMovePhase(id, it) }, progress = progress,
            )
        } else {
            copyThroughStorage(source, target, phase = { enterMovePhase(id, it) }, progress = progress)
        }
        return when (error) {
            null -> {
                task.targetFolder = newFolder
                // The old file goes only once the record provably points at the copy; otherwise the copy
                // goes and the download stays where it was.
                val recorded = taskInfoDB.saveHttpTask(task) &&
                        taskInfoDB.getHttpTask(id)?.targetFolder == newFolder
                if (!recorded) {
                    Logger.error("XDM", "Could not record the new folder of $id; keeping it in ${source.folder}")
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

    /**
     * [FileUtils.copyFile] for files at least one of which has no local path (a SAF document on Android):
     * copies [source] into a scratch file beside [target], then renames it into place. Same results.
     */
    private fun copyThroughStorage(
        source: StorageFile, target: StorageFile, phase: (MovePhase) -> Boolean, progress: (Long) -> Boolean,
    ): DownloadError? {
        if (!phase(MovePhase.COPYING)) return DownloadError.Cancelled
        val scratch = target.folder.file("${target.name}.move")
        try {
            val size = source.length()
            val free = target.folder.usableSpace()
            if (free in 0 until size) return DownloadError.DiskSpaceError
            source.folder.openRead(source.name).use { input ->
                scratch.open(create = true).apply { setLength(0) }.asOutputStream().use { out ->
                    val buf = ByteArray(COPY_BUFFER)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (!progress(copied)) {
                            out.close()
                            scratch.delete()
                            return DownloadError.Cancelled
                        }
                    }
                }
            }
            scratch.folder.force(scratch.name)
            if (!phase(MovePhase.FINALIZING)) {
                scratch.delete()
                return DownloadError.Cancelled
            }
            target.folder.rename(scratch.name, target.name, replace = true)
            return null
        } catch (e: Exception) {
            Logger.error("XDM", "Copy $source -> $target failed", e)
            scratch.delete()
            return DownloadError.OutputWriteError
        }
    }

    /**
     * A run's client: the shared certificate checks unless the app replaces them ([PlatformHooks.insecureTrust]).
     * Refused certificates are collected for [resumeTrustingRefused]; a new run starts with none.
     */
    private val linkRefresher = LinkRefresher({
        HttpClientImpl(
            1, config.toProxy(), hooks.insecureTrust(), config.readTimeoutSeconds,
            config.proxyUser, config.proxyPass, auth = hooks.httpAuth, serverAuth = true,
        )
    })

    /**
     * An HTTP download whose link expired gets a fresh one from the link the browser was redirected from
     * ([LinkRefresher]) and resumes; the error is shown only if that gives nothing. True when a refresh was
     * started: the caller then shows nothing.
     */
    private fun tryRefreshLink(id: Long, error: DownloadError): Boolean {
        if (error != DownloadError.LinkExpired) return false
        val task = taskInfoDB.getHttpTask(id)?.takeIf { it.sourceUrl != null } ?: return false
        val state = AtomicIO.readTransacted("$id.state", configDir) { fs -> readContext(fs, downloadHost, "", "") }
            .getOrNull()
        if (!linkRefresher.claim(id, state?.downloaded?.get() ?: 0L)) return false
        Logger.info("XDM", "Download $id: link expired, asking its source link for a fresh one")
        Thread({
            val url = runCatching { linkRefresher.resolve(task, state?.totalSize, state?.validator) }
                .onFailure { Logger.error("XDM", "Download $id: refreshing the link failed", it) }
                .getOrNull()
            // Resumed, deleted or refreshed by hand meanwhile: that is the user's choice, left alone.
            val stillFailed = !activeSessions.containsKey(id) && store.getById(id)?.status == RecordStatus.ERROR
            if (!stillFailed) return@Thread
            if (url == null) {
                ui.downloadFailed(id, error)
                hooks.downloadFailed(id)
                return@Thread
            }
            Logger.info("XDM", "Download $id: fresh link from its source, resuming")
            updateDownloadLink(id, url, task.headers, task.cookie)
            resumeDownload(id)
        }, "link-refresh-$id").apply { isDaemon = true }.start()
        return true
    }

    private fun newHttpClient(id: Long): HttpClientImpl {
        refused.remove(id)
        return HttpClientImpl(
            CoreConfig.MAX_SEGMENTS, config.toProxy(), hooks.insecureTrust(),
            config.readTimeoutSeconds,
            config.proxyUser, config.proxyPass,
            auth = hooks.httpAuth, serverAuth = true,
            onRefused = { host, fingerprint ->
                refused.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }.add("$host|$fingerprint")
            },
        )
    }

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
        hooks.keepAwake(config.keepAwake && activeSessions.isNotEmpty())
    }

    /**
     * The only place downloads are started: starts queued downloads in order while fewer than
     * `maxParallelDownloads` are active. Called after enqueueing and whenever a session actually
     * ends (inside the `activeSessions.remove(id)?.let {}` guards), so duplicate engine callbacks
     * cannot start extra downloads. A download that cannot start is marked paused and skipped.
     * Lock order is queue -> store; callers must not hold the store lock.
     */
    /** True while a download is running or waiting in the queue. Takes the queue lock only. */
    fun hasPendingDownloads(): Boolean = synchronized(queue) { queue.isNotEmpty() || activeSessions.isNotEmpty() }

    private fun pumpQueue() {
        synchronized(queue) {
            while (activeSessions.size < config.maxParallelDownloads && queue.isNotEmpty()) {
                val item = queue.removeFirst()
                if (!launch(item)) markNotStarted(item.id)
            }
            syncKeepAwake()
            if (queue.isEmpty() && activeSessions.isEmpty()) {
                Logger.info("No pending download")
                hooks.allDownloadsDone()
            }
        }
    }

    /** Marks a freshly completed file as downloaded from the internet. A failure is only logged. */
    private fun markDownloaded(event: DownloadStatusInfo.FinalInfo) {
        // A document without a path (SAF) carries no mark.
        val file = storage.folder(event.finalOutputFolder).file(event.finalFileName).localPath?.let { File(it) }
            ?: return
        val type = synchronized(store) { store.getById(event.id)?.downloadType }
        if (type == DownloadType.Batch) return markBatchDownloaded(event.id)
        if (type == DownloadType.Torrent && markTorrentDownloaded(event)) return
        val source = when (type) {
            DownloadType.Http -> taskInfoDB.getHttpTask(event.id)?.let { DownloadSource(it.url, it.origin) }
            DownloadType.Hls -> taskInfoDB.getHlsTask(event.id)?.let { DownloadSource(it.url, it.origin) }
            DownloadType.Dash -> taskInfoDB.getDashTask(event.id)?.let { DownloadSource(it.url, it.origin) }
            DownloadType.Torrent -> taskInfoDB.getTorrentTask(event.id)?.let { DownloadSource(it.magnet, it.origin) }
            else -> null
        } ?: DownloadSource(null, null)
        try {
            if (!hooks.markDownloadedFile(file, source)) {
                Logger.info("Download ${event.id} finished but could not be marked as downloaded: $file")
            }
        } catch (e: Exception) {
            Logger.error("Error marking $file as downloaded", e)
        }
    }

    /**
     * Marks each file of a multi-file torrent (a mark on its folder does not reach the files on Windows).
     * False when the torrent is a single file or its file list is unknown: then [markDownloaded] marks
     * [DownloadStatusInfo.FinalInfo.finalFileName] as usual.
     */
    private fun markTorrentDownloaded(event: DownloadStatusInfo.FinalInfo): Boolean {
        val info = torrents.torrentInfo(event.id) ?: return false
        if (info.singleFile) return false
        val task = taskInfoDB.getTorrentTask(event.id)
        val source = DownloadSource(task?.magnet, task?.origin)
        val folder = storage.folder(event.finalOutputFolder)
        for (f in info.files) {
            if (f.isPad) continue
            // Skipped files may not exist (or hold only the pieces shared with a wanted file).
            val file = folder.file(f.path).localPath?.let { File(it) } ?: continue
            if (!file.isFile) continue
            try {
                hooks.markDownloadedFile(file, source)
            } catch (e: Exception) {
                Logger.error("Error marking $file as downloaded", e)
            }
        }
        return true
    }

    /** Marks each file a batch finished with its own link as the source. */
    private fun markBatchDownloaded(id: Long) {
        val task = taskInfoDB.getBatchTask(id) ?: return
        val state = loadBatchState(id, configDir).getOrNull() ?: return
        for (f in state.files) {
            val name = f.finalName ?: continue
            if (f.status.get() != ChunkStatus.Finished) continue
            val file = storage.folder(state.batchFolder).file(name).localPath?.let { File(it) } ?: continue
            try {
                hooks.markDownloadedFile(file, DownloadSource(task.items[f.index].url, task.origin))
            } catch (e: Exception) {
                Logger.error("Error marking $file as downloaded", e)
            }
        }
    }

    /** The app's own reaction to a freshly completed file: sound, antivirus scan, custom command. */
    private fun runPostDownloadActions(event: DownloadStatusInfo.FinalInfo) {
        val type = synchronized(store) { store.getById(event.id)?.downloadType } ?: DownloadType.Http
        // A batch's final name is only its label; its files are in the folder.
        val path = if (type == DownloadType.Batch) File(event.finalOutputFolder).absolutePath
        else File(event.finalOutputFolder, event.finalFileName).absolutePath
        try {
            hooks.downloadFinished(path, type)
        } catch (error: Exception) {
            Logger.error("XDM", "Error running post-download actions", error)
        }
    }

    private companion object {
        const val COPY_BUFFER = 1 shl 20

        /** How many times a publish rename is tried, waiting a little longer each time. */
        const val PUBLISH_ATTEMPTS = 5
        const val PUBLISH_RETRY_MS = 200L

        /** Largest subtitle converted to SRT; anything bigger is not a subtitle file. */
        const val MAX_SUBTITLE_BYTES = 16L shl 20
    }
}

/** Whether [location] is a file system path rather than a provider location (a SAF tree on Android). */
internal fun isPath(location: String) = !location.startsWith("content:")

/** A location as recorded: paths made absolute, provider locations unchanged. */
internal fun normalizeLocation(location: String) = if (isPath(location)) File(location).absolutePath else location
