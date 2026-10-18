package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.headless.CliOptions
import xdm.app.headless.CliParser
import xdm.app.headless.CliUsageException
import xdm.app.headless.FormatSelector
import xdm.app.headless.HeadlessCli
import xdm.app.headless.ParsedArgs
import xdm.app.headless.Quality
import xdm.app.headless.StreamType
import xdm.integration.AudioChoice
import xdm.integration.AudioMode
import xdm.integration.FormatChoice
import xdm.integration.StreamPick

class HeadlessCliTest {
    private fun parse(vararg args: String): CliOptions =
        (CliParser.parse(arrayOf("--headless", *args)) as ParsedArgs.Run).options

    @Test
    fun headlessFlagIsDetected() {
        assertTrue(CliParser.isHeadless(arrayOf("--no-gui", "x")), "--no-gui")
        assertTrue(CliParser.isHeadless(arrayOf("x", "--headless")), "--headless anywhere")
        assertFalse(CliParser.isHeadless(arrayOf("--minimized")), "normal launch")
    }

    @Test
    fun parsesValuesInBothForms() {
        val o = parse("-o", "/tmp/d", "--quality=720p", "-s", "4", "--speed-limit=100", "https://a/b.mp4")
        assertEquals("/tmp/d", o.outputDir, "output dir")
        assertEquals(Quality.Height(720), o.quality, "quality")
        assertEquals(4, o.segments, "segments")
        assertEquals(100, o.speedLimit, "speed limit")
        assertEquals(listOf("https://a/b.mp4"), o.urls, "urls")
        assertEquals(StreamType.Auto, o.type, "type defaults to auto")
    }

    @Test
    fun collectsHeadersAndCookies() {
        val o = parse(
            "-o", "d", "-H", "X-A: 1", "-H", "X-A: 2", "-H", "Cookie: k=v", "-r", "https://ref/", "https://a/b"
        )
        assertEquals(listOf("1", "2"), o.headers["X-A"], "repeated header")
        assertEquals(listOf("https://ref/"), o.headers["Referer"], "referer")
        assertEquals(CliParser.DEFAULT_USER_AGENT, o.headers["User-Agent"]?.single(), "default user agent")
        assertEquals("k=v", o.cookie, "Cookie header becomes the cookie")
        assertNull(o.headers["Cookie"], "Cookie is not sent twice")
    }

    @Test
    fun splitsUserAndPassword() {
        val o = parse("-o", "d", "-u", "me:secret:x", "https://a/b")
        assertEquals("me", o.user, "user")
        assertEquals("secret:x", o.password, "password keeps later colons")
    }

    @Test
    fun rejectsBadCommandLines() {
        assertThrows(CliUsageException::class.java, { parse("https://a/b") }, "no folder")
        assertThrows(CliUsageException::class.java, { parse("-o", "d") }, "no url")
        assertThrows(CliUsageException::class.java, { parse("-o", "d", "ftp://a/b") }, "not http")
        assertThrows(CliUsageException::class.java, { parse("-o", "d", "--nope", "https://a/b") }, "unknown option")
        assertThrows(CliUsageException::class.java, { parse("-o", "d", "-q", "great", "https://a/b") }, "bad quality")
        assertThrows(CliUsageException::class.java, { parse("-o", "d", "-f", "x", "https://a", "https://b") }, "name with 2 urls")
        assertThrows(CliUsageException::class.java, { parse("-o", "d", "--proxy", "ftp://h:1", "https://a") }, "bad proxy")
        assertThrows(CliUsageException::class.java, { parse("-o") }, "missing value")
    }

    @Test
    fun listFormatsNeedsNoFolderAndHelpWins() {
        assertTrue(parse("-l", "https://a/m.m3u8").listFormats, "list without -o")
        assertEquals(ParsedArgs.Help, CliParser.parse(arrayOf("--headless", "--help")), "help alone")
        assertEquals(ParsedArgs.Help, CliParser.parse(arrayOf("--headless", "-h", "https://a/b")), "help skips checks")
    }

    @Test
    fun parsesProxy() {
        assertEquals(Triple(true, "h", 1080), CliParser.parseProxy("socks5://h:1080"), "socks")
        assertEquals(Triple(false, "proxy.local", 3128), CliParser.parseProxy("http://proxy.local:3128/"), "http")
    }

    @Test
    fun detectsTypeFromUrl() {
        assertEquals(StreamType.Hls, HeadlessCli.detectType("https://a/x/master.m3u8?t=1"), "hls")
        assertEquals(StreamType.Dash, HeadlessCli.detectType("https://a/manifest.mpd"), "dash")
        assertEquals(StreamType.Hls, HeadlessCli.detectType("https://a/proxy?u=https%3A//b/v.m3u8"), "hls in query")
        assertEquals(StreamType.Http, HeadlessCli.detectType("https://a/file.zip"), "http")
    }

    @Test
    fun suggestsStreamNames() {
        assertEquals("show.mp4", HeadlessCli.suggestStreamName("https://a/videos/show/master.m3u8", "mp4"), "generic name")
        assertEquals("ep1.mkv", HeadlessCli.suggestStreamName("https://a/ep1.mpd", "mkv"), "manifest name")
        assertEquals("a.example.mp4", HeadlessCli.suggestStreamName("https://a.example/index.m3u8", "mp4"), "host")
    }

    private fun format(label: String, height: Int, audioOnly: Boolean = false, audio: List<String> = listOf("")) =
        FormatChoice(
            label, if (audio.size > 1) AudioMode.CHOOSE else AudioMode.INCLUDED,
            audio.map { AudioChoice(it, StreamPick.Http("https://a/$label/$it", "mp4")) }, audioOnly, height,
        )

    private val formats = listOf(
        format("1080", 1080), format("720", 720), format("720-low", 720), format("360", 360),
        format("audio", 0, audioOnly = true),
    )

    @Test
    fun selectsFormats() {
        assertEquals("1080", FormatSelector.select(formats, Quality.Best).first.label, "best")
        assertEquals("360", FormatSelector.select(formats, Quality.Worst).first.label, "worst skips audio-only")
        assertEquals("audio", FormatSelector.select(formats, Quality.AudioOnly).first.label, "audio")
        assertEquals("720", FormatSelector.select(formats, Quality.Height(720)).first.label, "exact, best of that height")
        assertEquals("720-low", FormatSelector.select(formats, Quality.Index(3)).first.label, "index")

        val (below, note) = FormatSelector.select(formats, Quality.Height(480))
        assertEquals("360", below.label, "nearest lower height")
        assertEquals("no 480p, took 360p", note, "note")
        assertEquals("360", FormatSelector.select(formats, Quality.Height(240)).first.label, "lowest when none below")
        assertEquals("1080", FormatSelector.select(formats, Quality.Height(2160)).first.label, "highest below 2160")
        assertThrows(CliUsageException::class.java, { FormatSelector.select(formats, Quality.Index(9)) }, "index out of range")
    }

    @Test
    fun selectsAudio() {
        val f = format("v", 720, audio = listOf("English (en)", "Deutsch (de)"))
        assertEquals("English (en)", FormatSelector.selectAudio(f, null).label, "default first")
        assertEquals("Deutsch (de)", FormatSelector.selectAudio(f, "de)").label, "by text")
        assertEquals("English (en)", FormatSelector.selectAudio(f, "fr").label, "no match falls back")
    }
}
