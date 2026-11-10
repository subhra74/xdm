package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.media.FFmpegMuxer
import xdm.app.ytdlp.SaveAs
import xdm.app.ytdlp.VideoChoice
import xdm.app.ytdlp.YtDlp
import xdm.app.ytdlp.YtDlpChoices
import xdm.app.ytdlp.YtDlpException
import xdm.app.ytdlp.YtDlpTask
import xdm.app.ytdlp.YtDlpTasks
import xdm.app.ytdlp.cookieHeaderOf

/** Video download: reading `yt-dlp -J`, the quality list, the downloads it becomes, the commands. */
class YtDlpFormatsTest {
    private fun fmt(
        id: String, vcodec: String, acodec: String, ext: String, height: Int? = null, tbr: Double = 100.0,
        size: Long? = 25_000_000, protocol: String = "https", chunk: Boolean = true, fps: Int = 30,
        extra: String = "",
    ) = """{"format_id":"$id","protocol":"$protocol","url":"https://cdn.example/v?id=$id","ext":"$ext",
        "vcodec":"$vcodec","acodec":"$acodec",${height?.let { "\"height\":$it," } ?: ""}"fps":$fps,"tbr":$tbr,
        ${size?.let { "\"filesize\":$it," } ?: ""}
        ${if (chunk) "\"downloader_options\":{\"http_chunk_size\":10485760}," else ""}
        "http_headers":{"User-Agent":"UA/1","Accept":"*/*","Cookie":"leak=1"}$extra}"""

    private val page = """{"id":"abc","title":"A: talk / part 1","webpage_url":"https://video.example/watch?v=abc",
        "extractor_key":"Example","duration":600,"formats":[
        ${fmt("18", "avc1.42001E", "mp4a.40.2", "mp4", height = 360, chunk = false)},
        ${fmt("140", "none", "mp4a.40.2", "m4a", tbr = 129.0, size = 9_000_000, extra = ",\"abr\":129")},
        ${fmt("251", "none", "opus", "webm", tbr = 140.0, size = 9_500_000, extra = ",\"abr\":140")},
        ${fmt("137", "avc1.640028", "none", "mp4", height = 1080, tbr = 4000.0)},
        ${fmt("248", "vp9", "none", "webm", height = 1080, tbr = 3000.0)},
        ${fmt("136", "avc1.4d401f", "none", "mp4", height = 720, tbr = 2000.0)},
        ${fmt("drm", "avc1", "none", "mp4", height = 2160, extra = ",\"has_drm\":true")},
        ${fmt("sb0", "none", "none", "mhtml", protocol = "mhtml")}
        ]}"""

    @Test
    fun parsesFormats() {
        val info = YtDlp.parse(page)
        assertEquals("A: talk / part 1", info.title)
        assertEquals("https://video.example/watch?v=abc", info.webpageUrl)
        assertEquals(8, info.formats.size)
        val f137 = info.formats.first { it.id == "137" }
        assertEquals(10_485_760L, f137.chunkSize)
        assertTrue(f137.hasVideo && !f137.hasAudio)
        assertFalse(info.formats.first { it.id == "drm" }.supported, "DRM formats are never offered")
        assertFalse(info.formats.first { it.id == "sb0" }.supported, "storyboards are not media")
    }

    @Test
    fun qualityListPairsTracksBestFirst() {
        val choices = YtDlpChoices.of(YtDlp.parse(page))
        val pairs = choices.filterIsInstance<VideoChoice.Pair>()
        assertEquals(listOf("137+140", "248+251", "136+140"), pairs.map { it.formatIds.joinToString("+") })
        assertEquals(listOf("mp4", "webm", "mp4"), pairs.map { it.container }, "same-family audio, yt-dlp's merge container")
        val singles = choices.filterIsInstance<VideoChoice.Single>()
        assertEquals(listOf("18", "251", "140"), singles.map { it.format.id }, "muxed file, then audio by bitrate")
        assertTrue(singles.drop(1).all { it.audioOnly })
        assertEquals("1080p · H.264 + AAC · mp4", pairs[0].label)
        assertEquals(34_000_000L, pairs[0].size)
    }

    @Test
    fun liveStreamsAreNotOffered() {
        val formats = fmt("hv", "avc1", "mp4a.40.2", "mp4", height = 720, protocol = "m3u8_native")
        for (live in listOf("\"is_live\":true", "\"live_status\":\"is_upcoming\"", "\"live_status\":\"post_live\"")) {
            val info = YtDlp.parse("""{"title":"t","webpage_url":"https://p",$live,"formats":[$formats]}""")
            assertTrue(info.isLive, live)
            assertTrue(YtDlpChoices.of(info).isEmpty(), live)
        }
        assertFalse(YtDlp.parse("""{"title":"t","live_status":"was_live","formats":[$formats]}""").isLive,
            "a finished live stream is an ordinary video")
    }

    @Test
    fun mixedContainersMergeIntoMkv() {
        val info = YtDlp.parse("""{"title":"t","webpage_url":"https://p","formats":[
            ${fmt("v", "vp9", "none", "webm", height = 480)}, ${fmt("a", "none", "mp4a.40.2", "m4a")}]}""")
        val pair = YtDlpChoices.of(info).first() as VideoChoice.Pair
        assertEquals("mkv", pair.container)
    }

    private val save = SaveAs("x.mp4", "/dl", "video", 8, "/tmp")

    @Test
    fun pairBecomesRangedDashWithFfmpegSource() {
        val info = YtDlp.parse(page)
        val pair = YtDlpChoices.of(info).first()
        val task = (YtDlpTasks.build(info, pair, save) as YtDlpTask.Dash).task
        // 25 MB in 10 MiB pieces: 10 MiB, 10 MiB, the rest; (offset, length) like the DASH parsers.
        assertEquals(listOf(0L to 10_485_760L, 10_485_760L to 10_485_760L, 20_971_520L to 4_028_480L),
            task.videoSegments.map { it.range })
        assertEquals(1, task.audioSegments.size, "9 MB of audio is one piece")
        assertEquals(9_000_000L, task.audioSegments[0].range!!.second)
        val src = task.extractor!!
        assertEquals("yt-dlp", src.extractor)
        assertEquals(listOf("137", "140"), src.formatIds)
        assertEquals("mp4", src.container)
        assertEquals("https://video.example/watch?v=abc", src.pageUrl)
        assertEquals(listOf("UA/1"), task.headers!!["User-Agent"])
        assertNull(task.headers!!["Cookie"], "a Cookie header from yt-dlp's headers is never replayed")
    }

    @Test
    fun plainFileWithoutLimitIsOneHttpDownload() {
        val info = YtDlp.parse(page)
        val muxed = YtDlpChoices.of(info).first { it.formatIds == listOf("18") }
        val task = (YtDlpTasks.build(info, muxed, save) as YtDlpTask.Http).task
        assertEquals("https://cdn.example/v?id=18", task.url)
        assertEquals(25_000_000L, task.knownFileSize)
        assertEquals(8, task.maxPiece)
        assertEquals(listOf("18"), task.extractor!!.formatIds)
    }

    @Test
    fun unknownSizeUsesProbedSizeOrOnePiece() {
        val info = YtDlp.parse("""{"title":"t","webpage_url":"https://p","formats":[
            ${fmt("v", "avc1", "none", "mp4", height = 720, size = null)}, ${fmt("a", "none", "mp4a.40.2", "m4a", size = null)}]}""")
        val pair = YtDlpChoices.of(info).first()
        val probed = (YtDlpTasks.build(info, pair, save, mapOf("v" to 15_000_000L)) as YtDlpTask.Dash).task
        assertEquals(2, probed.videoSegments.size)
        assertNull(probed.audioSegments.single().range, "no size known: the whole file in one request")
    }

    @Test
    fun hlsPairKeepsBothPlaylists() {
        val info = YtDlp.parse("""{"title":"t","webpage_url":"https://p","formats":[
            ${fmt("hv", "avc1", "none", "mp4", height = 720, protocol = "m3u8_native")},
            ${fmt("ha", "none", "mp4a.40.2", "mp4", protocol = "m3u8_native")}]}""")
        val task = (YtDlpTasks.build(info, YtDlpChoices.of(info).first(), save) as YtDlpTask.Hls).task
        assertEquals("https://cdn.example/v?id=hv", task.url)
        assertEquals("https://cdn.example/v?id=ha", task.audioUrl)
        assertEquals(listOf("hv", "ha"), task.extractor!!.formatIds)
    }

    @Test
    fun dashFragmentsResolveAgainstTheBase() {
        val info = YtDlp.parse("""{"title":"t","webpage_url":"https://p","formats":[
            {"format_id":"d","protocol":"http_dash_segments","ext":"mp4","vcodec":"avc1","acodec":"mp4a.40.2",
             "height":480,"fragment_base_url":"https://cdn.example/seg/",
             "fragments":[{"path":"init.mp4"},{"path":"1.m4s"},{"url":"https://other.example/2.m4s"}]}]}""")
        val task = (YtDlpTasks.build(info, YtDlpChoices.of(info).first(), save) as YtDlpTask.Dash).task
        assertEquals(listOf("https://cdn.example/seg/init.mp4", "https://cdn.example/seg/1.m4s", "https://other.example/2.m4s"),
            task.videoSegments.map { it.url.toString() })
    }

    @Test
    fun cookiesFieldBecomesCookieHeader() {
        assertEquals("a=1; b=x y",
            cookieHeaderOf("a=1; Domain=.example.com; Path=/; Secure; Expires=1794271941; b=x y; Domain=.example.com"))
        assertNull(cookieHeaderOf("Domain=.example.com; Path=/"))
    }

    @Test
    fun commandNeverReadsUrlAsOption() {
        val cmd = YtDlp.command("/t/yt-dlp", "/t/deno", "--js-runtimes \"node\"", "-https://evil", listOf("137", "140"))
        assertEquals(listOf("/t/yt-dlp", "-J", "--no-playlist", "--ignore-config", "--no-progress",
            "--js-runtimes", "deno:/t/deno", "-f", "137+140", "--js-runtimes", "node", "--", "-https://evil"), cmd)
        assertFalse(cmd.any { it.startsWith("--cookies") }, "no browser cookies")
    }

    @Test
    fun errorsReadYtDlpsReason() {
        val e = YtDlpException("exit 1", "WARNING: x\nERROR: [Example] abc: Unable to extract player response\n")
        assertEquals("[Example] abc: Unable to extract player response", e.reason)
        assertTrue(e.looksOutdated)
        assertFalse(YtDlpException("exit 1", "ERROR: Private video. Sign in").looksOutdated)
        val unsupported = YtDlpException("exit 1", "WARNING: [generic] Falling back\nERROR: Unsupported URL: https://example.com/")
        assertTrue(unsupported.unsupportedUrl)
        assertFalse(unsupported.looksOutdated, "an unsupported site offers an update only when one exists")
        assertFalse(YtDlpException("exit 1", "ERROR: Private video. Sign in").unsupportedUrl)
    }

    @Test
    fun ffmpegCommandCopiesIntoChosenContainer() {
        assertEquals(listOf("ff", "-hide_banner", "-nostdin", "-y", "-i", "concatf:/t/v.txt", "-i", "concatf:/t/a.txt",
            "-map", "0", "-map", "1", "-c", "copy", "-movflags", "+faststart", "-f", "mp4", "/o/x.mkv"),
            FFmpegMuxer.command("ff", listOf("concatf:/t/v.txt", "concatf:/t/a.txt"), "/o/x.mkv", "mp4"))
        assertEquals(listOf("ff", "-hide_banner", "-nostdin", "-y", "-i", "a", "-map", "0", "-c", "copy", "-f", "webm", "o"),
            FFmpegMuxer.command("ff", listOf("a"), "o", "webm"))
        assertEquals("matroska", FFmpegMuxer.formatName("mkv"))
        assertNull(FFmpegMuxer.formatName("xdm-part"), "a part file's extension: the muxer falls back to mp4")
        assertEquals(3723.5, FFmpegMuxer.parseTime(Regex("time=\\s*(\\d+):(\\d\\d):(\\d\\d(?:\\.\\d+)?)"),
            "frame= 1 fps=0 time=01:02:03.50 bitrate=1"))
    }
}
