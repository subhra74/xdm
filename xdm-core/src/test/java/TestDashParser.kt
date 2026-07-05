import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.web.streaming.manifest.dash.MpdEntry
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest

class TestDashParser {

    private fun parse(mpd: String, url: String = "http://host/path/manifest.mpd"): List<MpdEntry> =
        mpd.trimIndent().byteInputStream().use { parseMpdManifest(it, url) }

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
}
