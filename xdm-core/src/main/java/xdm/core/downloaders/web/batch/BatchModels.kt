package xdm.core.downloaders.web.batch

import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.http.ChunkStatus
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Progress of one item of a batch, by its index in [xdm.core.downloaders.BatchDownloadTaskInfo.items]. */
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
)

/**
 * Resume state of a batch, saved to `<id>.state`. Holds no URLs or cookies: those are in the write-once
 * task info, so the state stays small enough to save often.
 */
class BatchTaskContext(
    val id: Long,
    /** Absolute folder the files go into, recorded when the batch first starts and never recomputed. */
    val batchFolder: String,
    val files: List<BatchFile>,
) {
    val finishedCount: Int get() = files.count { it.status.get() == ChunkStatus.Finished }
    val failedCount: Int get() = files.count { it.status.get() == ChunkStatus.Failed }
}

/** The part file an item is written to until it is complete; the name depends only on the index. */
fun batchPartFile(batchFolder: String, index: Int) = File(batchFolder, ".$index.xdm-part")
