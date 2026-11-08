// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager

import xdm.capture.CaptureConfig
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.web.SegmentProgress
import xdm.core.network.http.CertTrust
import xdm.core.network.http.HttpAuth
import xdm.core.storage.LocalStorage
import xdm.core.storage.Storage
import xdm.core.util.MovePhase
import java.io.File

/** The settings the download lifecycle reads; each app keeps them in its own settings store. */
interface EngineConfig : CaptureConfig {
    val maxParallelDownloads: Int

    /** Show the per-download progress window (desktop) or its equivalent when a download starts. */
    val showDownloadProgressWindow: Boolean

    /** What to show when a download finishes. */
    val downloadCompleteNotification: DownloadCompleteNotification

    /** On a name clash at publish: replace the existing file. Wins over [autoRenameOnConflict]. */
    val overwriteExistingFiles: Boolean

    /** On a name clash at publish: pick a free name. */
    val autoRenameOnConflict: Boolean

    /** Hold a wake lock while anything transfers ([PlatformHooks.keepAwake]). */
    val keepAwake: Boolean

    /** Mark finished files as coming from the internet ([PlatformHooks.markDownloadedFile]). */
    val markDownloadedFiles: Boolean

    /** User-editable categories. Replaced as a whole on every change, never mutated in place. */
    val categories: List<DownloadCategory>
}

/**
 * Where the download list lives: the desktop's in-memory list over tagged files, or SQLite on Android.
 *
 * Records are live objects: the manager changes progress fields in place without saving, and calls
 * [update] when a change must survive a restart (status, name, size). The manager locks the store
 * instance (`synchronized(store)`) around read-modify-write sequences; lock order is the manager's
 * queue first, then the store, never the reverse.
 */
interface DownloadStore {
    fun getById(id: Long): DbRecord?

    /** A copy of the records matching [predicate]. */
    fun filter(predicate: (DbRecord) -> Boolean): List<DbRecord>

    /** Adds a new record and persists it. */
    fun add(rec: DbRecord)

    /** Persists [rec]'s current state; its status may have moved it between lists. */
    fun update(rec: DbRecord)

    /** Removes the record with [id]; returns the position it had in the list, or null when there was none. */
    fun remove(id: Long): Int?

    /** Removes and returns the records matching [predicate], persisting once. */
    fun removeWhere(predicate: (DbRecord) -> Boolean): List<DbRecord>
}

/**
 * What the download list shows. Called from download threads: each implementation marshals to its
 * UI thread itself (the EDT on the desktop, the main looper on Android).
 */
interface UiSink {
    fun downloadAdded(id: Long)
    fun downloadUpdated(id: Long)

    /** [id] was removed; [position] is where it was in the store ([DownloadStore.remove]). */
    fun downloadRemoved(id: Long, position: Int)

    /** Several downloads were removed together: refresh the list once. */
    fun downloadsRemoved()

    fun showProgress(id: Long, fileName: String)
    fun hideProgress(id: Long)

    fun progress(
        id: Long,
        fileName: String?,
        downloaded: Long,
        size: Long,
        speed: Float,
        eta: Long,
        progress: Int,
        segments: Collection<SegmentProgress>,
        /** A batch's file counts; null for other downloads. */
        files: BatchFileCounts? = null,
    )

    /** Change folder / publish progress, where download events have stopped. */
    fun moveProgress(id: Long, phase: MovePhase, progress: Int)

    fun downloadFailed(id: Long, error: DownloadError)

    /**
     * Advisory: the volume a starting download writes to looks too small. [isTempFolder] says whether that
     * is the temp folder (HLS/DASH segments) or the download's own folder (HTTP).
     */
    fun spaceWarning(folder: String, needed: Long, free: Long, isTempFolder: Boolean)

    /** [DownloadCompleteNotification.DIALOG]. */
    fun downloadCompleteDialog(id: Long, folder: String, fileName: String, fileSize: Long)

    /** [DownloadCompleteNotification.NOTIFICATION]; a click opens [folder]. */
    fun downloadCompleteNotification(folder: String, fileName: String)
}

/** OS integration around the download lifecycle. Every method may be called from any thread. */
interface PlatformHooks {
    /** Answers 401 / 407 for every download client. */
    val httpAuth: HttpAuth

    /**
     * Certificate checks of the app's own for download clients, or null for the shared ones (the
     * platform's plus the user's certificate exceptions). Asked for each run of a download.
     */
    fun insecureTrust(): CertTrust? = null

    /** Hold (true) or release (false) whatever keeps the device awake and the process alive. Idempotent. */
    fun keepAwake(on: Boolean)

    /** Free bytes on the volume of [folder], or null when unknown. */
    fun usableSpace(folder: String): Long?

    /** Marks [file] as downloaded from the internet; false when the file system can't carry the mark. */
    fun markDownloadedFile(file: File, source: DownloadSource): Boolean

    /**
     * A download finished: sounds, virus scan, custom command, as the app is configured. [path] is the
     * finished file, or for a [DownloadType.Batch] (many files, no single one) the batch folder.
     */
    fun downloadFinished(path: String, type: DownloadType)

    /** A download failed (not stopped by the user). */
    fun downloadFailed(id: Long)

    /** Nothing is running or queued any more. */
    fun allDownloadsDone()

    /** [id] and its metadata were deleted: drop anything the app keeps for it (a schedule entry). */
    fun downloadDeleted(id: Long)
}

/**
 * What the download lifecycle needs from the app around it. The desktop implements it over
 * `AppContext`; Android over its own services. Properties are read on every use, so an app may
 * swap what is behind them (tests do).
 */
interface EngineHost {
    val config: EngineConfig
    val ui: UiSink
    val hooks: PlatformHooks
    val taskInfoDB: TaskInfoDB

    /**
     * Resolves target folders to the place bytes are written: plain paths on the desktop; on Android
     * paths or Storage Access Framework documents.
     */
    val storage: Storage get() = LocalStorage

    /**
     * The folder under which a new HLS/DASH download keeps its segments, for a download into
     * [targetFolder]. Recorded in the task info, so a resume finds them after the setting changes.
     * Android may pick the target's own volume; the desktop always uses the configured temp folder.
     */
    fun streamingTempFolder(targetFolder: String): String = config.tempFolder
}

/**
 * Service locator for the manager module, the same pattern as `CaptureContext`. Each app sets [host]
 * at startup, before the first download is created.
 */
object EngineContext {
    lateinit var host: EngineHost

    val config: EngineConfig get() = host.config
    val ui: UiSink get() = host.ui
    val hooks: PlatformHooks get() = host.hooks
    val taskInfoDB: TaskInfoDB get() = host.taskInfoDB
    val storage: Storage get() = host.storage
}
