import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import xdm.core.CoreConfig
import xdm.core.downloaders.CommitResult
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.PauseEvent
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.network.http.impl.HttpClientImpl
import java.io.File
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Shared harness for the segmented HTTP downloader tests: a fresh [MockHttpServer], temp
 * dirs, and helpers to build/run/await [HttpDownloaderTask]s. The race-connection timings are
 * dialled down so tests exercise the stall logic in ~1s instead of ~12s.
 *
 * The concrete test classes are split (plain vs. race/stall) and run in separate JVM forks
 * (see surefire `reuseForks=false`) so the many networked downloads in one class can't leak
 * threads/sockets into another.
 */
abstract class HttpDownloadTestBase {

    protected lateinit var server: MockHttpServer
    protected lateinit var work: File
    protected lateinit var tmpDir: File
    protected lateinit var outDir: File
    private val clients = CopyOnWriteArrayList<HttpClientImpl>()
    private val tasks = CopyOnWriteArrayList<HttpDownloaderTask>()
    protected val rawServers = CopyOnWriteArrayList<RawDropServer>()

    @Before
    fun setup() {
        server = MockHttpServer()
        work = Files.createTempDirectory("xdm-http-test").toFile()
        tmpDir = File(work, "tmp").apply { mkdirs() }
        outDir = File(work, "out").apply { mkdirs() }
        // Dial the stall/race timings way down so tests exercise the logic in ~1s, not ~12s.
        HttpDownloaderTask.RACE_MONITOR_INTERVAL_MS = 150L
        HttpDownloaderTask.RACE_TRIGGER_MS = 400L
        HttpDownloaderTask.RACE_SLOW_SPEED_BYTES = 8 * 1024
        HttpDownloaderTask.MIN_RACE_REMAINING = 4L * 1024
    }

    @After
    fun tearDown() {
        // Stop every task first so no monitor/retriever thread survives the test (a timed-out
        // download would otherwise leave a monitor spinning and spawning connections against the
        // now-stopped server).
        tasks.forEach { runCatching { it.stop() } }
        Thread.sleep(200) // let stopFlag propagate to the monitor/retriever loops
        clients.forEach { runCatching { it.close() } }
        server.stop()
        rawServers.forEach { runCatching { it.stop() } }
        // Restore production defaults for anything else running in the same JVM.
        HttpDownloaderTask.RACE_MONITOR_INTERVAL_MS = 3000L
        HttpDownloaderTask.RACE_TRIGGER_MS = 9000L
        HttpDownloaderTask.RACE_SLOW_SPEED_BYTES = 24 * 1024
        HttpDownloaderTask.MIN_RACE_REMAINING = 8L * 1024
        work.deleteRecursively()
    }

    protected fun download(id: Long, path: String, maxSegments: Int): TestDownloadHost {
        val host = host()
        newTask(id, path, host, maxSegments).start()
        return host
    }

    protected fun newTask(id: Long, path: String, host: TestDownloadHost, maxSegments: Int): HttpDownloaderTask =
        newTask(id, path, host, TestConfig(maxSegments))

    protected fun newTask(id: Long, path: String, host: TestDownloadHost, config: CoreConfig): HttpDownloaderTask =
        newTaskForUrl(id, server.url(path), host, config)

    protected fun newTaskForUrl(id: Long, url: String, host: TestDownloadHost, config: CoreConfig): HttpDownloaderTask {
        val client = HttpClientImpl(8)
        clients.add(client)
        val info = HttpDownloadTaskInfo(
            id = id,
            url = url,
            fileName = "file-$id.bin",
            respectFileName = false,
            cookie = null,
            headers = null,
            origin = null,
            autoCategorize = false,
            defaultDownloadFolder = tmpDir.absolutePath,
            userSelectedDownloadFolder = null,
            maxPiece = 0,
            authInfo = null,
            knownFileSize = null,
        )
        return HttpDownloaderTask(info, host, client, work.absolutePath, config).also { tasks.add(it) }
    }

    protected fun host() = TestDownloadHost(work.absolutePath, tmpDir.absolutePath, outDir.absolutePath)

    protected fun awaitSuccess(host: TestDownloadHost, timeoutSec: Long = 30) {
        assertTrue("download did not finish within ${timeoutSec}s", host.latch.await(timeoutSec, TimeUnit.SECONDS))
        assertNull("unexpected failure: ${host.failure}", host.failure)
        assertNotNull("expected success info", host.success)
    }

    protected fun awaitDone(host: TestDownloadHost, timeoutSec: Long = 30) {
        assertTrue("download did not settle within ${timeoutSec}s", host.latch.await(timeoutSec, TimeUnit.SECONDS))
    }

    protected fun assertDownloaded(host: TestDownloadHost, expected: ByteArray) {
        val f = host.finalFile
        assertNotNull("no final file committed", f)
        assertEquals("file size mismatch", expected.size.toLong(), f!!.length())
        assertArrayEquals("file content mismatch", expected, f.readBytes())
    }

    protected fun randomData(size: Int, seed: Long): ByteArray {
        val b = ByteArray(size)
        Random(seed).nextBytes(b)
        return b
    }

    protected fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(30)
        }
        return cond()
    }
}

/** Minimal [CoreConfig] for tests. */
class TestConfig(
    override var maxSegments: Int,
    override var maxRetries: Int = 5,
) : CoreConfig {
    override var speedLimit: Int = 0
    override var speedLimiterEnabled: Boolean = false
    override var useProxy: Boolean = false
    override var socksProxy: Boolean = false
    override var proxyHost: String = ""
    override var proxyPort: Int = 0
    override var proxyUser: String = ""
    override var proxyPass: String = ""
    override fun toProxy(): Proxy? = null
}

/** Captures downloader callbacks and performs the temp -> final file move. */
class TestDownloadHost(
    override val appDir: String,
    private val tempDir: String,
    private val outDir: String,
) : DownloadHost {
    val latch = CountDownLatch(1)
    val pauseLatch = CountDownLatch(1)

    @Volatile
    var success: DownloadStatusInfo.FinalInfo? = null

    @Volatile
    var failure: DownloadError? = null

    @Volatile
    var paused = false

    @Volatile
    var initInfo: DownloadStatusInfo.InitInfo? = null

    @Volatile
    var finalFile: File? = null

    val maxSegmentsSeen = AtomicInteger(0)

    override fun getTempDir(id: Long, url: String, contentType: String?, contentDisposition: String?): String = tempDir

    override fun commitOutputFile(id: Long, tmpFilePath: String, downloadType: DownloadType): CommitResult {
        val src = File(tmpFilePath)
        val dst = File(outDir, "out-$id.bin")
        if (dst.exists()) dst.delete()
        val moved = src.renameTo(dst) || runCatching {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }.isSuccess
        if (!moved || !dst.exists()) return CommitResult.Failed
        finalFile = dst
        return CommitResult.Success(dst.name, outDir)
    }

    override fun onDownloadActivated(id: Long) {}

    override fun onDownloadInit(data: DownloadStatusInfo.InitInfo, downloadType: DownloadType) {
        initInfo = data
    }

    override fun onDownloadProgress(event: DownloadStatusInfo.ProgressInfo) {
        val n = event.segments.size
        maxSegmentsSeen.updateAndGet { if (n > it) n else it }
    }

    override fun onAssembleStart(id: Long) {}

    override fun onAssembleProgress(event: DownloadStatusInfo.AssembleInfo) {}

    override fun onDownloadSuccess(event: DownloadStatusInfo.FinalInfo) {
        success = event
        latch.countDown()
    }

    override fun onDownloadFailed(id: Long, error: DownloadError) {
        failure = error
        latch.countDown()
    }

    override fun onDownloadPaused(id: Long, event: PauseEvent) {
        paused = true
        pauseLatch.countDown()
    }

    override val applySpeedLimit: Boolean = false
    override val speedLimit: Int = 0
}
