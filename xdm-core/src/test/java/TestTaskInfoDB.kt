import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.http.HttpTaskContext
import xdm.core.downloaders.web.loadHlsState
import xdm.core.downloaders.web.loadState
import xdm.core.downloaders.web.saveState
import xdm.core.downloaders.web.streaming.downloader.HlsTaskContext
import xdm.core.downloaders.web.streaming.downloader.StreamingChunk
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.network.http.HeaderMap
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression for CODE_REVIEW B3 and the 64 KB `writeUTF` limit (B21) in download persistence.
 *
 * - `TaskInfoDB` must persist cookie, headers, origin and the user-selected folder for HTTP, HLS and
 *   DASH, so queued / "download later" tasks (built from `task-<id>.info` with no `.state` yet) keep
 *   their auth, and load-modify-save paths such as "Refresh link" do not wipe them.
 * - Strings in `.info` and `.state` files must survive values larger than 64 KB (big cookies,
 *   long signed URLs); `DataOutputStream.writeUTF` throws above that.
 */
class TestTaskInfoDB : HttpDownloadTestBase() {

    private val headers: HeaderMap = mapOf(
        "Referer" to listOf("https://example.com/watch?v=1"),
        "X-Token" to listOf("abc", "def"),
    )

    /** A string whose modified-UTF-8 encoding is well over 64 KB (includes multi-byte chars). */
    private fun big(prefix: String) = prefix + "é".repeat(40_000) + "x".repeat(10_000)

    private fun db() = TaskInfoDB(work.absolutePath)

    private fun httpTask(id: Long, url: String = "https://example.com/file.bin") = HttpDownloadTaskInfo(
        id = id, url = url, fileName = "file.bin", respectFileName = true,
        cookie = "session=s3cr3t; theme=dark", headers = headers, origin = "https://example.com/page",
        autoCategorize = true, defaultDownloadFolder = "/downloads", userSelectedDownloadFolder = "/chosen",
        maxPiece = 4, authInfo = null, knownFileSize = 1234L,
    )

    @Test
    fun http_roundTripKeepsCookieHeadersOriginAndFolder() {
        val task = httpTask(1)
        db().saveHttpTask(task)
        assertEquals(task, db().getHttpTask(1))
    }

    @Test
    fun hls_roundTripKeepsCookieHeadersOriginAndFolder() {
        val task = HlsDownloadTaskInfo(
            id = 2, fileName = "v.mp4", tempDir = "/tmp/v", respectFileName = false,
            cookie = "cf=1", headers = headers, origin = "https://example.com/hls",
            autoCategorize = false, defaultDownloadFolder = "/downloads", userSelectedDownloadFolder = "/chosen",
            maxPiece = 0, authInfo = null, url = "https://cdn.example.com/master.m3u8",
            audioUrl = "https://cdn.example.com/audio.m3u8", audioOnly = false, independent = true,
        )
        db().saveHlsTask(task)
        assertEquals(task, db().getHlsTask(2))
    }

    @Test
    fun dash_roundTripKeepsCookieHeadersOriginAndFolder() {
        val task = DashDownloadTaskInfo(
            id = 3, fileName = "d.mp4", tempDir = "/tmp/d", respectFileName = false,
            cookie = "cf=2", headers = headers, origin = "https://example.com/dash",
            autoCategorize = false, defaultDownloadFolder = "/downloads", userSelectedDownloadFolder = null,
            maxPiece = 0, authInfo = null,
            videoSegments = listOf(DashSegment(URI("https://cdn.example.com/v1.m4s"), Pair(0L, 99L))),
            audioSegments = listOf(DashSegment(URI("https://cdn.example.com/a1.m4s"), null)),
            url = "https://cdn.example.com/manifest.mpd", audioMime = "audio/mp4", videoMime = "video/mp4",
        )
        db().saveDashTask(task)
        assertEquals(task, db().getDashTask(3))
    }

    @Test
    fun loadModifySave_keepsFieldsItDidNotTouch() {
        db().saveHttpTask(httpTask(4))
        // Same pattern as DownloadManager.updateDownloadInfo ("Refresh link").
        val loaded = db().getHttpTask(4)!!
        loaded.url = "https://example.com/refreshed.bin"
        db().saveHttpTask(loaded)

        val again = db().getHttpTask(4)!!
        assertEquals("https://example.com/refreshed.bin", again.url)
        assertEquals("session=s3cr3t; theme=dark", again.cookie)
        assertEquals(headers, again.headers)
        assertEquals("https://example.com/page", again.origin)
        assertEquals("/chosen", again.userSelectedDownloadFolder)
    }

    @Test
    fun taskInfo_valuesOver64KbRoundTrip() {
        val task = httpTask(5, url = big("https://example.com/signed?sig=")).apply {
            cookie = big("session=")
            headers = mapOf("X-Big" to listOf(big("v")))
        }
        db().saveHttpTask(task)
        assertEquals(task, db().getHttpTask(5))
    }

    @Test
    fun httpState_valuesOver64KbRoundTrip() {
        val host = host()
        val ctx = HttpTaskContext(
            id = 6, chunks = ConcurrentHashMap<Long, Chunk>().apply {
                put(1L, Chunk(1L, 0, AtomicLong(10), AtomicLong(5), AtomicReference(ChunkStatus.Downloading),
                    AtomicReference(null), AtomicLong(0)))
            },
            init = AtomicBoolean(true), totalSize = 10, downloaded = AtomicLong(5),
            url = big("https://example.com/f?sig="), contentType = "application/octet-stream",
            headers = mapOf("X-Big" to listOf(big("v"))), cookie = big("session="),
            stopFlag = AtomicBoolean(false), completed = AtomicBoolean(false), tempFileName = "t.tmp",
            tempFileCreated = AtomicBoolean(true), diskError = AtomicBoolean(false),
            downloadHost = host, tempFolder = tmpDir.absolutePath,
        )
        saveState(ctx, work.absolutePath)

        val loaded = loadState(6, work.absolutePath, CountingHttpClient(), host)
        assertTrue("state with >64KB values did not load: ${loaded.exceptionOrNull()}", loaded.isSuccess)
        val c = loaded.getOrThrow()
        assertEquals(ctx.url, c.url)
        assertEquals(ctx.cookie, c.cookie)
        assertEquals(ctx.headers, c.headers)
        assertEquals(5L, c.chunks[1L]!!.downloaded.get())
    }

    @Test
    fun hlsState_segmentUrlOver64KbRoundTrips() {
        val host = host()
        val longUrl = big("https://cdn.example.com/seg0.ts?token=")
        val ctx = HlsTaskContext(
            id = 7,
            chunks = arrayListOf(
                StreamingChunk(
                    id = 1, sequence = 0, status = AtomicReference(ChunkStatus.Ready),
                    error = AtomicReference(null), url = longUrl, byteRange = null,
                    keyUrl = big("https://cdn.example.com/key?token="), iv = "0x01", tag = "video",
                    fileHandle = AtomicReference(null), encrypted = true,
                )
            ),
            cookie = big("cf="), httpClient = CountingHttpClient(), downloadHost = host,
            tempFileName = "h.tmp", tempFolder = tmpDir.absolutePath,
            url = big("https://cdn.example.com/index.m3u8?token="), audioUrl = null,
            independent = false, encrypted = true,
        )
        saveState(ctx, work.absolutePath)

        val loaded = loadHlsState(7, work.absolutePath, CountingHttpClient(), host)
        assertTrue("HLS state with >64KB values did not load: ${loaded.exceptionOrNull()}", loaded.isSuccess)
        val c = loaded.getOrThrow()
        assertEquals(ctx.url, c.url)
        assertEquals(ctx.cookie, c.cookie)
        assertEquals(longUrl, c.chunks[0].url)
        assertEquals(ctx.chunks[0].keyUrl, c.chunks[0].keyUrl)
    }

    @Test
    fun queuedHttpTask_sendsPersistedCookieAndHeaders() {
        val bytes = randomData(64 * 1024, seed = 60)
        val ep = server.register("/auth", Endpoint(bytes))
        db().saveHttpTask(
            httpTask(60, url = server.url("/auth")).copy(
                defaultDownloadFolder = tmpDir.absolutePath,
                userSelectedDownloadFolder = null,
                knownFileSize = null,
                maxPiece = 0,
            )
        )

        // DownloadManager.processNextQueue -> startHttpTask(id): the task comes only from the DB.
        val fromDb = db().getHttpTask(60)
        assertNotNull(fromDb)
        val host = host()
        HttpDownloaderTask(fromDb!!, host, CountingHttpClient(), work.absolutePath, TestConfig(maxSegments = 1)).start()
        awaitSuccess(host)
        assertDownloaded(host, bytes)

        val first = ep.requests.first().headers
        assertTrue("Cookie not sent: ${first["cookie"]}", first["cookie"].orEmpty().any { "session=s3cr3t" in it })
        assertEquals(listOf("abc", "def"), first["x-token"]?.flatMap { it.split(",").map(String::trim) })
    }
}
