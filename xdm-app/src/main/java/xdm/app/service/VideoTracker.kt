package xdm.app.service

import xdm.app.models.DetectedVideoInfo
import xdm.app.models.StreamingVideoDisplayInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.http.HttpSource

interface VideoTracker {
    fun addVideoDownload(videoId: Long)
    fun addVideoHls(items: List<Pair<HlsDownloadTaskInfo, StreamingVideoDisplayInfo>>)
    fun addVideoHttp(items: List<Pair<HttpSource, StreamingVideoDisplayInfo>>)
    val videoList: List<DetectedVideoInfo>
    fun updateMediaTitle(tabUrl: String, tabTitle: String)
    fun getHttpVideo(videoId: Long): HttpSource?
    fun getHlsVideo(videoId: Long): HlsDownloadTaskInfo?
}