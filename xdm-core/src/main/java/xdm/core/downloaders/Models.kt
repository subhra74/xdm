// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders

import xdm.core.downloaders.web.SegmentProgress
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.network.http.HeaderMap
import xdm.core.util.StableId
import java.io.File

enum class DownloadType(override val id: Int) : StableId {
    Http(1), Hls(2), Dash(3), Torrent(4),

    /** Many links saved as separate files in one folder, shown as a single download (see docs/batch-downloader.md). */
    Batch(5),
}

enum class DownloadError {
    NetworkError,
    InvalidResponse,
    SessionExpired,
    /**
     * Part of the file was downloaded, then the server started refusing the link (403, 410, or a 401
     * that no user name and password can answer): its signature, token or session ran out. A fresh
     * link (Refresh link) resumes from where it stopped.
     */
    LinkExpired,
    DiskSpaceError,
    InternalError,
    ResumeNotSupported,
    MuxError,
    /** The server's TLS certificate or hostname could not be verified. */
    TlsError,
    /** An encrypted segment could not be decrypted (missing or wrong key, corrupt data). */
    DecryptionError,
    /** The finished file could not be written or moved to the destination folder. */
    OutputWriteError,
    /** The user stopped a move that was copying across volumes. The part file is still where it was. */
    Cancelled,
}

enum class PauseEvent {
    PausedByUser,
    PausedBySystem,
}

interface DownloaderTask {
    fun start()
    fun stop()
    fun resume()
    fun deleteTemp()
}

data class HttpDownloadTaskInfo(
    var id: Long,
    var url: String,
    var fileName: String,
    var respectFileName: Boolean,
    var cookie: String?,
    var headers: HeaderMap?,
    var origin: String?,
    /** The category chosen when the download was created; null is None. Opaque to xdm-core. */
    var categoryId: String?,
    /**
     * The folder the download is written to and published in. Resolved once when the download is
     * created and never recomputed; only Change folder rewrites it, after copying the part file there.
     * The part file's path is derived from it and the id ([partFileName]), so it is the only location record.
     */
    var targetFolder: String,
    var maxPiece: Int,
    var authInfo: AuthInfo?,
    val knownFileSize: Long?,
    /**
     * The response ETag the browser saw, used only to register the download for the duplicate
     * check when it is added. Deliberately not persisted by TaskInfoDB, so it is null on reload.
     */
    val etag: String? = null,
    /**
     * A captured WebVTT/TTML subtitle the user wants as SubRip: converted when it is published, and
     * saved under the same name with `.srt`. A file that turns out not to be convertible is kept as is.
     */
    var convertToSrt: Boolean = false,
    /** Set when an external extractor (yt-dlp) resolved the link; it can resolve it again when it expires. */
    var extractor: ExtractorSource? = null,
    /**
     * The link the browser was redirected from to [url] (a site's download link in front of a signed,
     * short-lived file URL). Asked again for a fresh [url] when this one expires; null when there was no redirect.
     */
    var sourceUrl: String? = null,
    /** The cookies of [sourceUrl]'s host, sent only to it. */
    var sourceCookie: String? = null,
)

/**
 * A download whose media links came from an external extractor run on a page URL, not from the browser
 * or a typed manifest. Kept so the links can be resolved again when they expire, and so the app can pick
 * the extractor's merger for it. Opaque to xdm-core apart from being saved with the task info.
 */
data class ExtractorSource(
    /** Which extractor: `yt-dlp`. */
    val extractor: String,
    /** The page the user entered. */
    val pageUrl: String,
    /** The extractor's format ids, video first, that make up this download. */
    val formatIds: List<String>,
    /** The container the merged file is written as (`mp4`, `webm`, `mkv`, `m4a`); null leaves it to the merger. */
    val container: String? = null,
    /**
     * Each track's segments are consecutive byte ranges of one file, cut at arbitrary offsets, not
     * fragments that stand on their own: joined in order they are the file again.
     */
    val pieces: Boolean = false,
)

/**
 * A WebVTT subtitle track of a stream, saved next to the video as `<name>.<language>.srt`.
 * HLS gives a media playlist ([playlistUrl]), fetched when the download starts; DASH gives the
 * [segments] directly (usually one whole `.vtt` file).
 */
data class SubtitleTrack(
    val name: String,
    val language: String,
    val playlistUrl: String? = null,
    val segments: List<DashSegment> = emptyList(),
    val isDefault: Boolean = false,
    val forced: Boolean = false,
)

/**
 * The sidecar subtitle's name for the video [videoFileName]: `movie.mp4` + `en` -> `movie.en.srt`.
 * Characters a file name can't hold are dropped from the language; a blank one gives `movie.srt`.
 */
fun subtitleFileName(videoFileName: String, language: String): String {
    val dot = videoFileName.lastIndexOf('.')
    val base = if (dot > 0) videoFileName.substring(0, dot) else videoFileName
    val lang = language.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    return if (lang.isEmpty()) "$base.srt" else "$base.$lang.srt"
}

/**
 * The name of a download's partial file: id-based, so it never clashes and does not depend on the final
 * name, and with no real extension, so players and indexers leave it alone. HTTP writes it directly;
 * streaming appends the container extension to the muxed output.
 */
fun partFileName(id: Long): String = ".${id.toULong().toString(16)}.xdm-part"

abstract class StreamingDownloadTaskInfo(
    open val id: Long,
    open var fileName: String,
    open var tempDir: String,
    open var respectFileName: Boolean,
    open var cookie: String?,
    open var headers: HeaderMap?,
    open var origin: String?,
    open var categoryId: String?,
    open var targetFolder: String,
    open var maxPiece: Int,
    open var authInfo: AuthInfo?,
)

data class HlsDownloadTaskInfo(
    override val id: Long,
    override var fileName: String,
    override var tempDir: String,
    override var respectFileName: Boolean,
    override var cookie: String?,
    override var headers: HeaderMap?,
    override var origin: String?,
    override var categoryId: String?,
    override var targetFolder: String,
    override var maxPiece: Int,
    override var authInfo: AuthInfo?,
    var url: String,
    var audioUrl: String?,
    var audioOnly: Boolean = false,
    var independent: Boolean,
    /** The subtitles to save beside the video: the tracker offers all, a download keeps the one picked. */
    var subtitles: List<SubtitleTrack> = emptyList(),
    /** As [HttpDownloadTaskInfo.extractor]. */
    var extractor: ExtractorSource? = null,
) : StreamingDownloadTaskInfo(
    id,
    fileName,
    tempDir,
    respectFileName,
    cookie,
    headers,
    origin,
    categoryId,
    targetFolder,
    maxPiece,
    authInfo
)

data class DashDownloadTaskInfo(
    override val id: Long,
    override var fileName: String,
    override var tempDir: String,
    override var respectFileName: Boolean,
    override var cookie: String?,
    override var headers: HeaderMap?,
    override var origin: String?,
    override var categoryId: String?,
    override var targetFolder: String,
    override var maxPiece: Int,
    override var authInfo: AuthInfo?,
    var videoSegments: List<DashSegment>,
    var audioSegments: List<DashSegment>,
    var url: String,
    val audioMime: String,
    val videoMime: String,
    /** As [HlsDownloadTaskInfo.subtitles]. */
    var subtitles: List<SubtitleTrack> = emptyList(),
    /** As [HttpDownloadTaskInfo.extractor]. */
    var extractor: ExtractorSource? = null,
) : StreamingDownloadTaskInfo(
    id,
    fileName,
    tempDir,
    respectFileName,
    cookie,
    headers,
    origin,
    categoryId,
    targetFolder,
    maxPiece,
    authInfo
)

/** One link of a [BatchDownloadTaskInfo]. */
data class BatchItem(
    val url: String,
    /** Unique within the batch; the server's Content-Disposition may still replace it unless [nameFromPage]. */
    val fileName: String,
    /** Index into [BatchDownloadTaskInfo.cookies], or -1 when the link is sent without cookies. */
    val cookieGroup: Int,
    /** Size the browser reported, if any. */
    val knownSize: Long?,
    /** The page named the file (`<a download="…">`), so the server's name must not replace it. */
    val nameFromPage: Boolean,
    /**
     * Set for a video played as two tracks (see docs/site-video-detection.md): [url] is then the
     * video-only track, this the audio-only one, and the item is both merged into [fileName].
     */
    val audioUrl: String? = null,
) {
    val merged: Boolean get() = audioUrl != null
}

/**
 * Many links downloaded as one entry into the folder `<folder>/<name>`. The name and folder are fixed
 * once the batch is created: files are written straight into that folder.
 */
data class BatchDownloadTaskInfo(
    val id: Long,
    var name: String,
    /** Parent of the batch folder. */
    var folder: String,
    /** Sent with every item (User-Agent, Referer). */
    var headers: HeaderMap?,
    /** The page the links came from. */
    var origin: String?,
    /** Files fetched at once; 0 means the global setting. */
    var maxPiece: Int,
    /** Distinct Cookie header values, referenced by [BatchItem.cookieGroup]. */
    val cookies: List<String>,
    val items: List<BatchItem>,
    /**
     * Where merged items' tracks are downloaded before they are merged into the batch folder, as
     * `<tempDir>/batch-<id>/`: the merge reads them as local files. Null when no item is merged.
     */
    var tempDir: String? = null,
) {
    val batchFolder: String get() = File(folder, name).absolutePath

    /** The folder holding merged items' tracks, or null when the batch has none. */
    val trackFolder: String? get() = tempDir?.let { File(it, "batch-$id").absolutePath }
}

/**
 * A BitTorrent download. The torrent's files are written by the native engine directly into [folder] (a single
 * file as `<folder>/<file>`, several as `<folder>/<name>/...`). The metadata and the engine's resume data live
 * next to this record in the data folder as `<id>.torrent` and `<id>.resume`.
 */
data class TorrentDownloadTaskInfo(
    val id: Long,
    /** 40 hex: the v1 info-hash, else the v2 hash truncated to 20 bytes. Fixed when the torrent is added. */
    val key: String,
    /** The torrent's name (its top folder or single file); the magnet's display name until the metadata arrives. */
    var name: String,
    /** The folder the torrent is saved in. */
    var folder: String,
    /** The magnet link it was added from, or null for a .torrent file. */
    var magnet: String?,
    var categoryId: String?,
    /** One priority per file of the torrent (0 skip, 1 low, 4 normal, 7 high); empty means all normal. */
    var filePriorities: ByteArray = ByteArray(0),
    var sequential: Boolean = false,
    /** The page the link came from, if any. */
    var origin: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is TorrentDownloadTaskInfo && id == other.id && key == other.key && name == other.name &&
            folder == other.folder && magnet == other.magnet && categoryId == other.categoryId &&
            filePriorities.contentEquals(other.filePriorities) && sequential == other.sequential &&
            origin == other.origin

    override fun hashCode(): Int = (id.hashCode() * 31 + key.hashCode()) * 31 + filePriorities.contentHashCode()
}

sealed interface DownloadStatusInfo {
    data class InitInfo(
        val id: Long,
        val url: String,
        val isRedirect: Boolean,
        val fileSize: Long?,
        val contentDisposition: String?,
        val contentType: String?,
        val videoExt: String? = null
    )

    data class ProgressInfo(
        var id: Long = 0,
        var progress: Int = 0,
        var speed: Float = 0.0f,
        var eta: Long = 0,
        var downloaded: Long = 0,
        var segments: Collection<SegmentProgress> = listOf(),
        /** Batch only: files finished, files failed and files in the batch. Zero for other types. */
        var filesDone: Int = 0,
        var failedFiles: Int = 0,
        var filesTotal: Int = 0,
        /** Torrent only, null for other types. */
        var torrent: TorrentProgress? = null,
    )

    /** The torrent part of a progress update (see [ProgressInfo.torrent]). */
    data class TorrentProgress(
        val phase: TorrentPhase,
        /** Selected files only. */
        val wanted: Long,
        val uploadSpeed: Long,
        val uploaded: Long,
        /** Uploaded over downloaded (all time). */
        val ratio: Float,
        val peers: Int,
        val seeds: Int,
        /** 0-1000 while checking files. */
        val checkPermille: Int,
        val seeding: Boolean,
    )

    data class AssembleInfo(
        val id: Long,
        val progress: Int,
    )

    data class FinalInfo(
        val id: Long,
        val fileSize: Long,
        val finalFileName: String,
        val finalOutputFolder: String,
        /** Batch only: files that could not be downloaded; the batch still finishes. */
        val failedFiles: Int = 0,
    )
}

enum class TorrentPhase { Metadata, Checking, Downloading, Finished, Seeding }

sealed interface CommitResult {
    data class Success(val fileName: String, val outputDir: String) : CommitResult
    data class Failed(val error: DownloadError) : CommitResult
}