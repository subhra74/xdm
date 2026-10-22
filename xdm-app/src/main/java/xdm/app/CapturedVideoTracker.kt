package xdm.app

import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.StreamingDownloadTaskInfo
import xdm.core.util.*
import xdm.integration.DetectedVideoInfo
import xdm.integration.EventChannel
import xdm.integration.StreamingVideoDisplayInfo

interface ICapturedVideoTracker {
    fun addVideoDownload(videoId: Long)
    fun addVideoHls(items: List<Pair<HlsDownloadTaskInfo, StreamingVideoDisplayInfo>>)
    fun addVideoDash(items: List<Pair<DashDownloadTaskInfo, StreamingVideoDisplayInfo>>)
    fun addVideoHttp(items: List<Pair<HttpDownloadTaskInfo, StreamingVideoDisplayInfo>>)
    fun updateMediaTitle(tabUrl: String, tabTitle: String)
    fun getHttpVideo(videoId: Long): HttpDownloadTaskInfo?
    fun getHlsVideo(videoId: Long): HlsDownloadTaskInfo?
    fun getDashVideo(videoId: Long): DashDownloadTaskInfo?

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
        } ?: return

        AppContext.app.addVideoDownload(videoId, summary.fileName, summary.size, summary.contentType)
    }


    override fun addVideoHls(items: List<Pair<HlsDownloadTaskInfo, StreamingVideoDisplayInfo>>) {
        synchronized(this) {
            for (item in items) {
                putLatest(hlsVideoList, item.first.id, item) { "${it.url}|${it.audioUrl}" }
            }
        }
        EventChannel.notifyChanged()
    }

    override fun addVideoDash(items: List<Pair<DashDownloadTaskInfo, StreamingVideoDisplayInfo>>) {
        synchronized(this) {
            for (item in items) {
                putLatest(dashVideoList, item.first.id, item) {
                    "${it.videoSegments.firstOrNull() ?: it.url}|${it.audioSegments.firstOrNull()}"
                }
            }
        }
        EventChannel.notifyChanged()
    }

    override fun addVideoHttp(items: List<Pair<HttpDownloadTaskInfo, StreamingVideoDisplayInfo>>) {
        synchronized(this) {
            for (item in items) {
                putLatest(httpVideoList, item.first.id, item) { it.url }
            }
        }
        EventChannel.notifyChanged()
    }

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
                        tabId = s.tabId
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
                        tabId = s.tabId
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
        }
        EventChannel.notifyChanged()
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

    override fun clear() {
        synchronized(this) {
            hlsVideoList.clear()
            dashVideoList.clear()
            httpVideoList.clear()
        }
        EventChannel.notifyChanged()
    }

    override fun clearTab(tabId: String) {
        // Only entries captured in this very tab go; ones with no meaningful tab id are shown in
        // every tab (see the extension's isVideoForTab) and must survive a single tab reloading.
        val removed = synchronized(this) {
            // `or`, not `||`: every list must be swept, not just up to the first match.
            removeTabEntries(hlsVideoList, tabId) or
                    removeTabEntries(dashVideoList, tabId) or
                    removeTabEntries(httpVideoList, tabId)
        }
        if (removed) {
            EventChannel.notifyChanged()
        }
    }

    private fun <T> removeTabEntries(
        list: MutableMap<Long, Pair<T, StreamingVideoDisplayInfo>>,
        tabId: String
    ): Boolean = list.values.removeIf { (_, display) -> display.tabId == tabId }

    private val hlsVideoList = LinkedHashMap<Long, Pair<HlsDownloadTaskInfo, StreamingVideoDisplayInfo>>()
    private val dashVideoList = LinkedHashMap<Long, Pair<DashDownloadTaskInfo, StreamingVideoDisplayInfo>>()
    private val httpVideoList = LinkedHashMap<Long, Pair<HttpDownloadTaskInfo, StreamingVideoDisplayInfo>>()

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