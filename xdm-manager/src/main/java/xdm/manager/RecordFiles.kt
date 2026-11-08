// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager

import xdm.core.downloaders.DownloadType
import xdm.core.storage.StorageFile
import java.io.File

fun getFileFolder(ent: DbRecord): Pair<String?, String?>? {
    val folder: String?
    val fileName: String?
    when (ent.downloadType) {
        DownloadType.Http -> {
            val md = EngineContext.taskInfoDB.getHttpTask(ent.id) ?: return null
            folder = md.targetFolder
            fileName = md.fileName
        }

        DownloadType.Hls -> {
            val md = EngineContext.taskInfoDB.getHlsTask(ent.id) ?: return null
            folder = md.targetFolder
            fileName = md.fileName
        }

        DownloadType.Dash -> {
            val md = EngineContext.taskInfoDB.getDashTask(ent.id) ?: return null
            folder = md.targetFolder
            fileName = md.fileName
        }

        // The batch folder stands for the download: "open" opens it, "open folder" reveals it.
        DownloadType.Batch -> {
            val md = EngineContext.taskInfoDB.getBatchTask(ent.id) ?: return null
            folder = md.folder
            fileName = md.name
        }

        DownloadType.Torrent -> TODO()
    }

    return Pair(fileName, folder)
}

/**
 * Where a completed download's file (a batch's folder) is: its task info holds the final name and folder.
 * Resolved through [EngineContext.storage], so on Android it may be a document rather than a path.
 */
fun finishedFile(ent: DbRecord): StorageFile? {
    if (ent.downloadType == DownloadType.Torrent) return null
    val (fileName, folder) = getFileFolder(ent) ?: return null
    return if (fileName != null && folder != null) EngineContext.storage.folder(folder).file(fileName) else null
}

/** Whether a completed download's file (a batch's folder) is still on disk; false when it cannot be located. */
fun finishedFileExists(ent: DbRecord): Boolean = finishedFile(ent)?.let { it.folder.exists(it.name) } == true

/** Why a batch cannot use a folder, or [Ok]. */
enum class BatchFolderState {
    /** Missing (it will be created) or an empty folder (it will be reused). */
    Ok,

    /** A folder that already holds files. */
    NotEmpty,

    /** A file, not a folder. */
    NotAFolder,
}

fun batchFolderState(folder: File): BatchFolderState = when {
    !folder.exists() -> BatchFolderState.Ok
    !folder.isDirectory -> BatchFolderState.NotAFolder
    folder.list()?.isEmpty() == true -> BatchFolderState.Ok
    else -> BatchFolderState.NotEmpty
}

/** A batch may be saved into [folder]: it is missing or an empty folder. */
fun isUsableBatchFolder(folder: File) = batchFolderState(folder) == BatchFolderState.Ok
