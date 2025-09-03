package xdm.app.service

import xdm.app.models.DetectedVideoInfo
import xdm.app.models.StreamingVideoDisplayInfo
import xdm.core.downloaders.hls.HlsSource
import xdm.core.downloaders.http.HttpSource

interface VideoTracker {
    fun addVideoHls(items: List<Pair<HlsSource, StreamingVideoDisplayInfo>>)
    fun addVideoHttp(items: List<Pair<HttpSource, StreamingVideoDisplayInfo>>)
    val videoList: List<DetectedVideoInfo>
    fun updateMediaTitle(tabUrl: String, tabTitle: String)
}