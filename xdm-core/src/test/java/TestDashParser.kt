import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.web.streaming.manifest.dash.MpdEntry
import xdm.core.downloaders.web.streaming.manifest.dash.XlinkResolver
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest

class TestDashParser {

    private fun parse(
        mpd: String,
        url: String = "http://host/path/manifest.mpd",
        resolver: XlinkResolver? = null
    ): List<MpdEntry> =
        mpd.trimIndent().byteInputStream().use { parseMpdManifest(it, url, resolver) }

    @Test
    fun test1(){
        javaClass.getResourceAsStream("/mpd1").use {
            println(parseMpdManifest(it!!, "http://a/b/c"))
        }
    }

    /**
     * A BaseURL nested inside the audio AdaptationSet must not leak up and become the base for
     * the video track (which has no BaseURL of its own). Video segments must resolve against the
     * document URL. Regression for the Bitmovin AV1 demo returning 403 on every video segment.
     */
    @Test
    fun nestedBaseUrlDoesNotLeakToOtherAdaptationSets() {
        val docUrl = "https://storage.googleapis.com/bitmovin-demos/av1/stream_chrome.mpd"
        val entries = javaClass.getResourceAsStream("/mpd-nested-baseurl.mpd").use {
            parseMpdManifest(it!!, docUrl)
        }
        val entry = entries.first { it.video != null }
        val video = entry.video!!
        val audio = entry.audio!!

        val videoUrls = video.segments.map { it.toString() }
        val audioUrls = audio.segments.map { it.toString() }

        // Video resolves against the document URL, NOT the audio AdaptationSet's BaseURL.
        assertTrue(
            "video segments should resolve against the document base, got $videoUrls",
            videoUrls.all { it.startsWith("https://storage.googleapis.com/bitmovin-demos/av1/AV1_480/video/480/") }
        )
        assertTrue(
            "video segments must not leak the audio BaseURL host, got $videoUrls",
            videoUrls.none { it.contains("audio.example.com") }
        )
        assertNotNull(videoUrls.firstOrNull { it.endsWith("/AV1_480/video/480/segment_0.chk") })

        // Audio still resolves against its own BaseURL.
        assertTrue(
            "audio segments should resolve against the audio BaseURL, got $audioUrls",
            audioUrls.all { it.startsWith("https://audio.example.com/mozillaAV1/base_v1/audio/") }
        )
    }

    /** `$$` is the DASH escape for a literal `$`; it must not leave a stray space behind. */
    @Test
    fun escapedDollarBecomesLiteralDollar() {
        val entries = parse(
            """
            <MPD type="static" mediaPresentationDuration="PT2S" xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v0" bandwidth="1000" codecs="avc1.42c00d">
                    <SegmentTemplate media="seg-${'$'}${'$'}-${'$'}Number${'$'}.m4s" duration="1000" timescale="1000" startNumber="0"/>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
            """
        )
        val urls = entries.first().video!!.segments.map { it.toString() }
        assertTrue("expected a literal \$ with no stray space, got $urls", urls.any { it.endsWith("/seg-\$-0.m4s") })
        assertTrue("no segment URL should contain a space, got $urls", urls.none { it.contains(" ") })
    }

    /** With `$Time$` simple addressing, presentation time starts at 0, not startNumber. */
    @Test
    fun simpleTimeAddressingStartsAtZero() {
        val entries = parse(
            """
            <MPD type="static" mediaPresentationDuration="PT3S" xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v0" bandwidth="1000" codecs="avc1.42c00d">
                    <SegmentTemplate media="seg-${'$'}Time${'$'}.m4s" duration="1000" timescale="1000" startNumber="1"/>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
            """
        )
        val urls = entries.first().video!!.segments.map { it.toString().substringAfterLast('/') }
        assertEquals(listOf("seg-0.m4s", "seg-1000.m4s", "seg-2000.m4s"), urls)
    }

    /** Explicit SegmentTimeline: t="0" is honored and r="-1" repeats to the end of the period. */
    @Test
    fun explicitTimelineHonorsZeroTimeAndRepeatToEnd() {
        val entries = parse(
            """
            <MPD type="static" mediaPresentationDuration="PT5S" xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v0" bandwidth="1000" codecs="avc1.42c00d">
                    <SegmentTemplate media="seg-${'$'}Time${'$'}.m4s" timescale="1">
                      <SegmentTimeline>
                        <S t="0" d="1" r="-1"/>
                      </SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
            """
        )
        val urls = entries.first().video!!.segments.map { it.toString().substringAfterLast('/') }
        // 5s period, 1-tick segments -> times 0,1,2,3,4.
        assertEquals(listOf("seg-0.m4s", "seg-1.m4s", "seg-2.m4s", "seg-3.m4s", "seg-4.m4s"), urls)
    }

    /** Multi-period: a period with only @duration and one with only @start both resolve correctly. */
    @Test
    fun multiPeriodDurationsResolve() {
        val entries = parse(
            """
            <MPD type="static" mediaPresentationDuration="PT10S" xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period duration="PT4S">
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v0" bandwidth="1000" codecs="avc1.42c00d">
                    <SegmentTemplate media="p1-${'$'}Number${'$'}.m4s" duration="1000" timescale="1000" startNumber="0"/>
                  </Representation>
                </AdaptationSet>
              </Period>
              <Period start="PT4S">
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v0" bandwidth="1000" codecs="avc1.42c00d">
                    <SegmentTemplate media="p2-${'$'}Number${'$'}.m4s" duration="1000" timescale="1000" startNumber="0"/>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
            """
        )
        val durations = entries.mapNotNull { it.video?.duration }.toSet()
        // Period 1 duration = 4s; period 2 duration = 10s - 4s = 6s.
        assertTrue("expected period durations 4000 and 6000, got $durations", durations.containsAll(listOf(4000L, 6000L)))
        // Segment counts follow from those durations (4 and 6 one-second segments).
        val p1 = entries.first { it.video!!.segments.first().toString().contains("p1-") }.video!!
        val p2 = entries.first { it.video!!.segments.first().toString().contains("p2-") }.video!!
        assertEquals(4, p1.segments.size)
        assertEquals(6, p2.segments.size)
    }

    private val remotePeriod =
        """
        <Period xmlns="urn:mpeg:dash:schema:mpd:2011">
          <AdaptationSet mimeType="video/mp4">
            <Representation id="v0" bandwidth="1000" codecs="avc1.42c00d">
              <SegmentTemplate media="remote-${'$'}Number${'$'}.m4s" duration="1000" timescale="1000" startNumber="0"/>
            </Representation>
          </AdaptationSet>
        </Period>
        """.trimIndent()

    /** A remote (xlink) Period is fetched via the resolver and spliced in place of the placeholder. */
    @Test
    fun xlinkPeriodIsResolvedAndSpliced() {
        val mpd = """
            <MPD type="static" mediaPresentationDuration="PT2S" xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:xlink="http://www.w3.org/1999/xlink">
              <Period xlink:href="https://cdn.example.com/ad.period" xlink:actuate="onLoad"/>
            </MPD>
        """
        val entries = parse(mpd, resolver = XlinkResolver { url ->
            if (url == "https://cdn.example.com/ad.period") remotePeriod else null
        })
        val urls = entries.first().video!!.segments.map { it.toString().substringAfterLast('/') }
        assertEquals(listOf("remote-0.m4s", "remote-1.m4s"), urls)
    }

    /** A relative xlink href resolves against the document base before being fetched. */
    @Test
    fun xlinkHrefResolvesAgainstDocumentBase() {
        val requested = arrayOfNulls<String>(1)
        val mpd = """
            <MPD type="static" mediaPresentationDuration="PT2S" xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:xlink="http://www.w3.org/1999/xlink">
              <Period xlink:href="ads/ad.period"/>
            </MPD>
        """
        parse(mpd, resolver = XlinkResolver { url -> requested[0] = url; remotePeriod })
        assertEquals("http://host/path/ads/ad.period", requested[0])
    }

    /** The resolve-to-zero sentinel removes the placeholder without any fetch. */
    @Test
    fun xlinkResolveToZeroRemovesPeriod() {
        val mpd = """
            <MPD type="static" mediaPresentationDuration="PT4S" xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:xlink="http://www.w3.org/1999/xlink">
              <Period xlink:href="urn:mpeg:dash:resolve-to-zero:2013"/>
              <Period duration="PT2S">
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v0" bandwidth="1000" codecs="avc1.42c00d">
                    <SegmentTemplate media="local-${'$'}Number${'$'}.m4s" duration="1000" timescale="1000" startNumber="0"/>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """
        val entries = parse(mpd, resolver = XlinkResolver { error("resolve-to-zero must not fetch") })
        // Only the local period survives.
        val urls = entries.map { it.video!!.segments.first().toString().substringAfterLast('/') }
        assertTrue("resolve-to-zero period should be gone, got $urls", urls.all { it.startsWith("local-") })
    }

    /** With no resolver, an xlink placeholder is dropped (not an error) and inline periods still parse. */
    @Test
    fun xlinkWithoutResolverDropsPlaceholder() {
        val mpd = """
            <MPD type="static" mediaPresentationDuration="PT2S" xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:xlink="http://www.w3.org/1999/xlink">
              <Period xlink:href="https://cdn.example.com/ad.period"/>
              <Period duration="PT2S">
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v0" bandwidth="1000" codecs="avc1.42c00d">
                    <SegmentTemplate media="local-${'$'}Number${'$'}.m4s" duration="1000" timescale="1000" startNumber="0"/>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """
        val entries = parse(mpd)
        val urls = entries.map { it.video!!.segments.first().toString().substringAfterLast('/') }
        assertEquals(listOf("local-0.m4s"), urls.map { it })
    }

    /** A remote entity may carry an XML prolog and several top-level elements; all are spliced in. */
    @Test
    fun xlinkRemoteEntityWithPrologAndMultipleElements() {
        val twoPeriods = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Period xmlns="urn:mpeg:dash:schema:mpd:2011" duration="PT1S">
              <AdaptationSet mimeType="video/mp4">
                <Representation id="a" bandwidth="1000" codecs="avc1.42c00d">
                  <SegmentTemplate media="a-${'$'}Number${'$'}.m4s" duration="1000" timescale="1000" startNumber="0"/>
                </Representation>
              </AdaptationSet>
            </Period>
            <Period xmlns="urn:mpeg:dash:schema:mpd:2011" duration="PT1S">
              <AdaptationSet mimeType="video/mp4">
                <Representation id="b" bandwidth="1000" codecs="avc1.42c00d">
                  <SegmentTemplate media="b-${'$'}Number${'$'}.m4s" duration="1000" timescale="1000" startNumber="0"/>
                </Representation>
              </AdaptationSet>
            </Period>
        """.trimIndent()
        val mpd = """
            <MPD type="static" mediaPresentationDuration="PT2S" xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:xlink="http://www.w3.org/1999/xlink">
              <Period xlink:href="https://cdn.example.com/ads"/>
            </MPD>
        """
        val entries = parse(mpd, resolver = XlinkResolver { twoPeriods })
        val prefixes = entries.map { it.video!!.segments.first().toString().substringAfterLast('/').substringBefore('-') }
        assertTrue("expected both remote periods spliced, got $prefixes", prefixes.containsAll(listOf("a", "b")))
    }
}
