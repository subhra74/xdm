package xdm.app.ytdlp

import xdm.app.AppContext
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.ExtractorSource
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.util.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gives a Video download fresh links when the old ones stopped working. The links yt-dlp returns
 * expire (often after a few hours, and some are tied to the IP address): a download paused overnight,
 * or one that runs long, then fails with an expired link. yt-dlp is run again on the page for the same
 * formats, the new links replace the old ones, and the download resumes where it stopped.
 *
 * Done once per download every [RETRY_AFTER_MS], so a page that keeps failing ends as a normal error.
 */
object YtDlpRefresh {
    private const val RETRY_AFTER_MS = 10 * 60 * 1000L
    private val lastTried = ConcurrentHashMap<Long, Long>()

    private val expiredErrors = setOf(DownloadError.LinkExpired, DownloadError.InvalidResponse, DownloadError.SessionExpired)

    /**
     * Called when download [id] failed with [error]. Returns true when a refresh was started: the
     * caller then shows nothing, and [onGiveUp] gets the original error if the refresh fails.
     */
    fun tryHandle(id: Long, error: DownloadError, onGiveUp: (DownloadError) -> Unit): Boolean {
        if (error !in expiredErrors) return false
        val source = sourceOf(id) ?: return false
        val now = System.currentTimeMillis()
        val previous = lastTried[id]
        if (previous != null && now - previous < RETRY_AFTER_MS) return false
        lastTried[id] = now
        Thread({
            val ok = runCatching { refresh(id, source) }
                .onFailure { Logger.error("ytdlp", "Could not refresh the links of download $id", it) }
                .getOrDefault(false)
            if (ok) {
                Logger.info("ytdlp", "Download $id: fresh links, resuming")
                AppContext.downloader.resumeDownload(id)
            } else {
                onGiveUp(error)
            }
        }, "yt-dlp-refresh").apply { isDaemon = true }.start()
        return true
    }

    private fun sourceOf(id: Long): ExtractorSource? {
        val db = AppContext.taskInfoDB
        val source = db.getDashTask(id)?.extractor ?: db.getHttpTask(id)?.extractor ?: return null
        return source.takeIf { it.extractor == YtDlp.EXTRACTOR_ID && it.pageUrl.isNotBlank() }
    }

    private fun refresh(id: Long, source: ExtractorSource): Boolean {
        val info = YtDlp.resolve(source.pageUrl, AtomicBoolean())
        val byId = info.formats.associateBy { it.id }
        val formats = source.formatIds.map { byId[it] ?: return false.also { Logger.info("ytdlp", "Format $it is gone") } }
        val db = AppContext.taskInfoDB
        val headers = YtDlpTasks.headerMap(formats[0].headers)
        val cookie = formats.mapNotNull { it.cookieHeader }.distinct().joinToString("; ").ifEmpty { null }

        db.getHttpTask(id)?.let { task ->
            val f = formats[0]
            if (task.knownFileSize != null && f.fileSize != null && task.knownFileSize != f.fileSize) return false
            AppContext.downloader.updateDownloadLink(id, f.url ?: return false, headers, cookie)
            return true
        }

        val task = db.getDashTask(id) ?: return false
        val video = formats.firstOrNull { it.hasVideo }.takeIf { task.videoSegments.isNotEmpty() }
        val audio = formats.lastOrNull { !it.hasVideo || task.videoSegments.isEmpty() }.takeIf { task.audioSegments.isNotEmpty() }
        // Pieces already downloaded stay valid only if the file is byte for byte the same size.
        fun sameSize(f: YtFormat?, segments: List<DashSegment>): Boolean {
            if (f == null || segments.isEmpty()) return true
            val known = segments.last().range?.let { it.first + it.second } ?: return f.fileSize == null
            return f.fileSize == null || f.fileSize == known
        }
        if (!sameSize(video, task.videoSegments) || !sameSize(audio, task.audioSegments)) {
            Logger.info("ytdlp", "Download $id: the file changed size; the links cannot be swapped")
            return false
        }
        // The engine may still be writing its state for a moment after reporting the failure.
        repeat(20) {
            Thread.sleep(if (it == 0) 1000 else 500)
            if (AppContext.downloader.updateTrackLinks(id, video?.url, audio?.url, headers, cookie)) return true
        }
        return false
    }
}
