package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.ui.screens.StreamDownloadDialog
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.downloaders.web.streaming.manifest.dash.Representation
import xdm.core.downloaders.web.streaming.manifest.hls.HlsParser
import xdm.integration.AudioMode
import xdm.integration.StreamChoices
import xdm.integration.StreamPick
import xdm.integration.VideoHelper.LoadedManifest
import java.net.URI

/** The Stream download dialog's choices, built from parsed playlists and manifests. */
class StreamChoicesTest {
    private val base = "https://cdn.example.com/show/master.m3u8"

    private fun master(text: String) =
        LoadedManifest.HlsMaster(base, HlsParser.parseMasterPlaylist(text.trimIndent().lines().iterator(), base).getOrThrow())

    private fun media(text: String) =
        LoadedManifest.HlsMedia(base, HlsParser.parseMediaSegments(text.trimIndent().lines().iterator(), base).getOrThrow())

    @Test
    fun hlsMaster_groupsAudioRenditionsUnderEachVariant_bestFirst() {
        val formats = StreamChoices.of(
            master(
                """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Deutsch",LANGUAGE="de",URI="audio/de.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=854x480,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aud"
                v480/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud"
                v1080/index.m3u8
                """
            )
        )
        assertEquals(listOf("1080p · 5.2 Mbps · H.264, AAC", "480p · 1.4 Mbps · H.264, AAC"), formats.map { it.label }, "formats, highest bandwidth first")
        val audio = formats[0].audio
        assertEquals(AudioMode.CHOOSE, formats[0].audioMode, "separate renditions to choose from")
        assertEquals(listOf("English (en)", "Deutsch (de)"), audio.map { it.label }, "default rendition first")
        assertEquals(
            StreamPick.Hls("https://cdn.example.com/show/v1080/index.m3u8", "https://cdn.example.com/show/audio/en.m3u8", false, false),
            audio[0].pick, "variant and rendition resolved against the master"
        )
    }

    @Test
    fun hlsMaster_withoutAudioGroup_hasAudioIncluded() {
        val formats = StreamChoices.of(
            master(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
                low.m3u8
                """
            )
        )
        assertEquals(1, formats.size, "one format")
        assertEquals(AudioMode.INCLUDED, formats[0].audioMode, "audio is muxed into the variant")
        assertEquals(StreamPick.Hls("https://cdn.example.com/show/low.m3u8", null, false, false), formats[0].audio[0].pick, "the variant alone")
    }

    @Test
    fun hlsMediaPlaylist_isDownloadedAsItIs() {
        val manifest = media(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.0,
            seg0.ts
            #EXTINF:4.0,
            seg1.ts
            #EXT-X-ENDLIST
            """
        )
        val formats = StreamChoices.of(manifest)
        assertEquals(1, formats.size, "nothing to choose")
        assertEquals(StreamPick.Hls(base, null, false, false), formats[0].audio[0].pick, "the playlist itself")
    }

    @Test
    fun hlsSingleFileByteRanges_isARegularDownload() {
        val formats = StreamChoices.of(
            media(
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXTINF:4.0,
                #EXT-X-BYTERANGE:1000@0
                movie.mp4
                #EXTINF:4.0,
                #EXT-X-BYTERANGE:1000@1000
                movie.mp4
                #EXT-X-ENDLIST
                """
            )
        )
        assertEquals(StreamPick.Http("https://cdn.example.com/show/movie.mp4", "mp4"), formats[0].audio[0].pick, "one plain download of the file")
    }

    private fun rep(height: Int, bandwidth: Long, mime: String, codec: String, lang: String = "und") =
        Representation(0, height, codec, bandwidth, 0, listOf(DashSegment(URI("https://cdn.example.com/$mime/$bandwidth/0.m4s"))), mime, lang)

    @Test
    fun dash_crossProductBecomesFormatsWithAudioTracks() {
        val v720 = rep(720, 3_000_000, "video/mp4", "avc1.64001f")
        val v1080 = rep(1080, 6_000_000, "video/mp4", "avc1.640028")
        val en = rep(0, 128_000, "audio/mp4", "mp4a.40.2", "en")
        val de = rep(0, 96_000, "audio/mp4", "mp4a.40.2", "de")
        val formats = StreamChoices.dash(listOf(v720 to en, v720 to de, v1080 to en, v1080 to de))
        assertEquals(listOf("1080p · 6.0 Mbps · H.264", "720p · 3.0 Mbps · H.264"), formats.map { it.label }, "videos, tallest first")
        assertEquals(listOf("English (en) · AAC · 128 kbps", "German (de) · AAC · 96 kbps"), formats[0].audio.map { it.label }, "audio tracks")
        val pick = formats[0].audio[1].pick as StreamPick.Dash
        assertTrue(pick.video === v1080 && pick.audio === de, "the chosen pair")
        assertEquals("mp4", pick.extension, "mp4 tracks mux into mp4")
    }

    @Test
    fun dash_singleStream_isARegularDownloadWithoutAudioChoice() {
        val video = rep(720, 3_000_000, "video/webm", "vp09.00.40.08")
        val formats = StreamChoices.dash(listOf(video to null))
        assertEquals(AudioMode.NONE, formats[0].audioMode, "no audio to choose")
        assertEquals(StreamPick.Http(video.segments[0].toString(), "webm"), formats[0].audio[0].pick, "native container")
    }

    @Test
    fun copiedHeaders_skipPseudoAndUnsafeHeaders() {
        val parsed = StreamDownloadDialog.parseHeaderLines(
            """
            :authority: cdn.example.com
            Host: cdn.example.com
            Referer: https://example.com/watch
            -H 'User-Agent: Mozilla/5.0 (X11)' \
            Range: bytes=0-
            Cookie: a=1; b=2
            not a header
            """.trimIndent()
        )
        assertEquals(
            listOf("Referer" to "https://example.com/watch", "User-Agent" to "Mozilla/5.0 (X11)", "Cookie" to "a=1; b=2"),
            parsed, "only replayable headers"
        )
    }

    @Test
    fun duration_isMinutesOrHours() {
        assertEquals("12:34", StreamDownloadDialog.duration(754.2), "minutes and seconds")
        assertEquals("1:02:03", StreamDownloadDialog.duration(3723.0), "with hours")
    }
}
