// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xdm.core.downloaders.web.streaming.manifest.dash.MAX_SEGMENTS_PER_MANIFEST
import xdm.core.downloaders.web.streaming.manifest.dash.MAX_SEGMENTS_PER_REPRESENTATION
import xdm.core.downloaders.web.streaming.manifest.dash.MpdEntry
import xdm.core.downloaders.web.streaming.manifest.dash.MpdParserException
import xdm.core.downloaders.web.streaming.manifest.dash.XlinkResolver
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest

/**
 * Manifests are parsed in the background for any page that plays DASH, and segment counts come from
 * the server. Each case here used to run the heap out (OutOfMemoryError); now it fails to parse, or
 * drops the one representation, while real long presentations still parse.
 */
class TestDashSegmentLimits {
    private fun parse(mpd: String, resolver: XlinkResolver? = null): List<MpdEntry> =
        mpd.trimIndent().byteInputStream().use { parseMpdManifest(it, "https://example.com/v.mpd", resolver) }

    private fun representation(id: String, template: String) =
        """<Representation id="$id" bandwidth="1000" codecs="avc1.42c00d">$template</Representation>"""

    private fun mpd(duration: String, vararg representations: String, periods: String? = null) = """
        <MPD type="static" mediaPresentationDuration="$duration" xmlns="urn:mpeg:dash:schema:mpd:2011"
             xmlns:xlink="http://www.w3.org/1999/xlink">
          ${periods ?: """<Period><AdaptationSet mimeType="video/mp4">${representations.joinToString("")}</AdaptationSet></Period>"""}
        </MPD>
    """

    private fun simple(attrs: String, media: String = "seg-\$Number\$.m4s") =
        """<SegmentTemplate media="$media" $attrs/>"""

    private fun timeline(s: String) =
        """<SegmentTemplate media="seg-${'$'}Time${'$'}.m4s" timescale="1"><SegmentTimeline>$s</SegmentTimeline></SegmentTemplate>"""

    @Test
    fun tinyTemplateDuration_isRefused() {
        assertThrows<MpdParserException> { parse(mpd("PT100000H", representation("v", simple("""duration="1" timescale="1000"""")))) }
        assertThrows<MpdParserException> { parse(mpd("PT10000H", representation("v", simple("""duration="1" timescale="1"""")))) }
    }

    @Test
    fun zeroOrNegativeTemplateDuration_dropsTheRepresentation() {
        for (attrs in listOf("""duration="0" timescale="1000"""", """duration="-5"""", """duration="2" timescale="0"""", """duration="2" timescale="-1"""")) {
            assertNull(parse(mpd("PT10H", representation("v", simple(attrs)))).firstOrNull()?.video, attrs)
        }
    }

    @Test
    fun hugeTimelineRepeat_isRefused() {
        assertThrows<MpdParserException> { parse(mpd("PT10H", representation("v", timeline("""<S t="0" d="1" r="2000000000"/>""")))) }
        assertThrows<MpdParserException> { parse(mpd("PT10H", representation("v", timeline("""<S t="0" d="1" r="9223372036854775807"/>""")))) }
    }

    @Test
    fun openEndedTimelineRepeat_isRefused() {
        assertThrows<MpdParserException> { parse(mpd("PT10000000H", representation("v", timeline("""<S t="0" d="1" r="-1"/>""")))) }
    }

    @Test
    fun manyRepresentationsUnderTheCap_areRefusedTogether() {
        // Each just under the per-representation cap; together over the manifest cap.
        val perRep = MAX_SEGMENTS_PER_REPRESENTATION - 10
        val reps = (1..MAX_SEGMENTS_PER_MANIFEST / perRep + 1).map {
            representation("v$it", simple("""duration="1" timescale="1"""", "v$it-\$Number\$.m4s"))
        }
        assertThrows<MpdParserException> { parse(mpd("PT${perRep}S", *reps.toTypedArray())) }
    }

    @Test
    fun xlinkPeriodsCountTowardsTheManifestCap() {
        val perRep = MAX_SEGMENTS_PER_REPRESENTATION - 10
        val remote = """<Period duration="PT${perRep}S"><AdaptationSet mimeType="video/mp4">${
            representation("r", simple("""duration="1" timescale="1""""))
        }</AdaptationSet></Period>"""
        val count = MAX_SEGMENTS_PER_MANIFEST / perRep + 1
        val periods = (1..count).joinToString("") { """<Period duration="PT${perRep}S" xlink:href="https://example.com/p$it.xml"/>""" }
        assertThrows<MpdParserException> { parse(mpd("PT${perRep * count}S", periods = periods)) { remote } }
    }

    @Test
    fun hugeNumberWidth_isCapped() {
        val video = parse(mpd("PT4S", representation("v", simple("""duration="1" timescale="1"""", "seg-\$Number%0999999999d\$.m4s"))))
            .first().video!!
        assertEquals(4, video.segments.size)
        assertEquals("https://example.com/seg-00000000000000000001.m4s", video.segments[0].url.toString(), "width capped at 20")
    }

    @Test
    fun widthTooLongToParse_isCapped() {
        val video = parse(mpd("PT2S", representation("v", simple("""duration="1" timescale="1"""", "seg-\$Number%099999999999999999999d\$.m4s"))))
            .first().video!!
        assertTrue(video.segments.all { it.url.toString().length < 100 }, "segment URLs stay short")
    }

    @Test
    fun ordinaryWidthUnchanged() {
        val video = parse(mpd("PT2S", representation("v", simple("""duration="1" timescale="1"""", "seg-\$Number%05d\$.m4s"))))
            .first().video!!
        assertEquals("https://example.com/seg-00001.m4s", video.segments[0].url.toString())
    }

    /** A day of 2s segments in eight renditions (the most a real VOD does) still parses. */
    @Test
    fun aDayLongPresentationInEightRenditions_stillParses() {
        val reps = (1..8).map { representation("v$it", simple("""duration="2000" timescale="1000"""", "v$it-\$Number\$.m4s")) }
        val entries = parse(mpd("PT24H", *reps.toTypedArray()))
        assertEquals(8, entries.size)
        assertTrue(entries.all { it.video!!.segments.size == 43_200 }, "43200 segments each")
    }

    @Test
    fun longTimelineStillParses() {
        val video = parse(mpd("PT10H", representation("v", timeline("""<S t="0" d="2" r="17999"/>""")))).first().video!!
        assertEquals(18_000, video.segments.size)
    }
}
