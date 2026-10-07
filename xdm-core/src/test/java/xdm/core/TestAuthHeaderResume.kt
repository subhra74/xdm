package xdm.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.http.HttpTaskContext
import xdm.core.downloaders.web.saveState
import xdm.core.network.http.SensitiveHeaders
import xdm.core.network.http.impl.HttpClientImpl

/**
 * The browser-captured `Authorization` header is never written to `task-<id>.info` / `<id>.state`,
 * yet a download paused and resumed in the same run (rebuilt from those files, as the app does)
 * still sends it. After a restart it is gone; the server's refusal then reads as an expired link.
 */
class TestAuthHeaderResume : HttpDownloadTestBase() {
    private val token = "Bearer s3cr3t-token-123"
    private val ids = listOf(301L, 302L, 303L, 304L, 305L)

    @AfterEach
    fun forget() = ids.forEach(SensitiveHeaders::forget)

    private fun db() = TaskInfoDB(work.absolutePath)

    private fun info(id: Long, path: String) = HttpDownloadTaskInfo(
        id = id, url = server.url(path), fileName = "file-$id.bin", respectFileName = false,
        cookie = "session=abc", headers = mapOf("Authorization" to listOf(token), "Referer" to listOf("https://x/")),
        origin = null, autoCategorize = false, defaultDownloadFolder = tmpDir.absolutePath,
        userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null, knownFileSize = null,
    )

    private fun task(info: HttpDownloadTaskInfo, host: TestDownloadHost) =
        HttpDownloaderTask(info, host, HttpClientImpl(8), work.absolutePath, TestConfig(4))

    /** Serves [bytes] slowly to requests carrying [token]; 401 (no Basic challenge) or [refusal] otherwise. */
    private fun register(path: String, bytes: ByteArray, refuse: AtomicBoolean = AtomicBoolean(false), refusal: Int = 401) =
        server.register(path, Endpoint(bytes, plan = { ctx ->
            when {
                refuse.get() -> ConnPlan(failStatus = refusal)
                ctx.headers["authorization"]?.firstOrNull() != token -> ConnPlan(failStatus = 401)
                else -> ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 20)
            }
        }))

    private fun tokenOnDisk(): Boolean {
        val needle = token.toByteArray(Charsets.UTF_8)
        return work.walkTopDown().filter { it.isFile && (it.name.endsWith(".info") || it.name.contains(".state")) }.any { f ->
            val b = f.readBytes()
            (0..b.size - needle.size).any { i -> needle.indices.all { b[i + it] == needle[it] } }
        }
    }

    /** Starts [id], lets some bytes arrive, pauses it: the app's "add, then pause" with files on disk. */
    private fun startAndPause(id: Long, path: String) {
        db().saveHttpTask(info(id, path))
        val host = host()
        val t = task(db().getHttpTask(id)!!, host)
        t.start()
        Thread.sleep(600)
        t.stop()
        assertTrue(host.pauseLatch.await(5, TimeUnit.SECONDS), "pause callback should fire")
        assertEquals(null, host.success, "finished before the pause")
    }

    @Test
    fun pausedDownload_resumesWithInMemoryAuthorization() {
        val bytes = randomData(3 * 1024 * 1024, seed = 31)
        val ep = register("/auth", bytes)
        startAndPause(ids[0], "/auth")
        assertFalse(tokenOnDisk(), "Authorization written to .info/.state")

        // Resume as the app does: task info and state read back from disk.
        val resumedInfo = db().getHttpTask(ids[0])!!
        assertEquals(token, resumedInfo.headers!!["Authorization"]!!.single(), "header not restored into task info")
        val before = ep.requests.size
        val host = host()
        task(resumedInfo, host).resume()
        awaitSuccess(host)
        assertDownloaded(host, bytes)

        val after = ep.requests.drop(before)
        assertTrue(after.isNotEmpty(), "resume made no requests")
        assertTrue(after.all { it.headers["authorization"]?.firstOrNull() == token }, "a resumed request lacked Authorization")
        assertFalse(tokenOnDisk(), "Authorization written to disk during resume")
    }

    @Test
    fun afterRestart_refusalIsReportedAsExpiredLink() {
        val bytes = randomData(3 * 1024 * 1024, seed = 32)
        register("/restart", bytes)
        startAndPause(ids[1], "/restart")

        SensitiveHeaders.forget(ids[1]) // what a restart amounts to
        val info = db().getHttpTask(ids[1])!!
        assertEquals(null, info.headers!!["Authorization"], "Authorization survived a restart")
        val host = host()
        task(info, host).resume()
        awaitDone(host)
        assertEquals(DownloadError.LinkExpired, host.failure, "401 after a partial download should be an expired link")
    }

    @Test
    fun forbiddenAfterPartialDownload_isExpiredLink() {
        val bytes = randomData(3 * 1024 * 1024, seed = 33)
        val refuse = AtomicBoolean(false)
        register("/forbidden", bytes, refuse, refusal = 403)
        startAndPause(ids[2], "/forbidden")

        refuse.set(true)
        val host = host()
        task(db().getHttpTask(ids[2])!!, host).resume()
        awaitDone(host)
        assertEquals(DownloadError.LinkExpired, host.failure, "403 after a partial download should be an expired link")
    }

    @Test
    fun forbiddenFromTheStart_isNotExpiredLink() {
        server.register("/never", Endpoint(randomData(1024 * 1024, seed = 34), plan = { ConnPlan(failStatus = 403) }))
        db().saveHttpTask(info(ids[3], "/never"))
        val host = host()
        task(db().getHttpTask(ids[3])!!, host).start()
        awaitDone(host)
        assertNotNull(host.failure, "expected a failure")
        assertTrue(host.failure != DownloadError.LinkExpired, "a link that never worked is not an expired one")
    }

    @Test
    fun expiredAfterSomeSegmentsFinished_stillReportsExpiredLink() {
        val id = ids[4]
        val size = 256 * 1024L
        val half = size / 2
        server.register("/mixed", Endpoint(randomData(size.toInt(), seed = 35), plan = { ConnPlan(failStatus = 403) }))
        db().saveHttpTask(info(id, "/mixed"))
        // Paused with the first segment finished and the second half done, as a restart finds it.
        val host = host()
        File(tmpDir, "mixed.tmp").writeBytes(ByteArray(size.toInt()))
        fun chunk(cid: Long, offset: Long, status: ChunkStatus, done: Long) = Chunk(
            id = cid, offset = offset, length = AtomicLong(half), downloaded = AtomicLong(done),
            status = AtomicReference(status), fileHandle = AtomicReference(null), lastTakeOver = AtomicLong(0),
        )
        val chunks = ConcurrentHashMap<Long, Chunk>().apply {
            put(1L, chunk(1L, 0, ChunkStatus.Finished, half))
            put(2L, chunk(2L, half, ChunkStatus.Downloading, half / 2))
        }
        saveState(
            HttpTaskContext(
                id = id, chunks = chunks, init = AtomicBoolean(true), totalSize = size,
                downloaded = AtomicLong(half + half / 2), url = server.url("/mixed"), contentType = null,
                headers = null, cookie = null, stopFlag = AtomicBoolean(false), completed = AtomicBoolean(false),
                tempFileName = "mixed.tmp", tempFileCreated = AtomicBoolean(true), diskError = AtomicBoolean(false),
                downloadHost = host, tempFolder = tmpDir.absolutePath,
            ),
            work.absolutePath,
        )

        task(db().getHttpTask(id)!!, host).resume()
        awaitDone(host, timeoutSec = 20)
        assertEquals(DownloadError.LinkExpired, host.failure, "expired link not reported when a segment had finished")
    }
}
