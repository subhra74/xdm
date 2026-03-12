package xdm.app

import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.util.*
import xdm.integration.DetectedVideoInfo
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
    val videoList: List<DetectedVideoInfo>
}

class CapturedVideoTracker : ICapturedVideoTracker {
    override fun addVideoDownload(videoId: Long) {
        var name: String?
        var size: Long
        var contentType: String?
        httpVideoList[videoId]?.let {
            //TODO: Check for link refresh
            val (source, _) = it
            size = getContentLength(source.headers) ?: -1
            contentType = getHeader("Content-Type", source.headers)
            AppContext.app.addVideoDownload(videoId, source.fileName, size, contentType)
            return
        }

        hlsVideoList[videoId]?.let {
            //TODO: Check for link refresh
            val (source, _) = it
            AppContext.app.addVideoDownload(videoId, source.fileName, -1, "application/x-mpegURL")
            return
        }

        dashVideoList[videoId]?.let {
            //TODO: Check for link refresh
            val (source, _) = it
            AppContext.app.addVideoDownload(videoId, source.fileName, -1, "application/dash+xml")
            return
        }
    }


    override fun addVideoHls(items: List<Pair<HlsDownloadTaskInfo, StreamingVideoDisplayInfo>>) {
        synchronized(this) {
            for (item in items) {
                hlsVideoList[item.first.id] = item
            }
        }
    }

    override fun addVideoDash(items: List<Pair<DashDownloadTaskInfo, StreamingVideoDisplayInfo>>) {
        synchronized(this) {
            for (item in items) {
                dashVideoList[item.first.id] = item
            }
        }
    }

    override fun addVideoHttp(items: List<Pair<HttpDownloadTaskInfo, StreamingVideoDisplayInfo>>) {
        synchronized(this) {
            for (item in items) {
                httpVideoList[item.first.id] = item
            }
        }
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
        }
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
        }
        return null
    }

    override fun getDashVideo(videoId: Long): DashDownloadTaskInfo? {
        synchronized(this) {
            this.dashVideoList[videoId]?.let {
                return it.first
            }
        }
        return null
    }

    private val hlsVideoList = LinkedHashMap<Long, Pair<HlsDownloadTaskInfo, StreamingVideoDisplayInfo>>()
    private val dashVideoList = LinkedHashMap<Long, Pair<DashDownloadTaskInfo, StreamingVideoDisplayInfo>>()
    private val httpVideoList = LinkedHashMap<Long, Pair<HttpDownloadTaskInfo, StreamingVideoDisplayInfo>>()
    private fun generateUpdatedFileName(oldName: String, newName: String): String {
        val ext: String? = getExtension(oldName)
        var newFileName = FileUtils.sanitizeFileName(newName)
        ext?.let { newFileName += ext }
        return newFileName!!
    }
}