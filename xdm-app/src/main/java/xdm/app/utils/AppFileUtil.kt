package xdm.app.utils

import xdm.app.AppContext.taskInfoDB
import xdm.app.DbRecord
import xdm.core.downloaders.DownloadType
import java.util.*

fun getFileFolder(ent: DbRecord): Pair<String?, String?>? {
    val folder: String?
    val fileName: String?
    when (ent.downloadType) {
        DownloadType.Http -> {
            val md = taskInfoDB.getHttpTask(ent.id) ?: return null
            folder =
                Optional.ofNullable(md.userSelectedDownloadFolder)
                    .orElse(md.defaultDownloadFolder)
            fileName = md.fileName
        }

        DownloadType.Hls -> {
            val md = taskInfoDB.getHlsTask(ent.id) ?: return null
            folder =
                Optional.ofNullable(md.userSelectedDownloadFolder)
                    .orElse(md.defaultDownloadFolder)
            fileName = md.fileName
        }

        DownloadType.Dash -> {
            val md = taskInfoDB.getDashTask(ent.id) ?: return null
            folder =
                Optional.ofNullable(md.userSelectedDownloadFolder)
                    .orElse(md.defaultDownloadFolder)
            fileName = md.fileName
        }

        DownloadType.Torrent -> TODO()
    }

    return Pair(fileName, folder)
}