package xdm.core.downloaders.web.streaming.manifest.dash

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.net.URI


fun parsePeriod(
    period: Node, baseUrl: URI?, mediaPresentationDuration: Long
): List<MpdEntry> {
    var baseUrl = baseUrl
    var periodDuration = mediaPresentationDuration
    val durationAttr = getAttr(period, "duration")
    if (durationAttr != null) {
        periodDuration = parseXsDuration(durationAttr)
    }
    baseUrl = resolveBaseUrl(period, baseUrl!!)
    val hasParentSegmentBase = containsTag(period, "SegmentBase")
    val adaptationSets = (period as Element).getElementsByTagName("AdaptationSet")
    val audioList: MutableList<Representation> = ArrayList()
    val videoList: MutableList<Representation> = ArrayList()
    for (i in 0..<adaptationSets.length) {
        val representations = parseAdaptationSet(
                adaptationSets.item(i), baseUrl, periodDuration, hasParentSegmentBase
            )
        if (representations.isEmpty()) continue
        if (representations[0].mimeType.startsWith("audio")) {
            audioList.addAll(representations)
        } else {
            videoList.addAll(representations)
        }
    }
    val mediaList: MutableList<MpdEntry> = ArrayList()
    if (videoList.isNotEmpty() && audioList.isNotEmpty()) {
        for (video in videoList) {
            for (audio in audioList) {
                mediaList.add(MpdEntry(video, audio))
            }
        }
    } else if (videoList.isNotEmpty()) {
        for (video in videoList) {
            mediaList.add(MpdEntry(video, null))
        }
    } else if (audioList.isNotEmpty()) {
        for (audio in audioList) {
            mediaList.add(MpdEntry(null, audio))
        }
    }
    return mediaList
}
