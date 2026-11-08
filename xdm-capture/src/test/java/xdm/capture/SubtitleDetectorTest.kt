package xdm.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.core.media.subtitle.Subtitles

class SubtitleDetectorTest {
    private val fetched = ArrayList<String>()
    private val offered = ArrayList<CapturedSubtitle>()
    private val bodies = HashMap<String, String>()

    private val detector = SubtitleDetector(object : SubtitleDetector.Env {
        override fun execute(task: () -> Unit) = task()

        override fun fetch(url: String, msg: ExtensionMessage, maxBytes: Int): ByteArray? {
            fetched.add(url)
            return bodies[url]?.toByteArray()
        }

        override fun offer(subtitle: CapturedSubtitle) {
            offered.add(subtitle)
        }
    })

    private val file = "WEBVTT\n\n00:00:01.000 --> 00:42:10.000\nHello\n"
    private val segment = "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n00:00:01.000 --> 00:00:02.000\nHi\n"

    private fun msg(url: String, title: String? = "My Show Ep 1", tab: String = "7") =
        ExtensionMessage(url = url, tabId = tab, tabUrl = "https://site.example/watch", tabTitle = title)

    @BeforeEach
    fun setup() {
        FakeCaptureHost.install()
    }

    @Test
    fun wholeFile_isOfferedWithTitleLanguageAndLength() {
        val url = "https://cdn.example.com/subs/show_en.vtt"
        bodies[url] = file
        detector.onMediaMessage(msg(url))
        val s = offered.single()
        assertEquals("My Show Ep 1.en.vtt", s.task.fileName)
        assertEquals("en", s.language)
        assertEquals(Subtitles.Format.VTT, s.format)
        assertEquals("[VTT] en 00:42:10", s.display.descriptionText)
        assertEquals(1, s.task.maxPiece)
    }

    @Test
    fun bodyFromTheSender_isNotFetched() {
        detector.onMediaMessage(msg("https://cdn.example.com/a.vtt"), file)
        assertEquals(emptyList<String>(), fetched)
        assertEquals(1, offered.size)
    }

    @Test
    fun hlsSegments_areDroppedAndTheirFolderIsNotFetchedAgain() {
        val first = "https://cdn.example.com/hls/subs/seg1.vtt"
        bodies[first] = segment
        detector.onMediaMessage(msg(first))
        detector.onMediaMessage(msg("https://cdn.example.com/hls/subs/seg2.vtt"))
        assertEquals(listOf(first), fetched, "the second segment is known by its folder")
        assertEquals(emptyList<CapturedSubtitle>(), offered)
    }

    @Test
    fun sameUrlInTheSameTab_isLookedAtOnceUntilItNavigates() {
        val url = "https://cdn.example.com/a.vtt"
        bodies[url] = file
        detector.onMediaMessage(msg(url))
        detector.onMediaMessage(msg(url))
        assertEquals(1, fetched.size)
        detector.onTabNavigated("7")
        detector.onMediaMessage(msg(url))
        assertEquals(2, fetched.size)
    }

    @Test
    fun notASubtitle_isNotOffered() {
        assertNull(detector.process(msg("https://cdn.example.com/a.vtt"), "<html>404</html>"))
        assertNull(detector.process(msg("https://cdn.example.com/a.vtt"), "WEBVTT\n\n"))
    }

    @Test
    fun noTitle_usesTheUrlName() {
        val s = detector.process(msg("https://cdn.example.com/x/episode-3.de.vtt", title = null), file)!!
        assertEquals("episode-3.de.vtt", s.task.fileName)
        val t = detector.process(msg("https://cdn.example.com/x/show_en.vtt", title = null), file)!!
        assertEquals("show.en.vtt", t.task.fileName)
    }

    @Test
    fun languageFromUrl() {
        assertEquals("en", SubtitleDetector.languageFromUrl("https://c.example/s/movie_en.vtt"))
        assertEquals("pt-BR", SubtitleDetector.languageFromUrl("https://c.example/s/ep1.pt-BR.vtt"))
        assertEquals("pt-BR", SubtitleDetector.languageFromUrl("https://c.example/s/ep1_pt_br.vtt"))
        assertEquals("de", SubtitleDetector.languageFromUrl("https://c.example/subs/de/1.vtt"))
        assertEquals("fra", SubtitleDetector.languageFromUrl("https://c.example/sub?id=4&lang=fra"))
        assertEquals("zh-Hans", SubtitleDetector.languageFromUrl("https://c.example/s/a.zh-hans.vtt"))
        assertNull(SubtitleDetector.languageFromUrl("https://c.example/s/movie_hd.vtt"), "hd is no language")
        assertNull(SubtitleDetector.languageFromUrl("https://c.example/s/abc.vtt"), "three letters in a path")
    }

    @Test
    fun tracker_capsPerTabAndRenamesKeepingTheLanguage() {
        val tracker = CapturedVideoTracker()
        FakeCaptureHost.install(tracker)
        repeat(MAX_SUBTITLES_PER_TAB + 5) { i ->
            detector.process(msg("https://cdn.example.com/s/$i/en.vtt"), file)?.let { tracker.addSubtitle(it) }
        }
        assertEquals(MAX_SUBTITLES_PER_TAB, tracker.videoList.count { it.subtitle })
        tracker.updateMediaTitle("https://site.example/watch", "Renamed")
        assertEquals(setOf("Renamed.en.vtt"), tracker.videoList.map { it.name }.toSet())
        tracker.clearTab("7")
        assertEquals(0, tracker.videoList.size)
    }
}
