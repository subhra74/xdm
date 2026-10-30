// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.downloaders.web.streaming.manifest.dash

import org.w3c.dom.Element
import org.w3c.dom.Node
import xdm.core.downloaders.SubtitleTrack
import okhttp3.HttpUrl


fun parsePeriod(
    period: Node, baseUrl: HttpUrl?, mediaPresentationDuration: Long, budget: SegmentBudget
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
    val subtitles: MutableList<SubtitleTrack> = ArrayList()
    for (i in 0..<adaptationSets.length) {
        val adaptationSet = adaptationSets.item(i)
        val representations = parseAdaptationSet(
                adaptationSet, baseUrl, periodDuration, hasParentSegmentBase, budget
            )
        if (representations.isEmpty()) continue
        if (representations[0].mimeType == TEXT_VTT) {
            representations.mapTo(subtitles) { subtitleTrack(adaptationSet as Element, it) }
        } else if (representations[0].mimeType.startsWith("audio")) {
            audioList.addAll(representations)
        } else {
            videoList.addAll(representations)
        }
    }
    val mediaList: MutableList<MpdEntry> = ArrayList()
    if (videoList.isNotEmpty() && audioList.isNotEmpty()) {
        for (video in videoList) {
            for (audio in audioList) {
                mediaList.add(MpdEntry(video, audio, subtitles))
            }
        }
    } else if (videoList.isNotEmpty()) {
        for (video in videoList) {
            mediaList.add(MpdEntry(video, null, subtitles))
        }
    } else if (audioList.isNotEmpty()) {
        for (audio in audioList) {
            mediaList.add(MpdEntry(null, audio))
        }
    }
    return mediaList
}

/** A `text/vtt` representation as a subtitle track, named by the set's `label` or `<Label>`. */
private fun subtitleTrack(adaptationSet: Element, rep: Representation): SubtitleTrack {
    val label = adaptationSet.getAttribute("label").ifBlank {
        getDirectChildTagValue(adaptationSet, "Label") ?: ""
    }
    val roles = adaptationSet.getElementsByTagName("Role").let { list ->
        (0..<list.length).mapNotNull { getAttr(list.item(it), "value") }
    }
    return SubtitleTrack(
        name = label.trim(),
        language = rep.language,
        segments = rep.segments,
        isDefault = "main" in roles,
        forced = "forced-subtitle" in roles || "forced_subtitle" in roles,
    )
}
