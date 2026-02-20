package xdm.core.downloaders.web.streaming.manifest.dash


import xdm.core.downloaders.web.streaming.manifest.common.resolveUri
import java.io.InputStream
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory


fun parseMpdManifest(inputStream: InputStream, playlistUrl: String): List<MpdEntry> {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.isNamespaceAware = false
    val builder = factory.newDocumentBuilder()
    val xmldoc = builder.parse(inputStream)

    val root = xmldoc.documentElement
    if ("MPD" != root.nodeName) {
        throw MpdParserException("Missing MPD start tag: " + root.nodeName)
    }
    if (root.hasAttribute("type") && "dynamic" == root.getAttribute("type")) {
        throw MpdParserException("Manifest type dynamic is not supported")
    }
    if (root.getElementsByTagName("ContentProtection").length != 0) {
        throw MpdParserException("Encrypted manifest")
    }

    val mediaList: MutableList<MpdEntry> = ArrayList()
    val mediaPresentationDuration =
        parseXsDuration(
            if (root.hasAttribute("mediaPresentationDuration"))
                root.getAttribute("mediaPresentationDuration")
            else
                "0"
        )
    var baseUrl = URI(playlistUrl)
    val baseUrlNodeRoot = root.getElementsByTagName("BaseURL")
    if (baseUrlNodeRoot.length > 0) {
        baseUrl = resolveUri(baseUrl, baseUrlNodeRoot.item(0).textContent)
    }
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
