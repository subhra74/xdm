// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

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

/**
 * Marks the tail section after the per-file records: merged items' track progress. States written
 * before merged items existed end without it, and older versions stop reading before it.
 */
private const val TRACKS_TAIL = 0x584D5654 // "XMVT"

private fun writeTrack(t: BatchFile, out: DataOutputStream) {
    out.writeLongString(t.status.get().name)
    out.writeLong(t.downloaded.get())
    out.writeLong(t.length.get())
    out.writeNullableLongString(t.error.get()?.name)
}

private fun readTrack(index: Int, r: DataInputStream) = BatchFile(
    index = index,
    status = AtomicReference(ChunkStatus.valueOf(r.readLongString())),
    downloaded = AtomicLong(r.readLong()),
    length = AtomicLong(r.readLong()),
    error = AtomicReference(r.readNullableLongString()?.let { runCatching { DownloadError.valueOf(it) }.getOrNull() }),
)

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
    val merged = context.files.filter { it.merged }
    if (merged.isNotEmpty()) {
        out.writeInt(TRACKS_TAIL)
        out.writeInt(merged.size)
        for (f in merged) {
            out.writeInt(f.index)
            writeTrack(f.video!!, out)
            writeTrack(f.audio!!, out)
        }
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
    if (r.available() >= 4 && r.readInt() == TRACKS_TAIL) {
        repeat(r.readInt()) {
            val index = r.readInt()
            val video = readTrack(index, r)
            val audio = readTrack(index, r)
            files.getOrNull(index)?.let { it.video = video; it.audio = audio }
        }
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
        ctx.files.forEach { f ->
            (listOf(f) + f.tracks).forEach { it.status.compareAndSet(ChunkStatus.Downloading, ChunkStatus.Ready) }
        }
    }
