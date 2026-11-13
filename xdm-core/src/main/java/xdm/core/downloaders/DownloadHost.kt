// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders

import xdm.core.storage.LocalStorage
import xdm.core.storage.Storage
import xdm.core.storage.StorageFile

interface FileProvider {
    /** If user provided output folder then use that or decide based on file type */
    fun getTempDir(id: Long, url: String, contentType: String?, contentDisposition: String?): String

    /**
     * Resolves target folders (`targetFolder`, a batch's folder) to the place the bytes are written:
     * the local file system on desktop, the Storage Access Framework on Android.
     */
    val storage: Storage get() = LocalStorage

    /**
     * Publishes the finished [part] file under its final name, normally a rename within [part]'s folder.
     * [CommitResult.Success.outputDir] is that folder's [xdm.core.storage.StorageFolder.location].
     * [container]: for a muxed output, the container written (an extension without the dot, e.g. `mkv`;
     * see [xdm.core.media.muxer.MuxContainer]), which the final name takes; null keeps the name.
     */
    fun commitOutputFile(id: Long, part: StorageFile, downloadType: DownloadType, container: String? = null): CommitResult

    /**
     * Publishes [folder] (a local folder of segments and playlists, built when the segments couldn't be
     * made into one file) into the download's folder under the download's name, its [entry] playlist
     * renamed to `<name>.m3u8`. [CommitResult.Success.fileName] is `<folder name>/<name>.m3u8`. Hosts that
     * can't publish a folder (yet) fail, and the segments are kept.
     */
    fun commitOutputFolder(id: Long, folder: String, entry: String, downloadType: DownloadType): CommitResult =
        CommitResult.Failed(DownloadError.OutputWriteError)

    /**
     * Publishes the local [file] beside an already published output [mainFileName] in [outputDir], as
     * `<main name>.<suffix>.<ext>` (the file's own extension): a track that couldn't be merged into it.
     * Returns the name given, or null when the host can't (the caller keeps the file).
     */
    fun commitSidecar(id: Long, file: String, outputDir: String, mainFileName: String, suffix: String): String? = null

    /**
     * Where a streaming download should write its assembled output ([ext] includes the dot): a partial
     * file inside the final destination folder, so [commitOutputFile] is a same-folder rename even
     * when the temp folder is on another drive. Stable for a download id. Null means "write it in the
     * task's temp folder".
     */
    fun outputFile(id: Long, downloadType: DownloadType, ext: String): StorageFile? = null
}

interface DownloadHost : FileProvider {
    fun onDownloadActivated(id: Long)
    fun onDownloadInit(data: DownloadStatusInfo.InitInfo, downloadType: DownloadType)
    fun onDownloadProgress(event: DownloadStatusInfo.ProgressInfo)
    fun onAssembleStart(id: Long)
    fun onAssembleProgress(event: DownloadStatusInfo.AssembleInfo)
    fun onDownloadSuccess(event: DownloadStatusInfo.FinalInfo)
    fun onDownloadFailed(id: Long, error: DownloadError)
    fun onDownloadPaused(id: Long, event: PauseEvent)

    /**
     * The part file on a disk without holes (FAT32, exFAT) is being filled before the download splits:
     * [percent] of it so far, then -1 once it is done. Sent only while the download runs; a pause or failure
     * ends it without a -1. Any thread.
     */
    fun onAllocateProgress(id: Long, percent: Int) {}
    val appDir: String
    val applySpeedLimit: Boolean
    val speedLimit: Int
}