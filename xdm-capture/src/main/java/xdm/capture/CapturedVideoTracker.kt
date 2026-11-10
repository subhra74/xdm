// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.StreamingDownloadTaskInfo
import xdm.core.util.*

interface ICapturedVideoTracker {
    fun addVideoDownload(videoId: Long)
    fun addVideoHls(items: List<Pair<HlsDownloadTaskInfo, StreamingVideoDisplayInfo>>)
    fun addVideoDash(items: List<Pair<DashDownloadTaskInfo, StreamingVideoDisplayInfo>>)
    fun addVideoHttp(items: List<Pair<HttpDownloadTaskInfo, StreamingVideoDisplayInfo>>)
    fun updateMediaTitle(tabUrl: String, tabTitle: String)
    fun getHttpVideo(videoId: Long): HttpDownloadTaskInfo?
    fun getHlsVideo(videoId: Long): HlsDownloadTaskInfo?
    fun getDashVideo(videoId: Long): DashDownloadTaskInfo?

    /** Adds a captured subtitle file; dropped when its tab already has [MAX_SUBTITLES_PER_TAB]. */
    fun addSubtitle(item: CapturedSubtitle) {}

    fun getSubtitle(videoId: Long): CapturedSubtitle? = null

    /** What was shown for a captured HLS/DASH/HTTP video; null for a Restart or an unknown id. */
    fun getDisplayInfo(videoId: Long): StreamingVideoDisplayInfo?

    /**
     * Runs [update] under the tracker's lock on the display info of each of [videoIds] still
     * captured, then tells the UI. False when none of them is left (the tab was cleared).
     */
    fun updateDisplay(videoIds: Collection<Long>, update: (Long, StreamingVideoDisplayInfo) -> Unit): Boolean

    /**
     * Registers a copy of a saved HLS/DASH download for Restart, so the video dialog can take it like a
     * capture. Kept apart from the captured lists: it is not offered in the browser and never pushes
     * out a capture. Returns its video id.
     */
    fun addRestart(task: StreamingDownloadTaskInfo): Long
    fun clear()

    /** Drops everything captured for one browser tab (it reloaded, or navigated elsewhere). */
    fun clearTab(tabId: String)
    val videoList: List<DetectedVideoInfo>
}

class CapturedVideoTracker : ICapturedVideoTracker {
    /** What the UI needs about one detected video, read out under the lock. */
    private data class VideoSummary(val fileName: String, val size: Long, val contentType: String?)

    override fun addVideoDownload(videoId: Long) {
        // The maps are guarded by this instance; look the video up under the lock, then call the UI
        // outside it. (This ran unsynchronized before.)
        val summary = synchronized(this) {
            //TODO: Check for link refresh
            httpVideoList[videoId]?.let { (source, _) ->
                VideoSummary(
                    fileName = source.fileName,
                    size = getContentLength(source.headers) ?: (source.knownFileSize ?: -1),
                    contentType = getHeader("Content-Type", source.headers),
                )
            }
                ?: hlsVideoList[videoId]?.let { (s, _) -> VideoSummary(s.fileName, -1, "application/x-mpegURL") }
                ?: dashVideoList[videoId]?.let { (s, _) -> VideoSummary(s.fileName, -1, "application/dash+xml") }
                ?: restarts[videoId]?.let { s -> VideoSummary(s.fileName, -1, if (s is DashDownloadTaskInfo) "application/dash+xml" else "application/x-mpegURL") }
                ?: subtitleList[videoId]?.let { s -> VideoSummary(s.task.fileName, s.display.size ?: -1, s.format.mimeType) }
        } ?: return

        CaptureContext.host.offerVideo(videoId, summary.fileName, summary.size, summary.contentType)
    }


    override fun addVideoHls(items: List<Pair<HlsDownloadTaskInfo, StreamingVideoDisplayInfo>>) {
        synchronized(this) {
            for (item in items) {
                putLatest(hlsVideoList, item.first.id, item) { "${it.url}|${it.audioUrl}" }
            }
        }
        CaptureContext.host.videoListChanged()
    }

    override fun addVideoDash(items: List<Pair<DashDownloadTaskInfo, StreamingVideoDisplayInfo>>) {
        synchronized(this) {
            for (item in items) {
                putLatest(dashVideoList, item.first.id, item) {
                    "${it.videoSegments.firstOrNull() ?: it.url}|${it.audioSegments.firstOrNull()}"
                }
            }
        }
        CaptureContext.host.videoListChanged()
    }

    override fun addVideoHttp(items: List<Pair<HttpDownloadTaskInfo, StreamingVideoDisplayInfo>>) {
        synchronized(this) {
            for (item in items) {
                putLatest(httpVideoList, item.first.id, item) { it.url }
            }
        }
        CaptureContext.host.videoListChanged()
    }

    override fun addSubtitle(item: CapturedSubtitle) {
        val added = synchronized(this) {
            val tabId = item.display.tabId
            // The newest capture of a URL in a tab replaces the older one, as for videos.
            subtitleList.entries.removeIf { (id, s) -> id != item.task.id && s.display.tabId == tabId && s.task.url == item.task.url }
            if (subtitleList.values.count { it.display.tabId == tabId } >= MAX_SUBTITLES_PER_TAB) {
                Logger.info("XDM", "Tab $tabId has $MAX_SUBTITLES_PER_TAB subtitles, ignoring ${item.task.url}")
                false
            } else {
                subtitleList[item.task.id] = item
                true
            }
        }
        if (added) CaptureContext.host.videoListChanged()
    }

    override fun getSubtitle(videoId: Long): CapturedSubtitle? = synchronized(this) { subtitleList[videoId] }

    /**
     * Adds [item], first dropping any other capture in the same tab with the same media URL (or the
     * same video/audio URL pair). A tab is no longer cleared when it reloads the same page (see
     * ClearTabFilter), so the reload's captures would otherwise sit next to the old ones; the newest
     * is kept, as it carries the freshest signed URLs and headers. Same id: replaced in place.
     */
    private fun <T : Any> putLatest(
        list: MutableMap<Long, Pair<T, StreamingVideoDisplayInfo>>,
        id: Long,
        item: Pair<T, StreamingVideoDisplayInfo>,
        key: (T) -> String,
    ) {
        val itemKey = key(item.first)
        list.entries.removeIf { (otherId, other) ->
            otherId != id && other.second.tabId == item.second.tabId && key(other.first) == itemKey
        }
        list[id] = item
    }

    override val videoList: List<DetectedVideoInfo>
        get() {
            synchronized(this) {
                val list = ArrayList<DetectedVideoInfo>(hlsVideoList.size + httpVideoList.size)
                list.addAll(dashVideoList.values.map { (t, s) ->
                    DetectedVideoInfo(
                        id = t.id,
                        name = t.fileName,
                        description = s.descriptionText,
                        date = s.dateTime,
                        tabId = s.tabId,
                        previewable = previewUrl(t) != null,
                    )
                })
                list.addAll(hlsVideoList.values.map { (t, s) ->
                    DetectedVideoInfo(
                        id = t.id,
                        name = t.fileName,
                        description = s.descriptionText,
                        date = s.dateTime,
                        tabId = s.tabId
                    )
                })
                list.addAll(httpVideoList.values.map { (t, s) ->
                    DetectedVideoInfo(
                        id = t.id,
                        name = t.fileName,
                        description = s.descriptionText,
                        date = s.dateTime,
                        tabId = s.tabId,
                        previewable = previewUrl(t) != null,
                    )
                })
                list.addAll(subtitleList.values.map { s ->
                    DetectedVideoInfo(
                        id = s.task.id,
                        name = s.task.fileName,
                        description = s.display.descriptionText,
                        date = s.display.dateTime,
                        tabId = s.display.tabId,
                        subtitle = true,
                    )
                })
                return list
            }
        }

    override fun updateMediaTitle(tabUrl: String, tabTitle: String) {
        synchronized(this) {
            for ((s, di) in httpVideoList.values) {
                if (tabUrl == di.tabUrl) {
                    s.fileName = generateUpdatedFileName(s.fileName, tabTitle)
                }
            }
            for ((s, di) in hlsVideoList.values) {
                if (di.tabUrl == tabUrl) {
                    s.fileName = generateUpdatedFileName(s.fileName, tabTitle)
                }
            }
            // DASH entries were skipped here, so their names never picked up the tab title.
            for ((s, di) in dashVideoList.values) {
                if (di.tabUrl == tabUrl) {
                    s.fileName = generateUpdatedFileName(s.fileName, tabTitle)
                }
            }
            // Rebuilt from the title, so the language part of the name is kept.
            for (s in subtitleList.values) {
                if (s.display.tabUrl == tabUrl) {
                    s.task.fileName = SubtitleDetector.subtitleName(tabTitle, s.language, s.format)
                }
            }
        }
        CaptureContext.host.videoListChanged()
    }

    override fun getHttpVideo(videoId: Long): HttpDownloadTaskInfo? {
        synchronized(this) {
            this.httpVideoList[videoId]?.let {
                return it.first
            }
        }
        return null
    }

    override fun getHlsVideo(videoId: Long): HlsDownloadTaskInfo? {
        synchronized(this) {
            this.hlsVideoList[videoId]?.let {
                return it.first
            }
            (restarts[videoId] as? HlsDownloadTaskInfo)?.let { return it }
        }
        return null
    }

    override fun getDashVideo(videoId: Long): DashDownloadTaskInfo? {
        synchronized(this) {
            this.dashVideoList[videoId]?.let {
                return it.first
            }
            (restarts[videoId] as? DashDownloadTaskInfo)?.let { return it }
        }
        return null
    }

    override fun getDisplayInfo(videoId: Long): StreamingVideoDisplayInfo? = synchronized(this) {
        (hlsVideoList[videoId] ?: dashVideoList[videoId] ?: httpVideoList[videoId])?.second
            ?: subtitleList[videoId]?.display
    }

    override fun updateDisplay(videoIds: Collection<Long>, update: (Long, StreamingVideoDisplayInfo) -> Unit): Boolean {
        val any = synchronized(this) {
            var found = false
            for (id in videoIds) {
                val info = (hlsVideoList[id] ?: dashVideoList[id] ?: httpVideoList[id])?.second ?: continue
                update(id, info)
                found = true
            }
            found
        }
        if (any) CaptureContext.host.videoListChanged()
        return any
    }

    override fun clear() {
        synchronized(this) {
            hlsVideoList.clear()
            dashVideoList.clear()
            httpVideoList.clear()
            subtitleList.clear()
        }
        CaptureContext.host.videoListChanged()
    }

    override fun clearTab(tabId: String) {
        // Only entries captured in this very tab go; ones with no meaningful tab id are shown in
        // every tab (see the extension's isVideoForTab) and must survive a single tab reloading.
        val removed = synchronized(this) {
            // `or`, not `||`: every list must be swept, not just up to the first match.
            removeTabEntries(hlsVideoList, tabId) or
                    removeTabEntries(dashVideoList, tabId) or
                    removeTabEntries(httpVideoList, tabId) or
                    subtitleList.values.removeIf { it.display.tabId == tabId }
        }
        if (removed) {
            CaptureContext.host.videoListChanged()
        }
    }

    private fun <T> removeTabEntries(
        list: MutableMap<Long, Pair<T, StreamingVideoDisplayInfo>>,
        tabId: String
    ): Boolean = list.values.removeIf { (_, display) -> display.tabId == tabId }

    private val hlsVideoList = LinkedHashMap<Long, Pair<HlsDownloadTaskInfo, StreamingVideoDisplayInfo>>()
    private val dashVideoList = LinkedHashMap<Long, Pair<DashDownloadTaskInfo, StreamingVideoDisplayInfo>>()
    private val httpVideoList = LinkedHashMap<Long, Pair<HttpDownloadTaskInfo, StreamingVideoDisplayInfo>>()
    private val subtitleList = LinkedHashMap<Long, CapturedSubtitle>()

    /** Restarted HLS/DASH downloads waiting for their dialog; a few at most, oldest dropped first. */
    private val restarts = object : LinkedHashMap<Long, StreamingDownloadTaskInfo>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, StreamingDownloadTaskInfo>) = size > 16
    }

    override fun addRestart(task: StreamingDownloadTaskInfo): Long {
        synchronized(this) { restarts[task.id] = task }
        return task.id
    }
    private fun generateUpdatedFileName(oldName: String, newName: String): String {
        val ext: String? = getExtension(oldName)
        var newFileName = FileUtils.sanitizeFileName(newName)
        ext?.let { newFileName += ext }
        return newFileName!!
    }
}

/** Most subtitle files kept per tab: a page with every language of every episode stays usable. */
const val MAX_SUBTITLES_PER_TAB = 30

private val previewableExts = listOf("mp4", "m4v", "mov")

/**
 * The file `/preview` reads a plain capture's keyframes from: MP4 family by file name or URL path.
 * Whether it really is an MP4 the preview can use is only known once it is read.
 */
fun previewUrl(task: HttpDownloadTaskInfo): String? {
    val path = task.url.substringBefore('?').substringBefore('#')
    val mp4 = listOf(task.fileName, path).any { name -> previewableExts.any { name.endsWith(".$it", ignoreCase = true) } }
    return task.url.takeIf { mp4 }
}

/**
 * The file `/preview` reads a DASH capture's keyframes from: its video track when that is one MP4
 * file (a Facebook/Instagram split track, or a SegmentBase representation), cut into byte ranges or
 * not. Null for segmented video, which has no single file to read.
 */
fun previewUrl(task: DashDownloadTaskInfo): String? {
    if (!task.videoMime.contains("mp4", ignoreCase = true)) return null
    val first = task.videoSegments.firstOrNull() ?: return null
    if (task.videoSegments.any { it.url != first.url }) return null
    return first.url.toString()
}
