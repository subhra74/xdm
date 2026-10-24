package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xdm.core.downloaders.web.streaming.manifest.dash.MAX_SEGMENTS_PER_REPRESENTATION
import xdm.core.downloaders.web.streaming.manifest.dash.MpdParserException
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.ManifestUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manifests are fetched and parsed in the background for any page that plays media, so they are
 * hostile input: neither their size nor what they expand to may be left to the server.
 */
class TestManifestLimits {
    private val server = MockHttpServer()
    private val client = HttpClientImpl(2)

    @AfterEach
    fun tearDown() {
        client.close()
        server.stop()
    }

    private fun parse(mpd: String) = mpd.trimIndent().byteInputStream().use { parseMpdManifest(it, "https://example.com/v.mpd") }

    private fun template(attrs: String, duration: String = "PT100000H") = """
        <MPD type="static" mediaPresentationDuration="$duration" xmlns="urn:mpeg:dash:schema:mpd:2011">
          <Period>
            <AdaptationSet mimeType="video/mp4">
              <Representation id="v0" bandwidth="1000" codecs="avc1.42c00d">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" $attrs/>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """

    @Test
    fun templateExpandingToBillionsOfSegments_isRefused() {
        assertThrows<MpdParserException> { parse(template("""duration="1" timescale="1000"""")) }
    }

    @Test
    fun timelineRepeatingForever_isRefused() {
        val mpd = template("timescale=\"1\"").replace(
            "/>", "><SegmentTimeline><S t=\"0\" d=\"1\" r=\"9223372036854775807\"/></SegmentTimeline></SegmentTemplate>"
        )
        assertThrows<MpdParserException> { parse(mpd) }
    }

    @Test
    fun zeroOrNegativeDurations_yieldNoRepresentation() {
        assertNull(parse(template("""duration="0" timescale="1000"""")).firstOrNull()?.video)
        assertNull(parse(template("""duration="1000" timescale="-1"""")).firstOrNull()?.video)
    }

    @Test
    fun hugeNumberWidth_isCapped() {
        val mpd = template("""duration="1000" timescale="1000"""", duration = "PT2S")
            .replace("${'$'}Number${'$'}", "${'$'}Number%0999999999d${'$'}")
        val urls = parse(mpd).first().video!!.segments.map { it.toString() }
        assertTrue(urls.all { it.length < 200 }, "segment URLs should stay short, got ${urls.map { it.length }}")
    }

    @Test
    fun aLongButRealPresentation_stillParses() {
        // Ten hours of 2s segments.
        val video = parse(template("""duration="2000" timescale="1000"""", duration = "PT10H")).first().video!!
        assertEquals(18_000, video.segments.size)
        assertTrue(video.segments.size < MAX_SEGMENTS_PER_REPRESENTATION)
    }

    @Test
    fun oversizedManifest_isAbandoned() {
        server.register("/big.m3u8", Endpoint(ByteArray(64 * 1024) { 0x41 }))
        var error: Throwable? = null
        val bytes = ManifestUtils.downloadManifestBytes(
            client, server.url("/big.m3u8"), null, null, AtomicBoolean(false), onError = { error = it }, maxBytes = 1024,
        )
        assertNull(bytes)
        assertTrue(error is ManifestUtils.ManifestTooLargeException, "expected a size error, got $error")
    }

    @Test
    fun manifestWithinTheLimit_isRead() {
        val body = "#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray()
        server.register("/ok.m3u8", Endpoint(body))
        val bytes = ManifestUtils.downloadManifestBytes(client, server.url("/ok.m3u8"), null, null, AtomicBoolean(false), maxBytes = 1024)
        assertEquals(String(body), bytes?.let { String(it) })
    }
}
