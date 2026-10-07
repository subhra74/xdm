package xdm.core.downloaders.web.batch

import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import xdm.core.util.readLongString
import xdm.core.util.readNullableLongString
import xdm.core.util.writeLongString
import xdm.core.util.writeNullableLongString
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private fun writeBatchContext(context: BatchTaskContext, out: DataOutputStream) {
    out.writeLong(context.id)
    out.writeLongString(context.batchFolder)
    out.writeInt(context.files.size)
    for (f in context.files) {
        out.writeInt(f.index)
        out.writeLongString(f.status.get().name)
        out.writeLong(f.downloaded.get())
        out.writeLong(f.length.get())
        out.writeNullableLongString(f.finalName)
        out.writeNullableLongString(f.error.get()?.name)
    }
}

private fun readBatchContext(r: DataInputStream): BatchTaskContext {
    val id = r.readLong()
    val folder = r.readLongString()
    val files = List(r.readInt()) {
        BatchFile(
            index = r.readInt(),
            status = AtomicReference(ChunkStatus.valueOf(r.readLongString())),
            downloaded = AtomicLong(r.readLong()),
            length = AtomicLong(r.readLong()),
            finalName = r.readNullableLongString(),
            error = AtomicReference(r.readNullableLongString()?.let { runCatching { DownloadError.valueOf(it) }.getOrNull() }),
        )
    }
    return BatchTaskContext(id, folder, files)
}

fun saveBatchState(context: BatchTaskContext, configDir: String) {
    AtomicIO.writeTransacted("${context.id}.state", configDir, ownerOnly = true) { fs ->
        writeBatchContext(context, fs)
    }.onFailure { Logger.error("XDM", "Error saving batch state ${context.id}", it) }
}

/** The saved state of batch [id]; files that were mid-download come back as ready. */
fun loadBatchState(id: Long, configDir: String): Result<BatchTaskContext> =
    AtomicIO.readTransacted("$id.state", configDir) { fs -> readBatchContext(fs) }.onSuccess { ctx ->
        ctx.files.forEach { it.status.compareAndSet(ChunkStatus.Downloading, ChunkStatus.Ready) }
    }
