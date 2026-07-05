package xdm.core.downloaders.web.streaming.manifest.dash

import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import xdm.core.downloaders.web.streaming.manifest.common.resolveUri
import java.net.URI
import java.util.*
import java.util.regex.Pattern
import kotlin.math.ceil


private const val MEDIA_KEY = "media"
private val TemplatePattern: Pattern = Pattern.compile(
    "\\$(RepresentationID|Time|Time%0(?<timedigits>\\d+)(?<timedx>[dx])?|Number|Number%0(?<numdigits>\\d+)(?<numdx>[dx])?|Bandwidth)\\$"
)

fun parseAdaptationSet(
    xmlAdaptationSet: Node, baseUrl: URI, periodDuration: Long, hasParentSegmentBase: Boolean
): List<Representation> {
    var baseUrl = baseUrl
    val representations: MutableList<Representation> = ArrayList()
    val baseUrlValue = getDirectChildTagValue(xmlAdaptationSet, "BaseURL")
    if (baseUrlValue != null) {
        baseUrl = resolveUri(baseUrl, baseUrlValue)
    }
    if (containsTrickMode(xmlAdaptationSet)) {
        return representations
    }
    val xmlRepresentations = (xmlAdaptationSet as Element).getElementsByTagName("Representation")
    for (i in 0..<xmlRepresentations.length) {
        val rep = parseRepresentation(
            xmlRepresentations.item(i), baseUrl, periodDuration, hasParentSegmentBase
        )
        if (rep != null) representations.add(rep)
    }
    return representations
}

private fun getAttr(
    attrs: NamedNodeMap, pAttrs: NamedNodeMap, name: String
) = getSelfOrParentAttr(attrs, pAttrs, name)

private fun parseSegmentList(
    xmlSegmentList: Node,
    baseUrl: URI,
    width: Int,
    height: Int,
    codec: String,
    bandwidth: Long,
    periodDuration: Long,
    mimeType: String,
    lang: String
): Representation? {
    val segmentUrlNodes = (xmlSegmentList as Element).getElementsByTagName("SegmentURL")
    val segments: MutableList<URI> = ArrayList()
    val xmlInit = findTag(xmlSegmentList, mutableListOf("Initialization", "RepresentationIndex"))
    if (xmlInit != null) {
        val sourceURL = getAttr(xmlInit, "sourceURL")
        if (sourceURL != null) {
            segments.add(resolveUri(baseUrl, sourceURL))
        }
    }
    for (i in 0..<segmentUrlNodes.length) {
        val segmentNode = segmentUrlNodes.item(i)
        val media = getAttr(segmentNode, MEDIA_KEY)
        if (media != null) {
            segments.add(resolveUri(baseUrl, media))
        }
    }
    if (segments.isNotEmpty()) {
        return Representation(
            width, height, codec, bandwidth, periodDuration, segments, mimeType, lang
        )
    }
    return null
}

private fun parseSegmentTimeLineSimple(
    xmlSegmentTemplate: Node,
    attrs: NamedNodeMap,
    baseUrl: URI,
    width: Int,
    height: Int,
    codec: String,
    bandwidth: Long,
    bandwidthStr: String,
    periodDuration: Long,
    mimeType: String,
    lang: String
): Representation? {
    val timescale = getAttr(xmlSegmentTemplate, "timescale")?.toLongOrNull() ?: 1L
    val duration = getAttr(xmlSegmentTemplate, "duration")?.toLongOrNull() ?: 1L
    val startNumber = getAttr(xmlSegmentTemplate, "startNumber")?.toLongOrNull() ?: 1L
    val segmentCount = ceil((periodDuration.toDouble() / 1000) / (duration.toDouble() / timescale)).toInt()
    val representationId = attrs.getNamedItem("id")?.nodeValue ?: ""
    var number = startNumber
    // $Time$ addressing counts presentation time from the presentation-time-offset (default 0),
    // not from startNumber. Seeding time with startNumber would offset every $Time$ URL.
    var time = 0L
    val initUrl = getAttr(xmlSegmentTemplate, "initialization")?.replace("$$", "\u0000")
    val mediaUrl = getAttr(xmlSegmentTemplate, MEDIA_KEY)?.replace("$$", "\u0000")
    val mediaMatches: MutableList<MatcherResult> = ArrayList()
    if (mediaUrl != null) {
        val m = TemplatePattern.matcher(mediaUrl)
        while (m.find()) mediaMatches.add(MatcherResult.toMatcherResult(m))
    }
    val segments: MutableList<URI> = ArrayList(segmentCount + (if (initUrl != null) 1 else 0))
    if (initUrl != null) {
        val initMatches: MutableList<MatcherResult> = ArrayList()
        val m = TemplatePattern.matcher(initUrl)
        while (m.find()) initMatches.add(MatcherResult.toMatcherResult(m))
        val initializationUrl = parseTemplate(
            initMatches, initUrl, number, time, bandwidthStr, representationId
        ).replace("\u0000", "$")
        segments.add(resolveUri(baseUrl, initializationUrl))
    }
    for (i in 0..<segmentCount) {
        val segmentUrl = parseTemplate(
            mediaMatches, mediaUrl!!, number, time, bandwidthStr, representationId
        ).replace("\u0000", "$")
        segments.add(resolveUri(baseUrl, segmentUrl))
        number++
        time += duration
    }
    if (segments.isNotEmpty()) {
        return Representation(
            width, height, codec, bandwidth, periodDuration, segments, mimeType, lang
        )
    }
    return null
}

private fun matchTemplateUrl(url: String?): List<MatcherResult> {
    val arr: MutableList<MatcherResult> = ArrayList()
    if (url != null) {
        val m = TemplatePattern.matcher(url)
        while (m.find()) arr.add(MatcherResult.toMatcherResult(m))
    }
    return arr
}

/** The explicit start time (@t) of the S element after [index], or null if there is none. */
private fun nextSegmentStartTime(xmlSs: NodeList, index: Int): Long? {
    if (index + 1 >= xmlSs.length) return null
    return getAttr(xmlSs.item(index + 1), "t")?.toLongOrNull()
}

private fun parseSegmentTimeLineExplicit(
    xmlSegmentTemplate: Node,
    xmlSs: NodeList,
    attrs: NamedNodeMap,
    baseUrl: URI,
    width: Int,
    height: Int,
    codec: String,
    bandwidth: Long,
    bandwidthStr: String,
    periodDuration: Long,
    mimeType: String,
    lang: String
): Representation? {
    val representationId = attrs.getNamedItem("id")?.nodeValue ?: ""
    val timescale = getAttr(xmlSegmentTemplate, "timescale")?.toLongOrNull() ?: 1L
    var number = getAttr(xmlSegmentTemplate, "startNumber")?.toLongOrNull() ?: 1L
    var time = 0L

    val initUrl = getAttr(xmlSegmentTemplate, "initialization")?.replace("$$", "\u0000")
    val mediaUrl = getAttr(xmlSegmentTemplate, MEDIA_KEY)?.replace("$$", "\u0000")
    val mediaMatches = matchTemplateUrl(mediaUrl)
    val segments: MutableList<URI> = ArrayList()
    if (initUrl != null) {
        val initMatches = matchTemplateUrl(initUrl)
        val initializationUrl = parseTemplate(
            initMatches, initUrl, number, time, bandwidthStr, representationId
        ).replace("\u0000", "$")
        segments.add(resolveUri(baseUrl, initializationUrl))
    }
    // End of the timeline, in @timescale ticks, used to bound an "r=-1" run.
    val periodEndTicks = (periodDuration.toDouble() / 1000.0 * timescale).toLong()
    for (i in 0..<xmlSs.length) {
        val xmls = xmlSs.item(i)
        val d = getAttr(xmls, "d")?.toLongOrNull() ?: 0L
        val t = getAttr(xmls, "t")?.toLongOrNull() ?: -1L
        val r = getAttr(xmls, "r")?.toLongOrNull() ?: 0L
        // t="0" is a valid start time; only a missing t (-1) is ignored.
        if (t >= 0) time = t
        var segmentUrl = parseTemplate(
            mediaMatches, mediaUrl!!, number, time, bandwidthStr, representationId
        ).replace("\u0000", "$")
        segments.add(resolveUri(baseUrl, segmentUrl))
        number++
        time += d
        if (r > 0) {
            for (k in 0..<r) {
                segmentUrl = parseTemplate(
                    mediaMatches, mediaUrl, number, time, bandwidthStr, representationId
                ).replace("\u0000", "$")
                segments.add(resolveUri(baseUrl, segmentUrl))
                number++
                time += d
            }
        } else if (r < 0 && d > 0) {
            // r="-1": repeat until the next S element's start time, or the end of the period.
            val boundary = nextSegmentStartTime(xmlSs, i) ?: periodEndTicks
            while (time < boundary) {
                segmentUrl = parseTemplate(
                    mediaMatches, mediaUrl, number, time, bandwidthStr, representationId
                ).replace("\u0000", "$")
                segments.add(resolveUri(baseUrl, segmentUrl))
                number++
                time += d
            }
        }
    }
    if (segments.isNotEmpty()) {
        return Representation(
            width, height, codec, bandwidth, periodDuration, segments, mimeType, lang
        )
    }

    return null
}

private fun parseRepresentation(
    xmlRepresentation: Node, baseUrlIn: URI, periodDuration: Long, hasParentSegmentBase: Boolean
): Representation? {
    var baseUrl = baseUrlIn
    val attrs = xmlRepresentation.attributes
    val parent = xmlRepresentation.parentNode
    val pAttrs = parent.attributes
    val mimeType = getAttr(attrs, pAttrs, "mimeType")?.lowercase(Locale.getDefault()) ?: ""
    val width = getAttr(attrs, pAttrs, "width")?.toIntOrNull() ?: -1
    val height = getAttr(attrs, pAttrs, "height")?.toIntOrNull() ?: -1
    val bw = getAttr(attrs, pAttrs, "bandwidth")
    val bandwidthStr = bw ?: ""
    val bandwidth = bw?.toLongOrNull() ?: -1L
    val codec = getAttr(attrs, pAttrs, "codecs")?.lowercase(Locale.getDefault()) ?: ""
    val lang = getAttr(attrs, pAttrs, "lang")?.lowercase(Locale.getDefault()) ?: ""

    if (!(mimeType.startsWith("audio") || mimeType.startsWith("video"))) {
        return null
    }

    // BaseURL
    baseUrl = resolveBaseUrl(xmlRepresentation, baseUrl)

    // SegmentBase
    val segmentBaseNodes = (xmlRepresentation as Element).getElementsByTagName("SegmentBase")
    if (segmentBaseNodes.length > 0 || hasParentSegmentBase) {
        val segments: MutableList<URI> = ArrayList()
        segments.add(baseUrl)
        return Representation(
            width, height, codec, bandwidth, periodDuration, segments, mimeType, lang
        )
    }

    // SegmentList
    val xmlSegmentList = getFirstTag(xmlRepresentation, "SegmentList")
    if (xmlSegmentList != null) {
        return parseSegmentList(
            xmlSegmentList, baseUrl, width, height, codec, bandwidth, periodDuration, mimeType, lang
        )
    }

    // SegmentTemplate
    val xmlSegmentTemplate = findSelfOrParentTag(xmlRepresentation, "SegmentTemplate")
    if (xmlSegmentTemplate != null) {
        val xmlSegmentTimeline = getFirstTag(xmlSegmentTemplate, "SegmentTimeline") ?: // simple addressing
        return parseSegmentTimeLineSimple(
            xmlSegmentTemplate,
            attrs,
            baseUrl,
            width,
            height,
            codec,
            bandwidth,
            bandwidthStr,
            periodDuration,
            mimeType,
            lang
        )
        // explicit addressing
        val xmlSs = (xmlSegmentTimeline as Element).getElementsByTagName("S")
        if (xmlSs.length > 0) {
            return parseSegmentTimeLineExplicit(
                xmlSegmentTemplate,
                xmlSs,
                attrs,
                baseUrl,
                width,
                height,
                codec,
                bandwidth,
                bandwidthStr,
                periodDuration,
                mimeType,
                lang
            )
        }
    }
    return null
}
