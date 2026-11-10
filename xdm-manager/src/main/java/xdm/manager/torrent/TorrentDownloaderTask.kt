// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.DownloaderTask
import xdm.core.downloaders.PauseEvent
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.TorrentDownloadTaskInfo
import xdm.core.downloaders.TorrentPhase
import xdm.core.util.Logger

/**
 * One run of a torrent download, driven by XDM's queue like any other download. Starting adds the torrent
 * to the session (from `<id>.resume`, else `<id>.torrent`, else the magnet link); stopping pauses it, saves
 * its resume data and removes it from the session, then reports the pause. Finishing (all selected files
 * complete) saves the resume data and either removes it or hands it to [onFinished] for seeding.
 *
 * Every report to [host] goes out on the service's event thread; nothing here blocks on an event.
 */
class TorrentDownloaderTask(
    private var task: TorrentDownloadTaskInfo,
    private val host: DownloadHost,
    private val service: TorrentService,
    private val taskInfoDB: TaskInfoDB,
    /**
     * Called on the event thread when the torrent finished, after its resume data is saved and before the
     * success is reported. Returns true when it takes the torrent over (seeding); else it is removed.
     */
    private val onFinished: (id: Long, key: String) -> Boolean = { _, _ -> false },
) : DownloaderTask, TorrentListener {
    private enum class Phase { IDLE, ADDING, RUNNING, STOPPING, FINISHING, FAILING, DONE }

    private val id get() = task.id
    private val key get() = task.key

    private var phase = Phase.IDLE
    private var failure: DownloadError? = null
    /** While FINISHING: the files are flushed, so the resume data may be asked for. */
    private var flushed = false
    private var lastWanted = -1L
    private var lastName: String? = null

    override fun start() = run()
    override fun resume() = run()

    private fun run() {
        synchronized(this) {
            if (phase != Phase.IDLE && phase != Phase.DONE) return
            phase = Phase.ADDING
            failure = null
        }
        try {
            service.whenFree(key, this) { e -> add(e) }
        } catch (ex: Exception) {
            // The session could not start (library or settings problem).
            Logger.error("Torrent", "Unable to start torrent $id", ex)
            synchronized(this) { phase = Phase.DONE }
            host.onDownloadFailed(id, DownloadError.InternalError)
        }
    }

    private fun add(e: TorrentEngine) {
        synchronized(this) {
            if (phase != Phase.ADDING) return // stopped while the add was waiting
            service.attach(key, id, this)
            phase = Phase.RUNNING
        }
        val resume = service.readResume(id)
        val torrent = service.readTorrent(id)
        // Empty means every file normal. Resume data carries the priorities of the last run (a file skipped
        // while seeding, say): over it, "all normal" has to be said explicitly, one per file.
        val priorities = task.filePriorities.takeIf { it.isNotEmpty() }
            ?: if (resume != null) torrent?.let { service.parse(it) }?.files?.let { f -> ByteArray(f.size) { FilePriority.NORMAL } }
            else null
        val flags = (if (task.sequential) TorrentFlags.SEQUENTIAL else 0) or service.addFlags()
        val added = when {
            resume != null || torrent != null -> e.addTorrent(torrent, resume, task.folder, priorities, flags)
            task.magnet != null -> e.addMagnet(task.magnet!!, null, task.folder, flags)
            else -> throw IllegalStateException("Torrent $id has no metadata, resume data or magnet link")
        }
        if (added != key) Logger.error("Torrent", "Torrent $id was added as $added, expected $key")
        runCatching { e.setLimits(key, 0, 0, TorrentSettings.MAX_CONNECTIONS_PER_TORRENT) }
        host.onDownloadActivated(id)
        Logger.info("Torrent", "Torrent $id ($key) added to the session")
    }

    override fun onAddFailed(error: Exception) {
        synchronized(this) {
            service.detach(key, this)
            phase = Phase.DONE
        }
        host.onDownloadFailed(id, DownloadError.InternalError)
    }

    override fun stop() {
        val state = synchronized(this) {
            val p = phase
            when (p) {
                Phase.ADDING -> phase = Phase.DONE
                Phase.RUNNING -> phase = Phase.STOPPING
                else -> {}
            }
            p
        }
        when (state) {
            Phase.ADDING -> {
                service.cancelDeferred(key)
                host.onDownloadPaused(id, PauseEvent.PausedByUser)
            }

            Phase.RUNNING -> saveAndRemove {
                runCatching { it.pause(key) }
            }

            else -> {}
        }
    }

    /** Asks for resume data; the answer (any of the three) removes the torrent ([removeAndReport]). */
    private fun saveAndRemove(before: (TorrentEngine) -> Unit = {}) {
        val e = runCatching { service.start() }.getOrNull()
        if (e == null) {
            removeAndReport()
            return
        }
        before(e)
        try {
            e.requestResume(key, 0)
        } catch (ex: TorrentEngineException) {
            Logger.error("Torrent", "Resume data of $id: ${ex.message}")
            removeAndReport()
        }
    }

    override fun onEvent(event: TorrentEvent) {
        when (event.type) {
            TorrentEventType.STATUS -> event.status?.let(::progress)
            TorrentEventType.METADATA -> metadataReceived()
            TorrentEventType.FINISHED -> finished()
            TorrentEventType.ERROR -> failed(event)
            TorrentEventType.FLUSHED -> {
                val next = synchronized(this) {
                    (phase == Phase.FINISHING && !flushed).also { if (it) flushed = true }
                }
                if (next) saveAndRemove()
            }
            TorrentEventType.RESUME_DATA, TorrentEventType.RESUME_SKIPPED, TorrentEventType.RESUME_FAILED -> {
                val waiting = synchronized(this) {
                    phase == Phase.STOPPING || phase == Phase.FAILING || (phase == Phase.FINISHING && flushed)
                }
                if (waiting) removeAndReport()
            }

            TorrentEventType.METADATA_FAILED -> Logger.error("Torrent", "Metadata of $id: ${event.message}")
            else -> {}
        }
    }

    private fun progress(s: TorrentStatus) {
        if (synchronized(this) { phase } != Phase.RUNNING) return
        if (s.hasMetadata && (s.totalWanted != lastWanted || s.name != lastName)) {
            lastWanted = s.totalWanted
            lastName = s.name
            if (s.name.isNotEmpty() && s.name != task.name) {
                task.name = s.name
                taskInfoDB.saveTorrentTask(task)
            }
            host.onDownloadInit(
                DownloadStatusInfo.InitInfo(id, task.magnet ?: "", false, s.totalWanted, null, null),
                DownloadType.Torrent,
            )
        }
        val wanted = s.totalWanted
        val done = s.totalWantedDone
        val rate = s.downloadRate.toLong()
        host.onDownloadProgress(
            DownloadStatusInfo.ProgressInfo(
                id = id,
                progress = if (wanted > 0) (done * 100 / wanted).toInt() else 0,
                speed = rate.toFloat(),
                eta = if (rate > 0 && wanted > done) (wanted - done) / rate else 0,
                downloaded = done,
                torrent = torrentProgress(s),
            )
        )
    }

    private fun metadataReceived() {
        val e = runCatching { service.start() }.getOrNull() ?: return
        // Kept so the next start (and a delete) does not depend on finding peers for the metadata again.
        runCatching { e.metadata(key) }.getOrNull()?.let { service.saveTorrent(id, it) }
        e.info(key)?.let { info ->
            if (info.name.isNotEmpty() && info.name != task.name) {
                task.name = info.name
                taskInfoDB.saveTorrentTask(task)
            }
        }
    }

    private fun finished() {
        synchronized(this) {
            if (phase != Phase.RUNNING) return
            phase = Phase.FINISHING
            flushed = false
        }
        // A small torrent can finish before the first status update: take name and size from its files.
        runCatching { service.start().info(key) }.getOrNull()?.let { info ->
            lastWanted = info.files.filter { !it.isPad && it.priority != FilePriority.SKIP }.sumOf { it.size }
            if (info.name.isNotEmpty() && info.name != task.name) {
                task.name = info.name
                taskInfoDB.saveTorrentTask(task)
            }
        }
        Logger.info("Torrent", "Torrent $id finished")
        // FINISHED can come before the last pieces reach the files (they are hashed from memory): success is
        // reported only after the flush (FLUSHED), then the resume data.
        try {
            service.start().flush(key)
        } catch (ex: Exception) {
            Logger.error("Torrent", "Flush of $id failed", ex)
            synchronized(this) { flushed = true }
            saveAndRemove()
        }
    }

    private fun failed(event: TorrentEvent) {
        synchronized(this) {
            if (phase != Phase.RUNNING) return
            phase = Phase.FAILING
            failure = errorFor(event.code)
        }
        Logger.error("Torrent", "Torrent $id failed: ${event.code} ${event.message}")
        saveAndRemove { runCatching { it.pause(key) } }
    }

    /** The end of stop, finish and failure: the resume data is saved (or could not be). */
    private fun removeAndReport() {
        val ended = synchronized(this) {
            val p = phase
            if (p != Phase.STOPPING && p != Phase.FINISHING && p != Phase.FAILING) return
            phase = Phase.DONE
            p
        }
        val seeding = ended == Phase.FINISHING && runCatching { onFinished(id, key) }.getOrDefault(false)
        if (!seeding) {
            service.detach(key, this)
            service.remove(key, deleteFiles = false)
        }
        when (ended) {
            Phase.STOPPING -> host.onDownloadPaused(id, PauseEvent.PausedByUser)
            Phase.FAILING -> host.onDownloadFailed(id, failure ?: DownloadError.InternalError)
            else -> host.onDownloadSuccess(
                DownloadStatusInfo.FinalInfo(id, lastWanted.coerceAtLeast(0), task.name, task.folder)
            )
        }
    }

    /**
     * Deletes the partial data. Only called once the task has stopped, so the torrent is out of the session:
     * it is added back paused just to delete its files.
     */
    override fun deleteTemp() {
        service.deleteData(key, service.readTorrent(id), service.readResume(id), task.folder)
    }

    companion object {
        /** File errors whose code means the disk is full (POSIX ENOSPC, Windows ERROR_DISK_FULL / HANDLE_DISK_FULL). */
        private val DISK_FULL = setOf(28, 112, 39)

        fun errorFor(code: Int): DownloadError =
            if (code in DISK_FULL) DownloadError.DiskSpaceError else DownloadError.OutputWriteError

        fun torrentProgress(s: TorrentStatus, seeding: Boolean = false) = DownloadStatusInfo.TorrentProgress(
            phase = when {
                seeding -> TorrentPhase.Seeding
                !s.hasMetadata -> TorrentPhase.Metadata
                s.checking -> TorrentPhase.Checking
                s.finished -> TorrentPhase.Finished
                else -> TorrentPhase.Downloading
            },
            wanted = s.totalWanted,
            uploadSpeed = s.uploadRate.toLong(),
            uploaded = s.allTimeUpload,
            ratio = s.ratio,
            peers = s.peers,
            seeds = s.seeds,
            checkPermille = if (s.checking) s.progressPpm / 1000 else 0,
            seeding = seeding,
        )
    }
}
