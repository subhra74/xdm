// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.subtitle

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the cues of a TTML / DFXP document (W3C TTML1/TTML2, IMSC text profile, SMPTE-TT, EBU-TT-D)
 * as [WebVtt.Cue]s, so they can be written as SRT by [WebVtt.formatSrt].
 *
 * Timing: `begin`, `end` and `dur` on `body`, `div`, `p` and `span`, each relative to its parent's
 * begin (the `par` time container every profile here uses). Clock times (`hh:mm:ss.fff`,
 * `hh:mm:ss:ff` with `ttp:frameRate`) and offset times (`12.5s`, `500ms`, `90t` with `ttp:tickRate`,
 * `25f`, `1.5m`, `0.1h`). A `p` without an end takes its parent's end; one with neither is dropped.
 *
 * Text: `<br/>` is a line break, `tts:fontStyle="italic"` / `tts:fontWeight="bold"` /
 * `tts:textDecoration="underline"` on a span become `<i>` / `<b>` / `<u>`; other styling is dropped.
 * Image subtitles (`smpte:backgroundImage`) hold no text and give no cues.
 */
object Ttml {
    private const val TTP = "http://www.w3.org/ns/ttml#parameter"
    private const val TTS = "http://www.w3.org/ns/ttml#styling"

    /** The document's `xml:lang`, or null. */
    fun language(text: String): String? = root(text)?.let { el ->
        el.getAttribute("xml:lang").takeIf { it.isNotBlank() }
            ?: el.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang").takeIf { it.isNotBlank() }
    }

    /** The cues of [text] in document order; empty when it is not TTML or holds no timed text. */
    fun parse(text: String): List<WebVtt.Cue> {
        val tt = root(text) ?: return emptyList()
        val declaredFrameRate = attr(tt, TTP, "frameRate")?.toDoubleOrNull()?.takeIf { it > 0 }
        val multiplier = attr(tt, TTP, "frameRateMultiplier")?.split(' ')
            ?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }
            ?.let { it[0] / it[1] } ?: 1.0
        val rates = Rates(
            frameRate = declaredFrameRate ?: 30.0,
            frameRateMultiplier = multiplier,
            // The spec's default: the effective frame rate when one is declared, else 1 tick a second.
            tickRate = attr(tt, TTP, "tickRate")?.toDoubleOrNull()?.takeIf { it > 0 }
                ?: declaredFrameRate?.let { it * multiplier } ?: 1.0,
        )
        val body = children(tt).firstOrNull { localName(it) == "body" } ?: return emptyList()
        val cues = ArrayList<WebVtt.Cue>()
        walk(body, 0L, null, rates, cues)
        return cues
    }

    internal class Rates(val frameRate: Double, val frameRateMultiplier: Double, val tickRate: Double)

    /** Visits [el] (body / div / p) whose parent begins at [parentBegin] and ends at [parentEnd]. */
    private fun walk(el: Element, parentBegin: Long, parentEnd: Long?, rates: Rates, out: MutableList<WebVtt.Cue>) {
        val (begin, end) = interval(el, parentBegin, parentEnd, rates)
        if (localName(el) == "p") {
            val e = end ?: return
            val sb = StringBuilder()
            appendText(el, sb)
            val body = sb.toString().lines().joinToString("\n") { it.trim() }.trim()
            if (body.isNotEmpty() && e > begin) out.add(WebVtt.Cue(begin, e, body))
            return
        }
        for (child in children(el)) {
            when (localName(child)) {
                "div", "p" -> walk(child, begin, end, rates, out)
            }
        }
    }

    private fun interval(el: Element, parentBegin: Long, parentEnd: Long?, rates: Rates): Pair<Long, Long?> {
        val begin = parentBegin + (time(el.getAttribute("begin"), rates) ?: 0L)
        val end = time(el.getAttribute("end"), rates)?.let { parentBegin + it }
            ?: time(el.getAttribute("dur"), rates)?.let { begin + it }
            ?: parentEnd
        return begin to (if (parentEnd != null && end != null) minOf(end, parentEnd) else end)
    }

    private fun appendText(node: Node, sb: StringBuilder) {
        var child = node.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                    // XML whitespace inside a cue collapses to single spaces; breaks come from <br/>.
                    sb.append(child.nodeValue.replace(WHITESPACE, " "))
                Node.ELEMENT_NODE -> {
                    val el = child as Element
                    when (localName(el)) {
                        "br" -> sb.append('\n')
                        "span" -> {
                            val tags = styleTags(el)
                            tags.forEach { sb.append('<').append(it).append('>') }
                            appendText(el, sb)
                            tags.asReversed().forEach { sb.append("</").append(it).append('>') }
                        }
                        else -> appendText(el, sb)
                    }
                }
            }
            child = child.nextSibling
        }
    }

    private fun styleTags(el: Element): List<String> = listOfNotNull(
        "i".takeIf { attr(el, TTS, "fontStyle").equals("italic", true) || attr(el, TTS, "fontStyle").equals("oblique", true) },
        "b".takeIf { attr(el, TTS, "fontWeight").equals("bold", true) },
        "u".takeIf { attr(el, TTS, "textDecoration")?.contains("underline", true) == true },
    )

    private val WHITESPACE = Regex("\\s+")
    private val CLOCK = Regex("""^(\d+):(\d{2}):(\d{2})(?:\.(\d+)|:(\d+(?:\.\d+)?))?$""")
    private val OFFSET = Regex("""^(\d+(?:\.\d+)?)(h|m|s|ms|f|t)$""")

    /** A TTML time expression in milliseconds, or null when blank or malformed. */
    internal fun time(s: String?, rates: Rates = Rates(30.0, 1.0, 1.0)): Long? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        CLOCK.find(t)?.let { m ->
            val (h, mi, sec) = Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong())
            var ms = ((h * 60 + mi) * 60 + sec) * 1000.0
            m.groupValues[4].takeIf { it.isNotEmpty() }?.let { ms += ("0.$it").toDouble() * 1000 }
            m.groupValues[5].takeIf { it.isNotEmpty() }?.let {
                ms += it.toDouble() * 1000 / (rates.frameRate * rates.frameRateMultiplier)
            }
            return Math.round(ms)
        }
        OFFSET.find(t)?.let { m ->
            val v = m.groupValues[1].toDouble()
            val ms = when (m.groupValues[2]) {
                "h" -> v * 3_600_000
                "m" -> v * 60_000
                "s" -> v * 1000
                "ms" -> v
                "f" -> v * 1000 / (rates.frameRate * rates.frameRateMultiplier)
                else -> v * 1000 / rates.tickRate
            }
            return Math.round(ms)
        }
        return null
    }

    private fun root(text: String): Element? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // No DTDs or external entities: the document came from a web page.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().apply { setErrorHandler(null) }
            .parse(InputSource(StringReader(text.removePrefix("﻿"))))
        doc.documentElement?.takeIf { localName(it) == "tt" }
    } catch (e: Exception) {
        null
    }

    private fun localName(el: Element): String = el.localName ?: el.tagName.substringAfter(':')

    private fun attr(el: Element, ns: String, name: String): String? =
        el.getAttributeNS(ns, name).takeIf { it.isNotEmpty() }

    private fun children(el: Element): List<Element> {
        val out = ArrayList<Element>()
        var c = el.firstChild
        while (c != null) {
            if (c is Element) out.add(c)
            c = c.nextSibling
        }
        return out
    }
}
