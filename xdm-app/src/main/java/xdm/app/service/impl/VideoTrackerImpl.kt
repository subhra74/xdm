package xdm.app.service.impl

import xdm.app.AppContext
import xdm.app.models.DetectedVideoInfo
import xdm.app.models.StreamingVideoDisplayInfo
import xdm.app.service.VideoTracker
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.util.FileUtils
import xdm.core.util.XDMUtils
import xdm.core.util.getContentLength
import xdm.core.util.getHeader

class VideoTrackerImpl : VideoTracker {
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
            name = source.fileName
            contentType = "application/x-mpegURL"
            AppContext.app.addVideoDownload(videoId, name, -1, contentType)
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

    private val hlsVideoList = LinkedHashMap<Long, Pair<HlsDownloadTaskInfo, StreamingVideoDisplayInfo>>()
    private val httpVideoList = LinkedHashMap<Long, Pair<HttpDownloadTaskInfo, StreamingVideoDisplayInfo>>()
    private fun generateUpdatedFileName(oldName: String, newName: String): String {
        val ext: String? = XDMUtils.getExtension(oldName)
        var newFileName = FileUtils.sanitizeFileName(newName)
        ext?.let { newFileName += ext }
        return newFileName
    }
}