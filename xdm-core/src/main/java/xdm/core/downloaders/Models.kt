package xdm.core.downloaders

import xdm.core.downloaders.web.SegmentProgress
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.network.http.HeaderMap
import java.io.File

enum class DownloadType {
    Http, Hls, Dash, Torrent,

    /** Many links saved as separate files in one folder, shown as a single download (see docs/batch-downloader.md). */
    Batch,
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
    /**
     * The user stopped a publish that was copying across volumes. The downloaded bytes are still in
     * the temp folder, so resuming republishes without downloading anything again.
     */
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
    var autoCategorize: Boolean,
    var defaultDownloadFolder: String,
    var userSelectedDownloadFolder: String?,
    var maxPiece: Int,
    var authInfo: AuthInfo?,
    val knownFileSize: Long?,
    /**
     * The response ETag the browser saw, used only to register the download for the duplicate
     * check when it is added. Deliberately not persisted by TaskInfoDB, so it is null on reload.
     */
    val etag: String? = null,
)

abstract class StreamingDownloadTaskInfo(
    open val id: Long,
    open var fileName: String,
    open var tempDir: String,
    open var respectFileName: Boolean,
    open var cookie: String?,
    open var headers: HeaderMap?,
    open var origin: String?,
    open var autoCategorize: Boolean,
    open var defaultDownloadFolder: String,
    open var userSelectedDownloadFolder: String?,
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
    override var autoCategorize: Boolean,
    override var defaultDownloadFolder: String,
    override var userSelectedDownloadFolder: String?,
    override var maxPiece: Int,
    override var authInfo: AuthInfo?,
    var url: String,
    var audioUrl: String?,
    var audioOnly: Boolean = false,
    var independent: Boolean,
) : StreamingDownloadTaskInfo(
    id,
    fileName,
    tempDir,
    respectFileName,
    cookie,
    headers,
    origin,
    autoCategorize,
    defaultDownloadFolder,
    userSelectedDownloadFolder,
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
    override var autoCategorize: Boolean,
    override var defaultDownloadFolder: String,
    override var userSelectedDownloadFolder: String?,
    override var maxPiece: Int,
    override var authInfo: AuthInfo?,
    var videoSegments: List<DashSegment>,
    var audioSegments: List<DashSegment>,
    var url: String,
    val audioMime: String,
    val videoMime: String,
) : StreamingDownloadTaskInfo(
    id,
    fileName,
    tempDir,
    respectFileName,
    cookie,
    headers,
    origin,
    autoCategorize,
    defaultDownloadFolder,
    userSelectedDownloadFolder,
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
)

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
) {
    val batchFolder: String get() = File(folder, name).absolutePath
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

sealed interface CommitResult {
    data class Success(val fileName: String, val outputDir: String) : CommitResult
    data class Failed(val error: DownloadError) : CommitResult
}