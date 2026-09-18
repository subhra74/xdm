package xdm.core.downloaders

import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.network.http.HeaderMap
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import xdm.core.util.readLongString
import xdm.core.util.readNullableHeaders
import xdm.core.util.readNullableLongString
import xdm.core.util.writeLongString
import xdm.core.util.writeNullableHeaders
import xdm.core.util.writeNullableLongString
import java.io.DataInput
import java.io.DataOutput
import java.io.File
import java.net.URI

/** Serialises a DASH segment as its URL plus an optional `(offset, length)` byte range. */
private fun writeDashSegment(seg: DashSegment, w: DataOutput) {
    w.writeLongString(seg.url.toString())
    w.writeBoolean(seg.range != null)
    seg.range?.let { w.writeLong(it.first); w.writeLong(it.second) }
}

private fun readDashSegment(r: DataInput): DashSegment {
    val url = URI(r.readLongString())
    val range = if (r.readBoolean()) Pair(r.readLong(), r.readLong()) else null
    return DashSegment(url, range)
}

/**
 * Request context shared by every task type, written after the type-specific fields. Queued and
 * "download later" tasks are started from the `.info` file alone, so these must be persisted or
 * the download runs without its cookies / headers.
 */
private class RequestFields(
    val cookie: String?,
    val headers: HeaderMap?,
    val origin: String?,
    val userSelectedDownloadFolder: String?,
)

private fun writeRequestFields(
    w: DataOutput, cookie: String?, headers: HeaderMap?, origin: String?, userSelectedDownloadFolder: String?,
) {
    w.writeNullableLongString(cookie)
    w.writeNullableHeaders(headers)
    w.writeNullableLongString(origin)
    w.writeNullableLongString(userSelectedDownloadFolder)
}

private fun readRequestFields(r: DataInput) = RequestFields(
    cookie = r.readNullableLongString(),
    headers = r.readNullableHeaders(),
    origin = r.readNullableLongString(),
    userSelectedDownloadFolder = r.readNullableLongString(),
)

/**
 * Persists immutable task parameters to `task-<id>.info`. Every get*Task / save*Task pair must read
 * and write fields in the same order. Strings use length-prefixed UTF-8 (see BinaryIO.kt), so values
 * over 64 KB are fine.
 */
class TaskInfoDB(private val configDir: String) {
    fun getHttpTask(id: Long): HttpDownloadTaskInfo? {
        AtomicIO.readTransacted("task-$id.info", configDir) { r ->
            val taskId = r.readLong()
            val url = r.readLongString()
            val fileName = r.readLongString()
            val respectFileName = r.readBoolean()
            val autoCategorize = r.readBoolean()
            val defaultDownloadFolder = r.readLongString()
            val maxPiece = r.readInt()
            val knownFileSize = if (r.readBoolean()) r.readLong() else null
            val req = readRequestFields(r)
            return HttpDownloadTaskInfo(
                id = taskId,
                url = url,
                fileName = fileName,
                respectFileName = respectFileName,
                cookie = req.cookie,
                headers = req.headers,
                origin = req.origin,
                autoCategorize = autoCategorize,
                defaultDownloadFolder = defaultDownloadFolder,
                userSelectedDownloadFolder = req.userSelectedDownloadFolder,
                maxPiece = maxPiece,
                authInfo = null,
                knownFileSize = knownFileSize,
            )
        }
        return null
    }

    fun getHlsTask(id: Long): HlsDownloadTaskInfo? {
        AtomicIO.readTransacted("task-$id.info", configDir) { r ->
            val taskId = r.readLong()
            val url = r.readLongString()
            val audioUrl = r.readNullableLongString()
            val fileName = r.readLongString()
            val respectFileName = r.readBoolean()
            val autoCategorize = r.readBoolean()
            val defaultDownloadFolder = r.readLongString()
            val maxPiece = r.readInt()
            val audioOnly = r.readBoolean()
            val tempDir = r.readLongString()
            val independent = r.readBoolean()
            val req = readRequestFields(r)
            return HlsDownloadTaskInfo(
                id = taskId,
                url = url,
                audioUrl = audioUrl,
                fileName = fileName,
                respectFileName = respectFileName,
                cookie = req.cookie,
                headers = req.headers,
                origin = req.origin,
                autoCategorize = autoCategorize,
                defaultDownloadFolder = defaultDownloadFolder,
                userSelectedDownloadFolder = req.userSelectedDownloadFolder,
                maxPiece = maxPiece,
                authInfo = null,
                audioOnly = audioOnly,
                tempDir = tempDir,
                independent = independent,
            )
        }
        return null
    }

    fun getDashTask(id: Long): DashDownloadTaskInfo? {
        AtomicIO.readTransacted("task-$id.info", configDir) { r ->
            val taskId = r.readLong()
            val url = r.readLongString()
            val fileName = r.readLongString()
            val respectFileName = r.readBoolean()
            val autoCategorize = r.readBoolean()
            val defaultDownloadFolder = r.readLongString()
            val maxPiece = r.readInt()
            val tempDir = r.readLongString()
            val audioSegments = List(r.readInt()) { readDashSegment(r) }
            val videoSegments = List(r.readInt()) { readDashSegment(r) }
            val audioMime = r.readLongString()
            val videoMime = r.readLongString()
            val req = readRequestFields(r)
            return DashDownloadTaskInfo(
                id = taskId,
                url = url,
                fileName = fileName,
                respectFileName = respectFileName,
                cookie = req.cookie,
                headers = req.headers,
                origin = req.origin,
                autoCategorize = autoCategorize,
                defaultDownloadFolder = defaultDownloadFolder,
                userSelectedDownloadFolder = req.userSelectedDownloadFolder,
                maxPiece = maxPiece,
                authInfo = null,
                tempDir = tempDir,
                audioSegments = audioSegments,
                videoSegments = videoSegments,
                audioMime = audioMime,
                videoMime = videoMime,
            )
        }
        return null
    }

    fun saveHttpTask(task: HttpDownloadTaskInfo) {
        AtomicIO.writeTransacted("task-${task.id}.info", configDir, ownerOnly = true) { w ->
            w.writeLong(task.id)
            w.writeLongString(task.url)
            w.writeLongString(task.fileName)
            w.writeBoolean(task.respectFileName)
            w.writeBoolean(task.autoCategorize)
            w.writeLongString(task.defaultDownloadFolder)
            w.writeInt(task.maxPiece)
            w.writeBoolean(task.knownFileSize != null)
            task.knownFileSize?.let { w.writeLong(it) }
            writeRequestFields(w, task.cookie, task.headers, task.origin, task.userSelectedDownloadFolder)
        }.onFailure { Logger.error("XDM", "Error saving task info ${task.id}", it) }
    }

    fun saveHlsTask(task: HlsDownloadTaskInfo) {
        AtomicIO.writeTransacted("task-${task.id}.info", configDir, ownerOnly = true) { w ->
            w.writeLong(task.id)
            w.writeLongString(task.url)
            w.writeNullableLongString(task.audioUrl)
            w.writeLongString(task.fileName)
            w.writeBoolean(task.respectFileName)
            w.writeBoolean(task.autoCategorize)
            w.writeLongString(task.defaultDownloadFolder)
            w.writeInt(task.maxPiece)
            w.writeBoolean(task.audioOnly)
            w.writeLongString(task.tempDir)
            w.writeBoolean(task.independent)
            writeRequestFields(w, task.cookie, task.headers, task.origin, task.userSelectedDownloadFolder)
        }.onFailure { Logger.error("XDM", "Error saving task info ${task.id}", it) }
    }

    fun saveDashTask(task: DashDownloadTaskInfo) {
        AtomicIO.writeTransacted("task-${task.id}.info", configDir, ownerOnly = true) { w ->
            w.writeLong(task.id)
            w.writeLongString(task.url)
            w.writeLongString(task.fileName)
            w.writeBoolean(task.respectFileName)
            w.writeBoolean(task.autoCategorize)
            w.writeLongString(task.defaultDownloadFolder)
            w.writeInt(task.maxPiece)
            w.writeLongString(task.tempDir)
            w.writeInt(task.audioSegments.size)
            for (seg in task.audioSegments) {
                writeDashSegment(seg, w)
            }
            w.writeInt(task.videoSegments.size)
            for (seg in task.videoSegments) {
                writeDashSegment(seg, w)
            }
            w.writeLongString(task.audioMime)
            w.writeLongString(task.videoMime)
            writeRequestFields(w, task.cookie, task.headers, task.origin, task.userSelectedDownloadFolder)
        }.onFailure { Logger.error("XDM", "Error saving task info ${task.id}", it) }
    }

    fun deleteRecord(id: Long) {
        File(configDir, "task-$id.info").delete()
        File(configDir, "task-$id.info.bak1").delete()
        File(configDir, "task-$id.info.bak2").delete()
    }
}