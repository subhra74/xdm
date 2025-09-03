package xdm.app.service.impl

import xdm.app.models.DetectedVideoInfo
import xdm.app.models.StreamingVideoDisplayInfo
import xdm.app.service.VideoTracker
import xdm.core.downloaders.hls.HlsSource
import xdm.core.downloaders.http.HttpSource
import xdm.core.util.FileUtils
import xdm.core.util.XDMUtils
import java.util.stream.Stream

class VideoTrackerImpl : VideoTracker {
    override fun addVideoHls(items: List<Pair<HlsSource, StreamingVideoDisplayInfo>>) {
        synchronized(this) {
            for (item in items) {
                hlsVideoList[item.first.id] = item
            }
        }
    }

    override fun addVideoHttp(items: List<Pair<HttpSource, StreamingVideoDisplayInfo>>) {
        synchronized(this) {
            for (item in items) {
                httpVideoList[item.first.id] = item
            }
        }
    }

    override val videoList: List<DetectedVideoInfo>
        get() {
            synchronized(this) {
                return Stream.concat(hlsVideoList.values.stream(), httpVideoList.values.stream()).map { (s, i) ->
                    DetectedVideoInfo(
                        id = s.id,
                        name = s.fileName,
                        description = i.descriptionText,
                        date = i.dateTime,
                        tabId = i.tabId
                    )
                }.toList()
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

    private val hlsVideoList = LinkedHashMap<Long, Pair<HlsSource, StreamingVideoDisplayInfo>>()
    private val httpVideoList = LinkedHashMap<Long, Pair<HttpSource, StreamingVideoDisplayInfo>>()
    private fun generateUpdatedFileName(oldName: String, newName: String): String {
        val ext: String? = XDMUtils.getExtension(oldName)
        var newFileName = FileUtils.sanitizeFileName(newName)
        ext?.let { newFileName += ext }
        return newFileName
    }
}