package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.HttpTaskContext
import xdm.core.downloaders.web.loadState
import xdm.core.downloaders.web.saveState
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.SensitiveHeaders
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A browser-captured `Authorization` header is kept in memory only, never in `.info` / `.state`. */
class TestSensitiveHeaders : HttpDownloadTestBase() {
    private val secret = "Basic dXNlcjpodW50ZXIy"
    private val headers: HeaderMap = mapOf(
        "Referer" to listOf("https://example.com/"),
        "Authorization" to listOf(secret),
    )
    private val ids = listOf(9101L, 9102L)

    @AfterEach
    fun forget() = ids.forEach(SensitiveHeaders::forget)

    private fun onDisk(): Boolean {
        val needle = secret.toByteArray(Charsets.UTF_8)
        return work.walkTopDown().filter { it.isFile }.any { f ->
            val bytes = f.readBytes()
            (0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } }
        }
    }

    private fun httpTask(id: Long) = HttpDownloadTaskInfo(
        id = id, url = "https://example.com/file.bin", fileName = "file.bin", respectFileName = true,
        cookie = null, headers = headers, origin = null, autoCategorize = false,
        defaultDownloadFolder = "/downloads", userSelectedDownloadFolder = null,
        maxPiece = 4, authInfo = null, knownFileSize = null,
    )

    @Test
    fun taskInfo_keepsAuthorizationInMemoryOnly() {
        val db = TaskInfoDB(work.absolutePath)
        db.saveHttpTask(httpTask(ids[0]))

        assertFalse(onDisk(), "Authorization was written to the .info file")
        assertEquals(headers, db.getHttpTask(ids[0])!!.headers, "Authorization not restored within the run")

        SensitiveHeaders.forget(ids[0]) // what a restart amounts to
        assertNull(db.getHttpTask(ids[0])!!.headers!!["Authorization"], "Authorization survived without memory")
    }

    @Test
    fun state_keepsAuthorizationInMemoryOnly() {
        val host = host()
        val ctx = HttpTaskContext(
            id = ids[1], chunks = ConcurrentHashMap<Long, Chunk>(),
            init = AtomicBoolean(false), totalSize = null, downloaded = AtomicLong(0),
            url = "https://example.com/file.bin", contentType = null, headers = headers, cookie = null,
            stopFlag = AtomicBoolean(false), completed = AtomicBoolean(false), tempFileName = "t.tmp",
            tempFileCreated = AtomicBoolean(false), diskError = AtomicBoolean(false),
            downloadHost = host, tempFolder = tmpDir.absolutePath,
        )
        saveState(ctx, work.absolutePath)

        assertFalse(onDisk(), "Authorization was written to the .state file")
        val loaded = loadState(ids[1], work.absolutePath, CountingHttpClient(), host).getOrThrow()
        assertEquals(headers, loaded.headers, "Authorization not restored within the run")
    }

    @Test
    fun deleteRecord_forgetsAuthorization() {
        val db = TaskInfoDB(work.absolutePath)
        db.saveHttpTask(httpTask(ids[0]))
        db.deleteRecord(ids[0])
        db.saveHttpTask(httpTask(ids[0]).copy(headers = null))

        assertNull(db.getHttpTask(ids[0])!!.headers, "deleted download's Authorization was handed back")
    }

    @Test
    fun saveWithoutAuthorization_dropsTheOldOne() {
        val db = TaskInfoDB(work.absolutePath)
        db.saveHttpTask(httpTask(ids[0]))
        // Refresh link: the new capture carries no Authorization.
        db.saveHttpTask(httpTask(ids[0]).copy(headers = mapOf("Referer" to listOf("https://example.com/"))))

        assertNull(db.getHttpTask(ids[0])!!.headers!!["Authorization"], "stale Authorization came back")
    }
}
