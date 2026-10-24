// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.downloaders.web.batch

import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.http.ChunkStatus
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Progress of one item of a batch, by its index in [xdm.core.downloaders.BatchDownloadTaskInfo.items].
 *
 * A merged item ([xdm.core.downloaders.BatchItem.audioUrl]) also has [video] and [audio]: the two tracks
 * it is downloaded as, each tracked like an item of its own. The item's own [status] is then the merge's:
 * Finished only once both tracks are merged into the batch folder.
 */
class BatchFile(
    val index: Int,
    val downloaded: AtomicLong = AtomicLong(0),
    /** Full size of the file; -1 until the server reports it. */
    val length: AtomicLong = AtomicLong(-1),
    val status: AtomicReference<ChunkStatus> = AtomicReference(ChunkStatus.Ready),
    val error: AtomicReference<DownloadError?> = AtomicReference(null),
    /**
     * The name the file is (or will be) saved under, once known: set from the first response and, if
     * that name was taken when the file finished, changed to the unique name it was renamed to.
     */
    @Volatile var finalName: String? = null,
    /** A merged item's video track; null for a plain item. Set before the task starts, never after. */
    var video: BatchFile? = null,
    /** A merged item's audio track; null for a plain item. */
    var audio: BatchFile? = null,
) {
    /** A merged item whose tracks are being merged right now. Not saved: a restart merges again. */
    @Volatile
    var merging: Boolean = false

    val merged: Boolean get() = video != null

    /** The tracks this item downloads: itself for a plain item, video and audio for a merged one. */
    val tracks: List<BatchFile> get() = if (merged) listOfNotNull(video, audio) else listOf(this)

    /** Bytes downloaded for the item, across its tracks. */
    val bytes: Long get() = tracks.sumOf { it.downloaded.get() }

    /** Full size across the tracks, or -1 while any is unknown. */
    val size: Long
        get() = tracks.map { it.length.get() }.let { lens -> if (lens.any { it < 0 }) -1 else lens.sum() }
}

/**
 * Resume state of a batch, saved to `<id>.state`. Holds no URLs or cookies: those are in the write-once
 * task info, so the state stays small enough to save often.
 */
class BatchTaskContext(
    val id: Long,
    /**
     * The folder the files go into, as a [xdm.core.storage.StorageFolder.location] (an absolute path on
     * desktop). Recorded when the batch first starts and never recomputed.
     */
    val batchFolder: String,
    val files: List<BatchFile>,
) {
    val finishedCount: Int get() = files.count { it.status.get() == ChunkStatus.Finished }
    val failedCount: Int get() = files.count { it.status.get() == ChunkStatus.Failed }
    val skippedCount: Int get() = files.count { it.status.get() == ChunkStatus.Skipped }
}

/** Finished, or skipped for having no content: either way never fetched again. */
val BatchFile.settled: Boolean
    get() = status.get().let { it == ChunkStatus.Finished || it == ChunkStatus.Skipped }

/** The name of the part file an item is written to until it is complete; it depends only on the index. */
fun batchPartName(index: Int) = ".$index.xdm-part"

/** The part file of item [index] in a local [batchFolder]. */
fun batchPartFile(batchFolder: String, index: Int) = File(batchFolder, batchPartName(index))
