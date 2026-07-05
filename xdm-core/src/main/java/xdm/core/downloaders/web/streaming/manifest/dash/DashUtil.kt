package xdm.core.downloaders.web.streaming.manifest.dash

import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import xdm.core.downloaders.web.streaming.manifest.common.resolveUri
import xdm.core.util.StringUtils
import java.net.URI
import java.util.regex.Matcher
import java.util.regex.Pattern


private val XS_DURATION_PATTERN: Pattern = Pattern.compile(
    "^(-)?P((\\d*)Y)?((\\d*)M)?((\\d*)D)?(T((\\d*)H)?((\\d*)M)?(([0-9.]*)S)?)?$", Pattern.CASE_INSENSITIVE
)

private const val TRICK_MODE_URL = "http://dashif.org/guidelines/trickmode"

fun parseXsDuration(value: String): Long {
    val match = XS_DURATION_PATTERN.matcher(value)
    return if (match.matches()) {
        val negated = !StringUtils.isNullOrEmpty(match.group(1))
        // Durations containing years and months aren't completely defined. We assume there are
        // 30.4368 days in a month, and 365.242 days in a year.
        val years = match.group(3)
        val durationSeconds = getDurationSeconds(years, match)
        val durationMillis = (durationSeconds * 1000).toLong()
        if (negated) -durationMillis else durationMillis
    } else {
        (value.toDouble() * 3600 * 1000).toLong()
    }
}

private fun getDurationSeconds(years: String?, match: Matcher): Double {
    var durationSeconds = years?.toDoubleOrNull()?.let { it * 31556908 } ?: 0.0
    val months = match.group(5)
    durationSeconds += months?.toDoubleOrNull()?.let { it * 2629739 } ?: 0.0
    val days = match.group(7)
    durationSeconds += days?.toDoubleOrNull()?.let { it * 86400 } ?: 0.0
    val hours = match.group(10)
    durationSeconds += hours?.toDoubleOrNull()?.let { it * 3600 } ?: 0.0
    val minutes = match.group(12)
    durationSeconds += minutes?.toDoubleOrNull()?.let { it * 60 } ?: 0.0
    val seconds = match.group(14)
    durationSeconds += seconds?.toDoubleOrNull() ?: 0.0
    return durationSeconds
}

fun containsTrickMode(xmlAdaptationSet: Node): Boolean {
    val xmlEssentialProperty = getFirstTag(xmlAdaptationSet, "EssentialProperty")
    if (xmlEssentialProperty != null) {
        val schemeIdUri = getAttr(xmlEssentialProperty, "schemeIdUri")
        if (TRICK_MODE_URL == schemeIdUri) {
            return true
        }
    }
    val xmlSupplementalProperty = getFirstTag(xmlAdaptationSet, "SupplementalProperty")
    if (xmlSupplementalProperty != null) {
        val schemeIdUri = getAttr(xmlSupplementalProperty, "schemeIdUri")
        return TRICK_MODE_URL == schemeIdUri
    }
    return false
}

fun getAttr(node: Node, attrName: String?): String? {
    val child = node.attributes.getNamedItem(attrName)
    if (child != null) {
        return child.nodeValue
    }
    return null
}

fun getFirstTagValue(node: Node, childTagName: String?): String? {
    val nodeList = (node as Element).getElementsByTagName(childTagName)
    if (nodeList.length > 0) {
        return nodeList.item(0).textContent
    }
    return null
}

/**
 * Returns the text of the first *direct child* element named [childTagName], or null.
 *
 * Unlike [getFirstTagValue] (which uses the recursive [Element.getElementsByTagName] and can
 * therefore return an element nested arbitrarily deep), this only inspects immediate children.
 * This matters for `BaseURL`, whose scope in DASH is the element it is a direct child of: a
 * recursive lookup at the MPD/Period level would wrongly pick up a `BaseURL` belonging to a
 * nested AdaptationSet/Representation and apply it to unrelated tracks.
 */
fun getDirectChildTagValue(node: Node, childTagName: String): String? {
    return getDirectChildTag(node, childTagName)?.textContent
}

/** Returns the first *direct child* element named [childTagName], or null. See [getDirectChildTagValue]. */
fun getDirectChildTag(node: Node, childTagName: String): Node? {
    val children = node.childNodes
    for (i in 0..<children.length) {
        val child = children.item(i)
        if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == childTagName) {
            return child
        }
    }
    return null
}

fun getFirstTag(node: Node, childTagName: String?): Node? {
    val nodeList = (node as Element).getElementsByTagName(childTagName)
    if (nodeList.length > 0) {
        return nodeList.item(0)
    }
    return null
}

fun findTag(node: Node, tags: List<String?>): Node? {
    for (tag in tags) {
        val nodeList = (node as Element).getElementsByTagName(tag)
        if (nodeList.length > 0) {
            return nodeList.item(0)
        }
    }
    return null
}

fun containsTag(node: Node, childTagName: String?): Boolean {
    return (node as Element).getElementsByTagName(childTagName).length > 0
}

fun getSelfOrParentAttr(
    selfAttrs: NamedNodeMap?, parentAttrs: NamedNodeMap?, attrName: String?
): String? {
    var attr: Node? = null
    if (selfAttrs != null) {
        attr = selfAttrs.getNamedItem(attrName)
    }
    if (attr == null && parentAttrs != null) {
        attr = parentAttrs.getNamedItem(attrName)
    }
    if (attr != null) {
        return attr.nodeValue
    }
    return null
}

fun resolveBaseUrl(node: Node, baseUrl: URI): URI {
    val baseUrlValue = getDirectChildTagValue(node, "BaseURL")
    if (baseUrlValue != null) {
        return resolveUri(baseUrl, baseUrlValue)
    }
    return baseUrl
}

/**
 * Finds a [name] element that applies to [node] by walking up the ancestor chain
 * (Representation → AdaptationSet → Period), using *direct-child* lookups at each level.
 *
 * A `SegmentTemplate` inherits from the level it is a direct child of. A recursive search on an
 * ancestor would incorrectly match a *sibling* Representation's `SegmentTemplate`, so we only ever
 * inspect immediate children.
 */
fun findSelfOrParentTag(node: Node, name: String): Node? {
    var current: Node? = node
    while (current != null && current.nodeType == Node.ELEMENT_NODE) {
        val found = getDirectChildTag(current, name)
        if (found != null) return found
        current = current.parentNode
    }
    return null
}
