package xdm.capture

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.downloaders.web.streaming.manifest.dash.Representation

/** The size-before-download rule (private-docs/docs/hls-size-estimate.md). */
class StreamSizeTest {
    private val finished = HlsMediaInfo(duration = 600.0, live = false, exactBytes = null)

    @Test
    fun hls_averageBandwidthBeatsBandwidth() {
        val size = StreamSizes.hlsVariant(mapOf("BANDWIDTH" to "2000000", "AVERAGE-BANDWIDTH" to "1600000"), finished, null, false, null)
        assertEquals(StreamSize.Kind.AVERAGE, size?.kind, "average bitrate used")
        assertEquals(120_000_000L, size?.bytes, "1.6 Mbit/s for 600 s")
    }

    @Test
    fun hls_bandwidthOnly_isRough() {
        val size = StreamSizes.hlsVariant(mapOf("BANDWIDTH" to "2000000"), finished, null, false, null)
        assertEquals(StreamSize.Kind.PEAK, size?.kind, "peak bitrate")
        assertEquals(150_000_000L, size?.bytes, "2 Mbit/s for 600 s")
    }

    @Test
    fun hls_sharedDuration_whenTheVariantItselfIsNotLoaded() {
        val size = StreamSizes.hlsVariant(mapOf("BANDWIDTH" to "800000"), null, null, false, 100.0)
        assertEquals(10_000_000L, size?.bytes, "another variant's duration")
        assertNull(StreamSizes.hlsVariant(mapOf("BANDWIDTH" to "800000"), null, null, false, null), "no duration, no size")
    }

    @Test
    fun hls_separateAudio_addsNothingToAnEstimate() {
        val audio = HlsMediaInfo(600.0, false, null)
        val size = StreamSizes.hlsVariant(mapOf("BANDWIDTH" to "2000000"), finished, audio, true, null)
        assertEquals(150_000_000L, size?.bytes, "BANDWIDTH covers video and audio together")
    }

    @Test
    fun hls_byteRanges_exact_onlyWithTheAudioKnownToo() {
        val video = HlsMediaInfo(600.0, false, 5000)
        val attrs = mapOf("BANDWIDTH" to "2000000")
        val alone = StreamSizes.hlsVariant(attrs, video, null, false, null)
        assertEquals(StreamSize.Kind.EXACT, alone?.kind, "muxed: exact")
        assertEquals(5000L, alone?.bytes, "sum of ranges")
        val audioMissing = StreamSizes.hlsVariant(attrs, video, null, true, null)
        assertEquals(StreamSize.Kind.PEAK, audioMissing?.kind, "audio not loaded yet: estimate")
        val both = StreamSizes.hlsVariant(attrs, video, HlsMediaInfo(600.0, false, 700), true, null)
        assertEquals(5700L, both?.bytes, "video + audio ranges")
        assertEquals(StreamSize.Kind.EXACT, both?.kind, "exact")
    }

    @Test
    fun hls_live() {
        val live = HlsMediaInfo(30.0, true, null)
        assertEquals(StreamSize.Kind.LIVE, StreamSizes.hlsVariant(mapOf("BANDWIDTH" to "1"), live, null, false, null)?.kind, "variant")
        assertEquals("Live", StreamSizes.hlsMedia(live)?.text, "media playlist")
        assertNull(StreamSize.LIVE.neededBytes, "nothing for the space check")
    }

    @Test
    fun hlsMedia_noBitrate_onlyExact() {
        assertNull(StreamSizes.hlsMedia(finished), "no ranges, no bitrate")
        assertEquals(StreamSize.Kind.EXACT, StreamSizes.hlsMedia(HlsMediaInfo(10.0, false, 99))?.kind, "ranges")
    }

    private fun rep(bandwidth: Long, durationMs: Long, vararg ranges: Pair<Long, Long>?) = Representation(
        1280, 720, "avc1", bandwidth, durationMs,
        ranges.map { DashSegment("https://h/v.mp4".toHttpUrl(), it) }, "video/mp4", "und",
    )

    @Test
    fun dash_bandwidthTimesDuration_bothTracks() {
        val size = StreamSizes.dash(rep(1_000_000, 80_000, null), rep(128_000, 80_000, null))
        assertEquals(StreamSize.Kind.PEAK, size?.kind, "DASH bandwidth is a bound")
        assertEquals(10_000_000L + 1_280_000L, size?.bytes, "video + audio")
    }

    @Test
    fun dash_rangesEverywhere_exact() {
        val size = StreamSizes.dash(rep(1, 1000, 0L to 100L, 100L to 900L), rep(1, 1000, 0L to 50L))
        assertEquals(StreamSize.Kind.EXACT, size?.kind, "every segment ranged")
        assertEquals(1050L, size?.bytes, "sum")
    }

    @Test
    fun text_marksTheAccuracy() {
        assertEquals("~ 1.5 KB", StreamSize(1500, StreamSize.Kind.PEAK).text.replace(',', '.'), "rough")
        assertEquals("≈ 1.5 KB", StreamSize(1500, StreamSize.Kind.AVERAGE).text.replace(',', '.'), "average")
    }
}
