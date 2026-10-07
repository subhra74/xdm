package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.core.downloaders.BatchDownloadTaskInfo
import xdm.core.downloaders.BatchItem
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.web.batch.BatchFile
import xdm.core.downloaders.web.batch.BatchDownloaderTask
import xdm.core.downloaders.web.batch.BatchTaskContext
import xdm.core.downloaders.web.batch.batchPartFile
import xdm.core.downloaders.web.batch.deleteBatchFiles
import xdm.core.downloaders.web.batch.loadBatchState
import xdm.core.downloaders.web.batch.saveBatchState
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.network.http.impl.HttpClientImpl
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** The batch downloader: many links into one folder, one client, per-file failures, resume. */
class TestBatchDownload {
    private lateinit var root: File
    private lateinit var configDir: File
    private lateinit var server: MockHttpServer

    @BeforeEach
    fun setup() {
        root = Files.createTempDirectory("xdm-batch").toFile()
        configDir = File(root, "config").apply { mkdirs() }
        server = MockHttpServer()
    }

    @AfterEach
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    private fun <T> notNull(value: T?, message: String): T {
        assertTrue(value != null, message)
        return value!!
    }

    private fun data(size: Int, seed: Int) = Random(seed).nextBytes(size)

    private fun task(id: Long, items: List<BatchItem>, maxPiece: Int = 4) = BatchDownloadTaskInfo(
        id = id, name = "Batch", folder = root.absolutePath, headers = null, origin = "https://example.com/",
        maxPiece = maxPiece, cookies = emptyList(), items = items,
    )

    private fun item(path: String, name: String, nameFromPage: Boolean = false) =
        BatchItem(url = server.url(path), fileName = name, cookieGroup = -1, knownSize = null, nameFromPage = nameFromPage)

    /** Runs [task] to the end and returns the host that saw it. */
    private fun run(task: BatchDownloadTaskInfo): StreamingTestHost {
        val host = StreamingTestHost(configDir.absolutePath, root.absolutePath, root.absolutePath)
        BatchDownloaderTask(task, HttpClientImpl(8), host, configDir.absolutePath, E2EConfig(4, maxRetries = 1)).start()
        assertTrue(host.latch.await(60, TimeUnit.SECONDS), "batch did not finish")
        return host
    }

    @Test
    fun allFilesLandUnderTheirNamesInTheBatchFolder() {
        val payloads = (0 until 20).map { data(10_000 + it * 1_000, it) }
        payloads.forEachIndexed { i, bytes -> server.register("/f$i", Endpoint(bytes)) }
        val t = task(1, payloads.indices.map { item("/f$it", "file$it.bin") })

        val host = run(t)

        val success = notNull(host.success, "batch should succeed")
        assertEquals(0, success.failedFiles, "no file failed")
        assertEquals("Batch", success.finalFileName, "the batch name is the final name")
        val folder = File(t.batchFolder)
        payloads.forEachIndexed { i, bytes ->
            assertArrayEquals(bytes, File(folder, "file$i.bin").readBytes(), "content of file$i")
        }
        assertEquals(20, folder.list()!!.size, "only the 20 files, no part files left: ${folder.list()!!.toList()}")
        assertEquals(payloads.sumOf { it.size.toLong() }, success.fileSize, "size is the sum of the files")
    }

    @Test
    fun aFailedFileDoesNotStopTheOthers() {
        server.register("/ok1", Endpoint(data(5_000, 1)))
        server.register("/missing", Endpoint(ByteArray(10)) { ConnPlan(failStatus = 404) })
        server.register("/ok2", Endpoint(data(5_000, 2)))
        val t = task(2, listOf(item("/ok1", "a.bin"), item("/missing", "b.bin"), item("/ok2", "c.bin")))

        val host = run(t)

        val success = notNull(host.success, "a batch with a failed file still finishes")
        assertEquals(1, success.failedFiles, "one file failed")
        val folder = File(t.batchFolder)
        assertTrue(File(folder, "a.bin").isFile && File(folder, "c.bin").isFile, "the other files are there")
        assertFalse(File(folder, "b.bin").exists(), "the failed file is not")
        val state = loadBatchState(2, configDir.absolutePath).getOrThrow()
        assertEquals(ChunkStatus.Failed, state.files[1].status.get(), "state records the failure")
        assertEquals(DownloadError.InvalidResponse, state.files[1].error.get(), "and why")
    }

    @Test
    fun retryDownloadsOnlyTheFailedFiles() {
        val ok = server.register("/ok", Endpoint(data(5_000, 3)))
        val broken = AtomicReference(true)
        val flaky = server.register("/flaky", Endpoint(data(6_000, 4)) {
            if (broken.get()) ConnPlan(failStatus = 500) else ConnPlan()
        })
        val t = task(3, listOf(item("/ok", "ok.bin"), item("/flaky", "flaky.bin")))
        assertEquals(1, notNull(run(t).success, "first run").failedFiles, "first run: one failure")
        val okRequests = ok.connCount.get()

        broken.set(false)
        val host = run(t)

        assertEquals(0, notNull(host.success, "retry").failedFiles, "retry: nothing failed")
        assertEquals(okRequests, ok.connCount.get(), "the finished file is not fetched again")
        assertArrayEquals(flaky.data, File(t.batchFolder, "flaky.bin").readBytes(), "the failed file is now complete")
    }

    @Test
    fun resumeContinuesFromThePartFile() {
        val bytes = data(50_000, 5)
        val ep = server.register("/big", Endpoint(bytes))
        val t = task(4, listOf(item("/big", "big.bin")))
        val folder = File(t.batchFolder).apply { mkdirs() }
        batchPartFile(folder.absolutePath, 0).writeBytes(bytes.copyOf(20_000))
        saveBatchState(
            BatchTaskContext(4, folder.absolutePath, listOf(BatchFile(0, downloaded = AtomicLong(20_000)))),
            configDir.absolutePath
        )

        run(t)

        assertEquals(20_000L, ep.requests.single().rangeStart, "resumed from the bytes on disk")
        assertArrayEquals(bytes, File(folder, "big.bin").readBytes(), "resumed file is intact")
    }

    @Test
    fun aServerIgnoringRangeRestartsThatFile() {
        val bytes = data(30_000, 6)
        server.register("/norange", Endpoint(bytes, resumeSupported = false))
        val t = task(5, listOf(item("/norange", "n.bin")))
        val folder = File(t.batchFolder).apply { mkdirs() }
        // Bytes that are not the file's: a resume that appended to them would corrupt it.
        batchPartFile(folder.absolutePath, 0).writeBytes(ByteArray(10_000) { 7 })
        saveBatchState(
            BatchTaskContext(5, folder.absolutePath, listOf(BatchFile(0, downloaded = AtomicLong(10_000)))),
            configDir.absolutePath
        )

        val host = run(t)

        assertEquals(0, notNull(host.success, "should finish").failedFiles, "restarted rather than failed")
        assertArrayEquals(bytes, File(folder, "n.bin").readBytes(), "restarted from zero")
    }

    @Test
    fun aNameTakenInTheFolderIsNeverOverwritten() {
        server.register("/x", Endpoint(data(4_000, 7)))
        val t = task(6, listOf(item("/x", "x.bin")))
        val folder = File(t.batchFolder).apply { mkdirs() }
        val foreign = File(folder, "x.bin").apply { writeText("not ours") }

        run(t)

        assertEquals("not ours", foreign.readText(), "existing file untouched")
        assertTrue(File(folder, "x_1.bin").isFile, "the download took the next free name")
        val state = loadBatchState(6, configDir.absolutePath).getOrThrow()
        assertEquals("x_1.bin", state.files[0].finalName, "state records the name actually used")
    }

    @Test
    fun deleteRemovesOnlyTheBatchsFiles() {
        server.register("/d", Endpoint(data(4_000, 8)))
        val t = task(7, listOf(item("/d", "d.bin")))
        run(t)
        val folder = File(t.batchFolder)
        val foreign = File(folder, "mine.txt").apply { writeText("keep") }

        deleteBatchFiles(loadBatchState(7, configDir.absolutePath).getOrThrow())

        assertFalse(File(folder, "d.bin").exists(), "the batch's file is deleted")
        assertTrue(foreign.isFile, "a file the user added stays, and with it the folder")

        foreign.delete()
        run(task(8, listOf(item("/d", "d.bin"))).also { File(it.batchFolder).mkdirs() })
        deleteBatchFiles(loadBatchState(8, configDir.absolutePath).getOrThrow())
        assertFalse(folder.exists(), "an emptied batch folder is removed")
    }

    @Test
    fun taskInfoRoundTripsWithManyItems() {
        val items = (0 until 5000).map {
            BatchItem("https://cdn.example.com/p$it.jpg?sig=$it", "p$it.jpg", it % 3 - 1, if (it % 2 == 0) it.toLong() else null, it % 5 == 0)
        }
        val t = BatchDownloadTaskInfo(
            id = 9, name = "Gallery", folder = "/downloads", headers = mapOf("User-Agent" to listOf("UA")),
            origin = "https://example.com/gallery", maxPiece = 6, cookies = listOf("a=1", "b=2"), items = items,
        )
        TaskInfoDB(configDir.absolutePath).saveBatchTask(t)

        assertEquals(t, TaskInfoDB(configDir.absolutePath).getBatchTask(9), "task info survives a round trip")
    }

    @Test
    fun stateRoundTripsAndMidDownloadFilesComeBackReady() {
        val files = listOf(
            BatchFile(0, AtomicLong(10), AtomicLong(10), AtomicReference(ChunkStatus.Finished), finalName = "a.jpg"),
            BatchFile(1, AtomicLong(5), AtomicLong(20), AtomicReference(ChunkStatus.Downloading)),
            BatchFile(2, status = AtomicReference(ChunkStatus.Failed), error = AtomicReference(DownloadError.LinkExpired)),
        )
        saveBatchState(BatchTaskContext(10, "/x/Batch", files), configDir.absolutePath)

        val loaded = loadBatchState(10, configDir.absolutePath).getOrThrow()

        assertEquals("/x/Batch", loaded.batchFolder, "folder")
        assertEquals("a.jpg", loaded.files[0].finalName, "final name")
        assertEquals(ChunkStatus.Ready, loaded.files[1].status.get(), "a file mid-download is ready again")
        assertEquals(5L, loaded.files[1].downloaded.get(), "with its bytes")
        assertEquals(DownloadError.LinkExpired, loaded.files[2].error.get(), "error kept")
        assertNull(loaded.files[1].finalName, "no name until the server answered")
    }
}
