package xdm.core.downloaders

import xdm.core.util.AtomicIO
import xdm.core.util.Logger

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
}