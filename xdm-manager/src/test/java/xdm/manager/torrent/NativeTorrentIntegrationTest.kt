// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.core.downloaders.CommitResult
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.PauseEvent
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.TorrentDownloadTaskInfo
import xdm.core.storage.StorageFile
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real native library through the Kotlin binding: a seeding session and a downloading one over 127.0.0.1
 * (no DHT, trackers or port mapping). Skipped when the library is not built: set `-Dxdm.torrent.lib=<path>`,
 * or build it with `build-unix.sh` of the separate libtorrent-builds repo into its default work folder.
 */
class NativeTorrentIntegrationTest {
    private lateinit var dir: File
    private val services = ArrayList<TorrentService>()

    private val settings = { _: Int? ->
        """
        listen_interfaces=127.0.0.1:0
        enable_dht=false
        enable_lsd=false
        enable_upnp=false
        enable_natpmp=false
        allow_multiple_connections_per_ip=true
        """.trimIndent()
    }

    companion object {
        private val loaded: Boolean by lazy {
            val os = System.getProperty("os.name").lowercase()
            val arch = if (System.getProperty("os.arch").contains("aarch64") || System.getProperty("os.arch") == "arm64") "arm64" else "x64"
            val candidates = listOfNotNull(
                System.getProperty("xdm.torrent.lib"),
                when {
                    os.contains("mac") -> "${System.getProperty("user.home")}/.cache/xdm-torrent/out/macos-$arch/bin/libxdmtorrent.dylib"
                    os.contains("linux") -> "${System.getProperty("user.home")}/.cache/xdm-torrent/out/linux-$arch/bin/libxdmtorrent.so"
                    else -> null
                },
            )
            val lib = candidates.map(::File).firstOrNull { it.isFile } ?: return@lazy false
            System.load(lib.absolutePath)
            true
        }
    }

    @BeforeEach
    fun setup() {
        assumeTrue(loaded, "native torrent library not built")
        dir = Files.createTempDirectory("xdm-native-torrent").toFile()
    }

    @AfterEach
    fun tearDown() {
        services.forEach { runCatching { it.shutdown(timeoutMs = 5000) } }
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun service(name: String) =
        TorrentService(File(dir, name).apply { mkdirs() }.absolutePath, { JniTorrentEngine() }, settings).also { services.add(it) }

    private fun randomFile(f: File, size: Int, seed: Long) {
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(size).also { Random(seed).nextBytes(it) })
    }

    private class Host(override val appDir: String) : DownloadHost {
        val activated = CountDownLatch(1)
        val done = CountDownLatch(1)
        val progress = CopyOnWriteArrayList<DownloadStatusInfo.ProgressInfo>()
        @Volatile var success: DownloadStatusInfo.FinalInfo? = null
        @Volatile var failed: DownloadError? = null
        @Volatile var paused = false

        override fun onDownloadActivated(id: Long) = activated.countDown()
        override fun onDownloadInit(data: DownloadStatusInfo.InitInfo, downloadType: DownloadType) {}
        override fun onDownloadProgress(event: DownloadStatusInfo.ProgressInfo) { progress.add(event) }
        override fun onAssembleStart(id: Long) {}
        override fun onAssembleProgress(event: DownloadStatusInfo.AssembleInfo) {}
        override fun onDownloadSuccess(event: DownloadStatusInfo.FinalInfo) { success = event; done.countDown() }
        override fun onDownloadFailed(id: Long, error: DownloadError) { failed = error; done.countDown() }
        override fun onDownloadPaused(id: Long, event: PauseEvent) { paused = true; done.countDown() }
        override val applySpeedLimit = false
        override val speedLimit = -1
        override fun getTempDir(id: Long, url: String, contentType: String?, contentDisposition: String?) = appDir
        override fun commitOutputFile(id: Long, part: StorageFile, downloadType: DownloadType): CommitResult =
            CommitResult.Failed(DownloadError.InternalError)
    }

    @Test
    fun `download from a local seed, then fetch the metadata of its magnet`() {
        val seedDir = File(dir, "seed")
        randomFile(File(seedDir, "pack/a.bin"), 2 * 1024 * 1024 + 12345, 1)
        randomFile(File(seedDir, "pack/sub/b.bin"), 700_001, 2)
        val torrent = assertNotNull(NativeTorrent.testMakeTorrent(File(seedDir, "pack").absolutePath, 0), "made a torrent")

        // Seeder.
        val a = service("a")
        val ea = a.start()
        val info = assertNotNull(ea.parseTorrent(torrent), "parsed")
        val key = info.key
        assertEquals("pack", info.name, "name")
        val seeding = CountDownLatch(1)
        a.attach(key, null, object : TorrentListener {
            override fun onEvent(event: TorrentEvent) {
                if (event.status?.seeding == true) seeding.countDown()
            }
        })
        assertEquals(key, ea.addTorrent(torrent, null, seedDir.absolutePath, null, 0), "seed added")
        assertTrue(seeding.await(30, TimeUnit.SECONDS), "A seeds")
        val port = ea.listenPort()
        assertTrue(port > 0, "A listens")

        // Downloader, through the task the download manager uses.
        val b = service("b")
        val bData = File(dir, "b").absolutePath
        val db = TaskInfoDB(bData)
        val target = File(dir, "dl")
        val task = TorrentDownloadTaskInfo(1, key, "pack", target.absolutePath, null, null)
        db.saveTorrentTask(task)
        assertTrue(b.saveTorrent(1, torrent), "metadata saved")
        val host = Host(bData)
        val t = TorrentDownloaderTask(task, host, b, db)
        t.start()
        assertTrue(host.activated.await(10, TimeUnit.SECONDS), "added to the session")
        b.start().addPeer(key, "127.0.0.1", port)
        assertTrue(host.done.await(60, TimeUnit.SECONDS), "finished")
        val ok = assertNotNull(host.success, "succeeded (failed: ${host.failed})")
        assertEquals("pack", ok.finalFileName, "name")
        assertEquals(target.absolutePath, ok.finalOutputFolder, "folder")
        assertEquals(info.totalSize - info.files.filter { it.isPad }.sumOf { it.size }, ok.fileSize, "size of the files")
        assertContentEquals(File(seedDir, "pack/a.bin").readBytes(), File(target, "pack/a.bin").readBytes(), "a.bin")
        assertContentEquals(File(seedDir, "pack/sub/b.bin").readBytes(), File(target, "pack/sub/b.bin").readBytes(), "b.bin")
        assertNotNull(b.readResume(1), "resume data saved")

        // Its magnet, with the seeder as a peer: metadata only, no files written.
        val got = CountDownLatch(1)
        var meta: ByteArray? = null
        val metaRoot = File(dir, "meta")
        b.fetchMetadata("magnet:?xt=urn:btih:$key&x.pe=127.0.0.1:$port", metaRoot.absolutePath) {
            meta = it
            got.countDown()
        }
        assertTrue(got.await(30, TimeUnit.SECONDS), "metadata fetched")
        val fetched = assertNotNull(meta?.let { b.start().parseTorrent(it) }, "fetched metadata parses")
        assertEquals(key, fetched.key, "same torrent")
        assertEquals(info.files.map { it.path }, fetched.files.map { it.path }, "same files")
        val deadline = System.currentTimeMillis() + 10_000
        while (File(metaRoot, "torrent-meta-$key").exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(!File(metaRoot, "torrent-meta-$key").exists(), "temp folder deleted")

        // Exit: the session state is kept for the next start.
        b.shutdown(timeoutMs = 5000)
        assertTrue(File(bData, TorrentService.STATE_FILE).isFile, "session state saved")
    }
}
