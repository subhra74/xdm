package xdm.app

import xdm.app.utils.captureDestination
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.SubtitleTrack
import xdm.core.util.FileUtils
import java.util.Locale

/**
 * What a captured video starts with: the preselection the video download dialog shows, and the same
 * choices made without it when "Start download immediately" is on.
 */
object VideoDownloadDefaults {

    /**
     * The subtitle track preselected for a stream: the system language (a full track before a forced
     * one), else the track the manifest marks default, else none.
     */
    fun defaultSubtitle(tracks: List<SubtitleTrack>, language: String = Locale.getDefault().language): SubtitleTrack? =
        tracks.sortedBy { it.forced }.firstOrNull { sameLanguage(it.language, language) }
            ?: tracks.firstOrNull { it.isDefault && !it.forced }

    /**
     * [name] with its extension swapped between a subtitle's own ([ext]) and `.srt`, toward `.srt` when
     * [toSrt]. A name ending in some other extension is returned as it is.
     */
    fun srtName(name: String, ext: String, toSrt: Boolean): String {
        val (from, to) = if (toSrt) ext to "srt" else "srt" to ext
        return if (name.endsWith(".$from", ignoreCase = true)) name.dropLast(from.length) + to else name
    }

    /**
     * Starts the captured video [videoId] as the dialog would with nothing changed: the sanitized name,
     * the category and folder the capture rules pick, the configured segment count, the preselected
     * subtitle track and the saved "Save as SRT" choice. Any thread. Returns false, starting nothing,
     * when the name is unusable or the tracker no longer has the video; the caller then shows the dialog.
     */
    fun startWithoutDialog(videoId: Long, fileName: String): Boolean {
        val config = AppContext.config
        val tracker = AppContext.videoTracker
        val http = tracker.getHttpVideo(videoId)
        val hls = tracker.getHlsVideo(videoId)
        val dash = tracker.getDashVideo(videoId)
        val subtitle = tracker.getSubtitle(videoId)
        var name = FileUtils.sanitizeFileName(fileName)?.takeIf { it.isNotBlank() } ?: return false
        val convertToSrt = subtitle?.format?.convertible == true && config.saveSubtitlesAsSrt
        if (subtitle != null && convertToSrt) name = srtName(name, subtitle.format.ext, toSrt = true)
        val url = http?.url ?: subtitle?.task?.url ?: hls?.url ?: dash?.url
        val origin = http?.origin ?: subtitle?.task?.origin ?: hls?.origin ?: dash?.origin
        val (categoryId, folder) = captureDestination(name, url, origin)
        val tracks = when {
            hls != null -> hls.subtitles
            dash != null -> dash.subtitles
            else -> emptyList()
        }
        return AppContext.downloader.addVideoDownload(
            videoId, name, categoryId, folder, config.maxSegments, defaultSubtitle(tracks), convertToSrt,
        ) != null
    }

    private fun sameLanguage(tag: String, language: String): Boolean =
        tag.isNotBlank() && Locale.forLanguageTag(tag).language.equals(language, ignoreCase = true)
}
