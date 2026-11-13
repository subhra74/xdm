// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager

import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.DownloadType
import xdm.core.util.MovePhase
import xdm.core.util.StableId

enum class RecordStatus {
    DOWNLOADING,
    FINISHED,
    READY,

    /**
     * Stopped by an error rather than by the user. Behaves like [PAUSED] (resumable, kept in the paused
     * list) and only differs in how it is shown.
     */
    ERROR,
    PAUSED,
    ASSEMBLING,

    /**
     * Change folder is moving a stopped download's part file to another folder; [DbRecord.movePhase]
     * says which step. A same-volume move only renames, so it is brief; a cross-volume one copies.
     * Cancellable until [MovePhase.FINALIZING]: the part file stays where it was.
     */
    MOVING;

    /** Idle and resumable: these records are saved in the paused list. */
    val isStopped: Boolean get() = this == PAUSED || this == ERROR
}

data class DbRecord(
    var id: Long,
    var size: Long,
    var downloaded: Long,
    var progress: Int,
    var date: Long,
    var fileName: String,
    var eta: Long,
    var speed: Float,
    var selected: Boolean,
    var status: RecordStatus,
    val downloadType: DownloadType,
    /** The move step while [status] is [RecordStatus.MOVING]; meaningless otherwise. Not persisted. */
    var movePhase: MovePhase? = null,
    /**
     * Batch only: files that could not be downloaded. A batch with failures is still FINISHED; this
     * only changes its icon. Not persisted in the record files: read back from the batch state on load.
     */
    var failedFiles: Int = 0,
    /**
     * The category the download was saved with, for the sidebar filter; null is None. An id whose
     * category was deleted also reads as None. Persisted.
     */
    val categoryId: String? = null,
    /**
     * While [status] is [RecordStatus.DOWNLOADING]: how far (percent) the part file has been filled on a disk
     * without holes (FAT32, exFAT) before the download splits, or -1 when it isn't being filled. Not persisted.
     */
    var allocating: Int = -1,
    /**
     * Torrent only: a FINISHED torrent that is seeding right now (status stays FINISHED, so every finished-only
     * action keeps working). Not persisted: seeding never resumes by itself after a restart.
     */
    var seeding: Boolean = false,
    /** Torrent only: the latest torrent figures while it downloads or seeds; null otherwise. Not persisted. */
    var torrent: DownloadStatusInfo.TorrentProgress? = null,
)

data class QueueItem(val id: Long, val resume: Boolean)

/** File counts of a running batch, shown in its progress window. */
data class BatchFileCounts(val done: Int, val failed: Int, val total: Int)

/** Where a finished download came from: the URL fetched and the page that led to it. */
data class DownloadSource(val url: String?, val referrer: String?)

/** What the app does when a download finishes. */
enum class DownloadCompleteNotification(override val id: Int) : StableId {
    /** The app's full download-complete dialog (desktop) or a heads-up notification (Android). */
    DIALOG(1),

    /** A quiet system notification; clicking it opens the folder. */
    NOTIFICATION(2),

    /** Nothing at all. */
    NONE(3),
}
