package xdm.core.downloaders.web.streaming.manifest.dash


import org.w3c.dom.Element
import org.w3c.dom.Node
import xdm.core.downloaders.web.streaming.manifest.common.resolveUri
import java.io.InputStream
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory


/**
 * Fetches a DASH remote-element entity referenced by an `xlink:href`. Given the absolute URL of the
 * remote entity, returns its XML text, or null if it could not be retrieved. When no resolver is
 * supplied to [parseMpdManifest], remote (xlink) elements are left unresolved (and thus contribute
 * nothing), which is the historical behaviour.
 */
fun interface XlinkResolver {
    fun resolve(url: String): String?
}

private const val XLINK_HREF = "xlink:href"
private const val RESOLVE_TO_ZERO = "urn:mpeg:dash:resolve-to-zero:2013"
private const val MAX_XLINK_DEPTH = 5


fun parseMpdManifest(
    inputStream: InputStream,
    playlistUrl: String,
    xlinkResolver: XlinkResolver? = null
): List<MpdEntry> {
    val builder = newSecureFactory().newDocumentBuilder()
    val xmldoc = builder.parse(inputStream)

    val root = xmldoc.documentElement
    if ("MPD" != root.nodeName) {
        throw MpdParserException("Missing MPD start tag: " + root.nodeName)
    }
    if (root.hasAttribute("type") && "dynamic" == root.getAttribute("type")) {
        throw MpdParserException("Manifest type dynamic is not supported")
    }

    val mediaPresentationDuration =
        parseXsDuration(
            if (root.hasAttribute("mediaPresentationDuration"))
                root.getAttribute("mediaPresentationDuration")
            else
                "0"
        )
    var baseUrl = URI(playlistUrl)
    // Only a BaseURL that is a *direct* child of <MPD> is the document base. Using the recursive
    // getElementsByTagName here would grab a BaseURL nested inside an AdaptationSet/Representation
    // and wrongly apply it to every track (see getDirectChildTagValue).
    val rootBaseUrl = getDirectChildTagValue(root, "BaseURL")
    if (rootBaseUrl != null) {
        baseUrl = resolveUri(baseUrl, rootBaseUrl)
    }

    // Resolve xlink remote elements before the structural/encryption checks below, so that content
    // pulled in from a remote Period (or AdaptationSet) is treated exactly like inline content — in
    // particular it is subject to the same ContentProtection check. Period hrefs resolve against the
    // document base; AdaptationSet hrefs against their (resolved) period base.
    resolveXlinks(root, "Period", baseUrl, xlinkResolver, 0)
    val xlinkPeriods = root.getElementsByTagName("Period")
    for (i in 0..<xlinkPeriods.length) {
        val period = xlinkPeriods.item(i) as Element
        resolveXlinks(period, "AdaptationSet", resolveBaseUrl(period, baseUrl), xlinkResolver, 0)
    }

    if (root.getElementsByTagName("ContentProtection").length != 0) {
        throw MpdParserException("Encrypted manifest")
    }

    val mediaList: MutableList<MpdEntry> = ArrayList()
    val periods = root.getElementsByTagName("Period")
    if (periods.length == 0) throw MpdParserException("No period found!")
    if (periods.length > 1) {
        val periodDurations = calculatePeriodDurationsIfMissing(
            periods, mediaPresentationDuration
        )
        for (i in 0..<periods.length) {
            val period = periods.item(i)
            mediaList.addAll(parsePeriod(period, baseUrl, periodDurations[i]))
        }
    } else {
        mediaList.addAll(
            parsePeriod(periods.item(0), baseUrl, mediaPresentationDuration)
        )
    }
    return mediaList
}

private fun newSecureFactory(): DocumentBuilderFactory {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.isNamespaceAware = false
    return factory
}

/**
 * Replaces every direct child of [parent] named [tagName] that carries an `xlink:href` with the
 * element(s) fetched from that href, in document order. The placeholder is always removed: the
 * `resolve-to-zero` sentinel, a missing [resolver], an exhausted recursion depth, or a failed fetch
 * all leave nothing behind (an unresolved remote element contributes no content, and a lingering
 * empty Period would break period-duration inference). A remote element may itself contain further
 * placeholders of the same kind, resolved recursively up to [MAX_XLINK_DEPTH].
 */
private fun resolveXlinks(
    parent: Element, tagName: String, baseUrl: URI, resolver: XlinkResolver?, depth: Int
) {
    val targets = ArrayList<Element>()
    val children = parent.childNodes
    for (i in 0..<children.length) {
        val c = children.item(i)
        if (c.nodeType == Node.ELEMENT_NODE && c.nodeName == tagName && (c as Element).hasAttribute(XLINK_HREF)) {
            targets.add(c)
        }
    }
    if (targets.isEmpty()) return
    val doc = parent.ownerDocument
    for (placeholder in targets) {
        val href = placeholder.getAttribute(XLINK_HREF)
        val replacements = when {
            href == RESOLVE_TO_ZERO || resolver == null || depth >= MAX_XLINK_DEPTH -> emptyList()
            else -> fetchRemoteElements(href, tagName, baseUrl, resolver)
        }
        for (remote in replacements) {
            val imported = doc.importNode(remote, true) as Element
            resolveXlinks(imported, tagName, baseUrl, resolver, depth + 1)
            parent.insertBefore(imported, placeholder)
        }
        parent.removeChild(placeholder)
    }
}

/** Fetches [href] (resolved against [baseUrl]) and returns the top-level [tagName] elements it contains. */
private fun fetchRemoteElements(
    href: String, tagName: String, baseUrl: URI, resolver: XlinkResolver
): List<Element> {
    val abs = try {
        resolveUri(baseUrl, href).toString()
    } catch (ex: Exception) {
        return emptyList()
    }
    if (!abs.startsWith("http://", true) && !abs.startsWith("https://", true)) return emptyList()
    val body = resolver.resolve(abs)?.let(::stripXmlProlog) ?: return emptyList()
    return try {
        // Wrap so multiple top-level remote elements (a valid remote entity) parse under one root.
        val doc = newSecureFactory().newDocumentBuilder()
            .parse("<xdmXlinkRoot>$body</xdmXlinkRoot>".byteInputStream())
        val wrapperRoot = doc.documentElement
        val list = wrapperRoot.getElementsByTagName(tagName)
        (0..<list.length)
            .map { list.item(it) as Element }
            .filter { it.parentNode === wrapperRoot }
    } catch (ex: Exception) {
        emptyList()
    }
}

/** Drops a leading BOM and/or `<?xml …?>` declaration so the body can be embedded inside a wrapper. */
private fun stripXmlProlog(raw: String): String {
    var s = raw.trimStart('﻿', ' ', '\t', '\r', '\n')
    if (s.startsWith("<?xml")) {
        val end = s.indexOf("?>")
        if (end >= 0) s = s.substring(end + 2)
    }
    return s
}
