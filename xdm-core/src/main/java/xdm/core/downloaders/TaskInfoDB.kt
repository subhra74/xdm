package xdm.core.downloaders

import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import java.io.DataInput
import java.io.DataOutput
import java.io.File
import java.net.URI

/** Serialises a DASH segment as its URL plus an optional `(offset, length)` byte range. */
private fun writeDashSegment(seg: DashSegment, w: DataOutput) {
    w.writeUTF(seg.url.toString())
    w.writeBoolean(seg.range != null)
    seg.range?.let { w.writeLong(it.first); w.writeLong(it.second) }
}

private fun readDashSegment(r: DataInput): DashSegment {
    val url = URI(r.readUTF())
    val range = if (r.readBoolean()) Pair(r.readLong(), r.readLong()) else null
    return DashSegment(url, range)
}

class TaskInfoDB(private val configDir: String) {
    fun getHttpTask(id: Long): HttpDownloadTaskInfo? {
        AtomicIO.readTransacted("task-$id.info", configDir) { r ->
            return HttpDownloadTaskInfo(
                id = r.readLong(),
                url = r.readUTF(),
                fileName = r.readUTF(),
                respectFileName = r.readBoolean(),
                cookie = null,
                headers = null,
                origin = null,
                autoCategorize = r.readBoolean(),
                defaultDownloadFolder = r.readUTF(),
                userSelectedDownloadFolder = null,
                maxPiece = r.readInt(),
                authInfo = null,
                knownFileSize = if (r.readBoolean()) {
                    r.readLong()
                } else null,
            )
        }
        return null
    }

    fun getHlsTask(id: Long): HlsDownloadTaskInfo? {
        AtomicIO.readTransacted("task-$id.info", configDir) { r ->
            return HlsDownloadTaskInfo(
                id = r.readLong(),
                url = r.readUTF(),
                audioUrl = if (r.readBoolean()) r.readUTF() else null,
                fileName = r.readUTF(),
                respectFileName = r.readBoolean(),
                cookie = null,
                headers = null,
                origin = null,
                autoCategorize = r.readBoolean(),
                defaultDownloadFolder = r.readUTF(),
                userSelectedDownloadFolder = null,
                maxPiece = r.readInt(),
                authInfo = null,
                audioOnly = r.readBoolean(),
                tempDir = r.readUTF(),
                independent = r.readBoolean(),
            )
        }
        return null
    }

    fun getDashTask(id: Long): DashDownloadTaskInfo? {
        AtomicIO.readTransacted("task-$id.info", configDir) { r ->
            return DashDownloadTaskInfo(
                id = r.readLong(),
                url = r.readUTF(),
                fileName = r.readUTF(),
                respectFileName = r.readBoolean(),
                cookie = null,
                headers = null,
                origin = null,
                autoCategorize = r.readBoolean(),
                defaultDownloadFolder = r.readUTF(),
                userSelectedDownloadFolder = null,
                maxPiece = r.readInt(),
                authInfo = null,
                tempDir = r.readUTF(),
                audioSegments = (0..<r.readInt()).map { readDashSegment(r) }.toList(),
                videoSegments = (0..<r.readInt()).map { readDashSegment(r) }.toList(),
                audioMime = r.readUTF(),
                videoMime = r.readUTF(),
            )
        }
        return null
    }

    fun saveHttpTask(task: HttpDownloadTaskInfo) {
        AtomicIO.writeTransacted("task-${task.id}.info", configDir) { w ->
            w.writeLong(task.id)
            w.writeUTF(task.url)
            w.writeUTF(task.fileName)
            w.writeBoolean(task.respectFileName)
            w.writeBoolean(task.autoCategorize)
            w.writeUTF(task.defaultDownloadFolder)
            w.writeInt(task.maxPiece)
            w.writeBoolean(task.knownFileSize != null)
            task.knownFileSize?.let { w.writeLong(it) }
        }
    }

    fun saveHlsTask(task: HlsDownloadTaskInfo) {
        AtomicIO.writeTransacted("task-${task.id}.info", configDir) { w ->
            w.writeLong(task.id)
            w.writeUTF(task.url)
            w.writeBoolean(task.audioUrl != null)
            task.audioUrl?.let { w.writeUTF(it) }
            w.writeUTF(task.fileName)
            w.writeBoolean(task.respectFileName)
            w.writeBoolean(task.autoCategorize)
            w.writeUTF(task.defaultDownloadFolder)
            w.writeInt(task.maxPiece)
            w.writeBoolean(task.audioOnly)
            w.writeUTF(task.tempDir)
            w.writeBoolean(task.independent)
        }
    }

    fun saveDashTask(task: DashDownloadTaskInfo) {
        AtomicIO.writeTransacted("task-${task.id}.info", configDir) { w ->
            w.writeLong(task.id)
            w.writeUTF(task.url)
            w.writeUTF(task.fileName)
            w.writeBoolean(task.respectFileName)
            w.writeBoolean(task.autoCategorize)
            w.writeUTF(task.defaultDownloadFolder)
            w.writeInt(task.maxPiece)
            w.writeUTF(task.tempDir)
            w.writeInt(task.audioSegments.size)
            for (seg in task.audioSegments) {
                writeDashSegment(seg, w)
            }
            w.writeInt(task.videoSegments.size)
            for (seg in task.videoSegments) {
                writeDashSegment(seg, w)
            }
            w.writeUTF(task.audioMime)
            w.writeUTF(task.videoMime)
        }
    }

    fun deleteRecord(id: Long) {
        File(configDir, "task-$id.info").delete()
        File(configDir, "task-$id.info.bak2").delete()
    }
}