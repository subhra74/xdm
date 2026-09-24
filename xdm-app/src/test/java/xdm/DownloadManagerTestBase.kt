package xdm

import org.junit.After
import org.junit.Before
import xdm.app.AppConfig
import xdm.app.AppContext
import xdm.app.AppDB
import xdm.app.CapturedVideoTracker
import xdm.app.DownloadCategory
import xdm.app.DownloadManager
import xdm.app.IAppInstance
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.DownloaderTask
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.TaskInfoDB
import java.io.File
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared harness for [DownloadManager] tests: the real manager with real HTTP/HLS/DASH tasks pointed
 * at a local server that accepts connections and never replies (so started downloads stay active
 * until paused), a real [AppConfig]/[AppDB]/[TaskInfoDB]/[CapturedVideoTracker] in a temp dir, and a
 * no-op [IAppInstance]. Internals are read by reflection.
 */
abstract class DownloadManagerTestBase {

    protected lateinit var dir: File
    protected lateinit var config: AppConfig
    protected lateinit var dm: DownloadManager
    protected lateinit var appDB: AppDB
    protected lateinit var taskDB: TaskInfoDB
    protected lateinit var tracker: CapturedVideoTracker
    private lateinit var server: ServerSocket
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val everStarted = CopyOnWriteArrayList<DownloaderTask>()
    protected var nextId = 100L

    @Before
    fun setup() {
        dir = Files.createTempDirectory("xdm-dm-test").toFile()
        server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                runCatching { sockets.add(server.accept()) }
            }
        }.apply { isDaemon = true }.start()

        config = AppConfig(dir.absolutePath).apply {
            keepAwake = false
            showDownloadProgressWindow = false
            showDownloadCompleteWindow = false
            haltAfterDownload = false
            tempFolder = File(dir, "temp").absolutePath
            defaultDownloadFolder = dir.absolutePath
            // Categories are seeded at construction from the real download folder; re-seed
            // them under the temp dir so auto-categorized tests stay inside it.
            categories = DownloadCategory.defaults(dir.absolutePath)
        }
        appDB = AppDB(dir.absolutePath)
        taskDB = TaskInfoDB(dir.absolutePath)
        tracker = CapturedVideoTracker()
        dm = DownloadManager(appDB, taskDB, dir.absolutePath)
        AppContext.configDir = dir.absolutePath
        AppContext.config = config
        AppContext.db = appDB
        AppContext.taskInfoDB = taskDB
        AppContext.downloader = dm
        AppContext.videoTracker = tracker
        AppContext.app = Proxy.newProxyInstance(
            IAppInstance::class.java.classLoader, arrayOf(IAppInstance::class.java)
        ) { _, _, _ -> null } as IAppInstance
    }

    @After
    fun tearDown() {
        (activeSessions().values + everStarted).forEach { runCatching { it.stop() } }
        Thread.sleep(300)
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    // ---- reflection helpers ---------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    protected fun activeSessions(): ConcurrentHashMap<Long, DownloaderTask> =
        DownloadManager::class.java.getDeclaredField("activeSessions").apply { isAccessible = true }
            .get(dm) as ConcurrentHashMap<Long, DownloaderTask>

    protected fun queuedIds(): List<Long> {
        val q = DownloadManager::class.java.getDeclaredField("queue").apply { isAccessible = true }.get(dm)
        return synchronized(q) {
            (q as Collection<*>).map { it!!.javaClass.getDeclaredMethod("getId").invoke(it) as Long }
        }
    }

    protected fun host(): DownloadHost =
        DownloadManager::class.java.getDeclaredField("downloadHost").apply { isAccessible = true }
            .get(dm) as DownloadHost

    /** Remembers every currently active task so teardown stops it even after it left the manager. */
    protected fun remember() = everStarted.addAll(activeSessions().values)

    protected fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    // ---- task builders --------------------------------------------------------------------

    protected val hangingBase get() = "http://127.0.0.1:${server.localPort}"

    protected fun httpTask(id: Long = nextId++) = HttpDownloadTaskInfo(
        id = id, url = "$hangingBase/file", fileName = "f$id.bin", respectFileName = false, cookie = null,
        headers = null, origin = null, autoCategorize = false, defaultDownloadFolder = dir.absolutePath,
        userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null, knownFileSize = null,
    )

    protected fun hlsTask(id: Long = nextId++) = HlsDownloadTaskInfo(
        id = id, fileName = "v$id.mp4", tempDir = "", respectFileName = false, cookie = null, headers = null,
        origin = null, autoCategorize = false, defaultDownloadFolder = dir.absolutePath,
        userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null,
        url = "$hangingBase/index.m3u8", audioUrl = null, independent = false,
    )
}
