// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.TorrentDownloadTaskInfo
import xdm.core.downloaders.TorrentPhase
import xdm.core.downloaders.web.SegmentProgress
import xdm.core.network.http.HttpAuth
import xdm.core.util.MovePhase
import xdm.manager.BatchFileCounts
import xdm.manager.DbRecord
import xdm.manager.DownloadCategory
import xdm.manager.DownloadCompleteNotification
import xdm.manager.DownloadManager
import xdm.manager.DownloadSource
import xdm.manager.DownloadStore
import xdm.manager.EngineConfig
import xdm.manager.EngineContext
import xdm.manager.EngineHost
import xdm.manager.PlatformHooks
import xdm.manager.RecordStatus
import xdm.manager.UiSink
import java.io.File
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The download manager's torrent lifecycle over [FakeTorrentEngine]: no native library, no network. */
class DownloadManagerTorrentTest {
    private lateinit var dir: File
    private lateinit var taskDB: TaskInfoDB
    private lateinit var store: MemoryStore
    private lateinit var dm: DownloadManager
    private lateinit var engine: FakeTorrentEngine
    private lateinit var config: TestConfig
    private val ui = RecordingUi()
    private val hooks = RecordingHooks()

    private val key = "b17fc5bc7c813b248811c4fce49c9b766fb8d097"
    private val other = "0123456789abcdef0123456789abcdef01234567"

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-torrent-test").toFile()
        taskDB = TaskInfoDB(dir.absolutePath)
        store = MemoryStore()
        config = TestConfig(File(dir, "temp").absolutePath)
        EngineContext.host = object : EngineHost {
            override val config: EngineConfig = this@DownloadManagerTorrentTest.config
            override val ui: UiSink = this@DownloadManagerTorrentTest.ui
            override val hooks: PlatformHooks = this@DownloadManagerTorrentTest.hooks
            override val taskInfoDB: TaskInfoDB = taskDB
        }
        engine = FakeTorrentEngine()
        dm = DownloadManager(store, taskDB, dir.absolutePath, torrentEngineFactory = { engine })
    }

    @AfterEach
    fun tearDown() {
        dm.shutdown()
        dir.deleteRecursively()
    }

    private fun task(id: Long, k: String = key, priorities: ByteArray = ByteArray(0)) = TorrentDownloadTaskInfo(
        id = id, key = k, name = "magnet name", folder = File(dir, "dl").absolutePath, magnet = null,
        categoryId = "CAT", filePriorities = priorities,
    )

    private fun waitFor(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun status(id: Long) = store.getById(id)?.status

    /** Adds a torrent and waits until it is in the session. */
    private fun startRunning(id: Long, k: String = key): Long {
        val got = assertNotNull(dm.startTorrentDownload(task(id, k), k.toByteArray()), "added")
        waitFor("$k in the session") { k in engine.inSession && status(got) == RecordStatus.DOWNLOADING }
        return got
    }

    private fun finish(id: Long, k: String = key) {
        engine.status(k, 100, 100, finished = true)
        engine.emit(TorrentEvent(TorrentEventType.FINISHED, k))
        waitFor("finished") { status(id) == RecordStatus.FINISHED }
    }

    @Test
    fun `the session starts only when a torrent runs`() {
        dm.startTorrentDownload(task(1), key.toByteArray(), runNow = false)
        assertEquals(0, engine.opened.get(), "a paused torrent does not start the session")
        assertEquals(RecordStatus.PAUSED, status(1), "added paused")
        assertTrue(File(dir, "1.torrent").isFile, "the .torrent is kept")

        dm.resumeDownload(1)
        waitFor("added") { key in engine.inSession }
        assertEquals(1, engine.opened.get(), "started once")
        val settings = assertNotNull(engine.openedWith).first
        assertTrue("listen_interfaces=0.0.0.0:" in settings, "first start picks a port: $settings")
        assertNull(engine.openedWith!!.second, "no saved state yet")
        assertTrue("enable_dht=true" in settings && "xt.fingerprint" !in settings, "defaults passed")
    }

    @Test
    fun `adding starts the torrent with its folder and priorities`() {
        dm.startTorrentDownload(task(2, priorities = byteArrayOf(0, 4)), key.toByteArray())
        waitFor("added") { engine.added.isNotEmpty() }
        val a = engine.added.single()
        assertEquals(File(dir, "dl").absolutePath, a.savePath, "saved in the task's folder")
        assertContentEquals(byteArrayOf(0, 4), a.priorities, "the selection is applied")
        assertNull(a.resume, "nothing to resume from yet")
        waitFor("downloading") { status(2) == RecordStatus.DOWNLOADING }
        assertTrue(hooks.awake, "a downloading torrent keeps the machine awake")
    }

    @Test
    fun `the same torrent cannot be added twice`() {
        assertNotNull(dm.startTorrentDownload(task(3), key.toByteArray(), runNow = false), "first")
        assertNull(dm.startTorrentDownload(task(4), key.toByteArray(), runNow = false), "second is refused")
        assertEquals(3L, dm.findTorrent(key), "found by key")
    }

    @Test
    fun `status updates name, size and progress`() {
        val id = startRunning(5)
        engine.status(key, wanted = 4000, done = 1000, name = "ubuntu.iso")
        waitFor("progress") { store.getById(id)?.downloaded == 1000L }
        val rec = store.getById(id)!!
        assertEquals("ubuntu.iso", rec.fileName, "name from the torrent")
        assertEquals(4000L, rec.size, "size of the selected files")
        assertEquals(25, rec.progress, "percent")
        val t = assertNotNull(rec.torrent, "torrent figures kept on the record")
        assertEquals(TorrentPhase.Downloading, t.phase, "phase")
        assertEquals(3, t.peers, "peers")
        assertEquals("ubuntu.iso", taskDB.getTorrentTask(id)?.name, "name saved in the task")
        assertTrue(ui.torrentUpdates.isNotEmpty(), "the window gets torrent figures")
    }

    @Test
    fun `stop saves resume data and takes the torrent out of the session`() {
        val id = startRunning(6)
        dm.stopDownload(id)
        waitFor("paused") { status(id) == RecordStatus.PAUSED }
        assertFalse(key in engine.inSession, "removed from the session")
        assertTrue(engine.calls.indexOf("pause $key") < engine.calls.indexOf("resume-data $key"), "paused first")
        assertTrue(engine.calls.contains("remove $key false"), "removed without files")
        assertEquals("resume-$key", File(dir, "6.resume").readBytes().dropLast(8).toByteArray().toString(Charsets.US_ASCII),
            "resume data saved (plus the footer)")
        assertFalse(hooks.awake, "nothing runs any more")
    }

    @Test
    fun `resuming right after a stop waits for the removal and uses the resume data`() {
        val id = startRunning(7)
        dm.stopDownload(id)
        waitFor("paused") { status(id) == RecordStatus.PAUSED }
        dm.resumeDownload(id)
        waitFor("added again") { engine.added.size == 2 }
        val again = engine.added[1]
        assertEquals("resume-$key", again.resume?.toString(Charsets.US_ASCII), "added from the resume data")
        waitFor("downloading") { status(id) == RecordStatus.DOWNLOADING }
    }

    @Test
    fun `finishing stops the torrent when seeding is off`() {
        val id = startRunning(8)
        finish(id)
        waitFor("removed") { key !in engine.inSession }
        val rec = store.getById(id)!!
        assertFalse(rec.seeding, "not seeding")
        assertNull(rec.torrent, "no live figures once finished")
        assertTrue(File(dir, "8.resume").isFile, "resume data saved for a later Start seeding")
        val flush = engine.calls.indexOf("flush $key")
        assertTrue(flush >= 0 && flush < engine.calls.lastIndexOf("resume-data $key"),
            "files flushed before the resume data (and the success): ${engine.calls}")
        assertEquals(listOf(File(File(dir, "dl"), "pack").absolutePath to DownloadType.Torrent), hooks.finished,
            "post-download actions on the torrent's folder")
        assertFalse(hooks.awake, "nothing runs any more")
    }

    @Test
    fun `the torrent window sees a running torrent and changes its file priorities at once`() {
        val id = startRunning(20)
        engine.status(key, 100, 50)
        waitFor("status seen") { dm.torrentDetails(id)?.status != null }
        val d = assertNotNull(dm.torrentDetails(id), "in the session")
        assertEquals("pack/a.bin", d.info?.files?.single()?.path, "files from the engine")
        dm.setTorrentFilePriorities(id, byteArrayOf(FilePriority.HIGH))
        assertTrue(engine.calls.contains("priorities $key 7"), "applied: ${engine.calls}")
        assertEquals(FilePriority.HIGH, taskDB.getTorrentTask(id)?.filePriorities?.single(), "saved")
        finish(id)
        waitFor("removed") { key !in engine.inSession }
        assertNull(dm.torrentDetails(id), "not in the session once finished")
    }

    @Test
    fun `trackers are added in tiers to a running torrent only`() {
        val id = startRunning(22)
        assertTrue(dm.addTorrentTrackers(id, "udp://a:1\nudp://b:2\n\nudp://c:3"), "added")
        assertTrue(engine.calls.containsAll(listOf("tracker $key udp://a:1 0", "tracker $key udp://b:2 0", "tracker $key udp://c:3 1")),
            "tiers after the torrent's own: ${engine.calls}")
        assertFalse(dm.addTorrentTrackers(id, "no trackers here"), "nothing to add")
        finish(id)
        waitFor("removed") { key !in engine.inSession }
        assertFalse(dm.addTorrentTrackers(id, "udp://d:4"), "not running")
    }

    @Test
    fun `wanting a skipped file of a finished torrent downloads it again`() {
        val id = assertNotNull(dm.startTorrentDownload(task(21, priorities = byteArrayOf(FilePriority.NORMAL, FilePriority.SKIP)),
            key.toByteArray()), "added")
        waitFor("running") { key in engine.inSession }
        finish(id)
        waitFor("removed") { key !in engine.inSession }
        dm.setTorrentFilePriorities(id, byteArrayOf(FilePriority.NORMAL, FilePriority.SKIP))
        assertEquals(RecordStatus.FINISHED, status(id), "nothing new wanted: stays finished")
        engine.parsed = TorrentInfo(key, "", "pack", 200, 16384, 1, 0, "", "", 0,
            listOf(TorrentFile(100, 0, 4, 0, "pack/a.bin"), TorrentFile(100, 0, 0, 0, "pack/b.bin")))
        dm.setTorrentFilePriorities(id, ByteArray(0))
        waitFor("downloading again") { key in engine.inSession && status(id) == RecordStatus.DOWNLOADING }
        // Over resume data that still skips b.bin, "all normal" is sent one per file.
        assertEquals(listOf(FilePriority.NORMAL, FilePriority.NORMAL), engine.added.last().priorities?.toList(),
            "added again wanting every file")
    }

    @Test
    fun `a finished multi-file torrent marks each of its files, not the folder`() {
        config.markDownloadedFiles = true
        engine.parsed = TorrentInfo(key, "", "pack", 300, 16384, 1, 0, "", "", 0, listOf(
            TorrentFile(100, 0, 4, 0, "pack/a.bin"),
            TorrentFile(10, 0, 4, TorrentFile.ATTR_PAD, "pack/.pad/10"),
            TorrentFile(100, 0, 4, 0, "pack/sub/b.bin"),
            TorrentFile(100, 0, 0, 0, "pack/skipped.bin"), // skipped: never written
        ))
        val pack = File(File(dir, "dl"), "pack")
        File(pack, "sub").mkdirs()
        File(pack, "a.bin").writeText("a")
        File(pack, "sub/b.bin").writeText("b")
        File(pack, ".pad").mkdirs()
        File(pack, ".pad/10").writeText("p")
        val id = startRunning(9)
        finish(id)
        waitFor("marked") { hooks.marked.size >= 2 }
        assertEquals(setOf(File(pack, "a.bin"), File(pack, "sub/b.bin")), hooks.marked.toSet(),
            "each existing non-padding file, not the folder")
    }

    @Test
    fun `finishing seeds until the ratio limit when seeding is on`() {
        config.seedAfterFinish = true
        config.ratio = 0.5f
        val id = startRunning(9)
        finish(id)
        engine.status(key, 100, 100, finished = true, upload = 0)
        waitFor("seeding") { store.getById(id)?.seeding == true }
        assertTrue(key in engine.inSession, "still in the session")
        assertTrue(dm.isSeeding(id), "seeding")
        assertEquals(RecordStatus.FINISHED, status(id), "still FINISHED")
        assertFalse(hooks.awake, "seeding does not keep the machine awake")
        assertEquals(TorrentPhase.Seeding, store.getById(id)?.torrent?.phase, "seeding phase")

        engine.status(key, 100, 100, finished = true, upload = 60)
        waitFor("seeding stopped") { store.getById(id)?.seeding == false }
        waitFor("removed") { key !in engine.inSession }
        assertFalse(dm.isSeeding(id), "not seeding")
    }

    @Test
    fun `start seeding by hand adds the finished torrent from its resume data`() {
        val id = startRunning(10)
        finish(id)
        waitFor("removed") { key !in engine.inSession }
        // The finished file must be on disk to seed (no metadata in the fake: the torrent's own path is checked).
        val file = File(File(dir, "dl"), "pack")
        file.delete()
        assertFalse(dm.torrentFilesPresent(id), "files gone")
        assertFalse(dm.startSeeding(id), "nothing to seed without the files")
        file.parentFile.mkdirs()
        file.writeText("x")
        assertTrue(dm.torrentFilesPresent(id), "files back")
        assertTrue(dm.startSeeding(id), "started")
        waitFor("seeding torrent added") { engine.added.size == 2 }
        assertEquals("resume-$key", engine.added[1].resume?.toString(Charsets.US_ASCII), "from resume data")
        assertEquals(0, engine.added[1].flags, "not paused, no upload mode")
        engine.status(key, 100, 100, finished = true)
        waitFor("seeding") { store.getById(id)?.seeding == true }

        dm.stopSeeding(id)
        waitFor("stopped") { store.getById(id)?.seeding == false && key !in engine.inSession }
    }

    @Test
    fun `deleting a stopped unfinished torrent deletes its partial data`() {
        val id = startRunning(11)
        dm.stopDownload(id)
        waitFor("paused") { status(id) == RecordStatus.PAUSED }
        dm.deleteDownload(id, fromDisk = false)
        waitFor("data deleted") { engine.calls.contains("remove $key true") }
        val add = engine.added.last()
        assertEquals(TorrentFlags.PAUSED or TorrentFlags.UPLOAD_MODE, add.flags, "added only to delete")
        assertFalse(File(dir, "11.resume").exists() || File(dir, "11.torrent").exists(), "metadata deleted")
        assertNull(taskDB.getTorrentTask(id), "task info deleted")
    }

    @Test
    fun `deleting a running torrent stops it first, then deletes its data`() {
        val id = startRunning(12)
        dm.deleteDownload(id, fromDisk = false)
        waitFor("gone") { store.getById(id) == null }
        waitFor("data deleted") { engine.calls.contains("remove $key true") }
        waitFor("out of the session") { key !in engine.inSession }
    }

    @Test
    fun `deleting a finished torrent keeps its files unless asked`() {
        val id = startRunning(13)
        finish(id)
        waitFor("removed") { key !in engine.inSession }
        val adds = engine.added.size
        dm.deleteDownload(id, fromDisk = false)
        Thread.sleep(300)
        assertEquals(adds, engine.added.size, "files kept: the engine is not involved")

        val id2 = startRunning(14, other)
        finish(id2, other)
        waitFor("removed") { other !in engine.inSession }
        dm.deleteDownload(id2, fromDisk = true)
        waitFor("files deleted") { engine.calls.contains("remove $other true") }
    }

    @Test
    fun `deleting a seeding torrent from disk removes it with its files`() {
        config.seedAfterFinish = true
        config.ratio = 0f
        val id = startRunning(15)
        finish(id)
        engine.status(key, 100, 100, finished = true)
        waitFor("seeding") { dm.isSeeding(id) && store.getById(id)?.seeding == true }
        dm.deleteDownload(id, fromDisk = true)
        waitFor("removed with files") { engine.calls.contains("remove $key true") }
        assertFalse(dm.isSeeding(id), "seeding ended")
    }

    @Test
    fun `a file error fails the download`() {
        val id = startRunning(16)
        engine.emit(TorrentEvent(TorrentEventType.ERROR, key, code = 28, message = "No space left on device"))
        waitFor("failed") { status(id) == RecordStatus.ERROR }
        assertEquals(listOf(id to DownloadError.DiskSpaceError), ui.failed, "disk full reported")
        waitFor("removed") { key !in engine.inSession }
    }

    @Test
    fun `a magnet is added from its link until the metadata arrives, then from the saved metadata`() {
        val magnet = "magnet:?xt=urn:btih:$key&dn=x"
        val id = assertNotNull(dm.startTorrentDownload(task(17).copy(magnet = magnet), null))
        waitFor("added") { key in engine.inSession }
        assertEquals(magnet, engine.added.single().magnet, "added from the link")
        engine.emit(TorrentEvent(TorrentEventType.METADATA, key))
        waitFor("metadata saved") { File(dir, "17.torrent").isFile }
        // The name is saved right after the metadata file, on the event thread.
        waitFor("name from the metadata") { taskDB.getTorrentTask(id)?.name == "pack" }
    }

    @Test
    fun `shutdown saves resume data and the session state`() {
        startRunning(18)
        dm.shutdown()
        assertTrue(engine.closed, "session closed")
        assertTrue(File(dir, TorrentService.STATE_FILE).isFile, "state saved")
        assertTrue(File(dir, "18.resume").isFile, "resume data saved")

        // The next session starts from the saved state and keeps its port.
        val engine2 = FakeTorrentEngine()
        val dm2 = DownloadManager(store, taskDB, dir.absolutePath, torrentEngineFactory = { engine2 })
        dm2.torrents.start()
        assertEquals("state", engine2.openedWith?.second?.toString(Charsets.US_ASCII), "saved state passed")
        assertFalse("listen_interfaces" in engine2.openedWith!!.first, "the saved state keeps the port")
        dm2.shutdown()
    }

    @Test
    fun `fetching a magnet's metadata adds it in upload mode and removes it with its temp folder`() {
        val got = CopyOnWriteArrayList<ByteArray?>()
        dm.torrents.fetchMetadata("magnet:?xt=urn:btih:$key", File(dir, "temp").absolutePath) { got.add(it) }
        waitFor("added") { key in engine.inSession }
        assertEquals(TorrentFlags.UPLOAD_MODE, engine.added.single().flags, "no file writes")
        engine.emit(TorrentEvent(TorrentEventType.METADATA, key))
        waitFor("answered") { got.isNotEmpty() }
        assertEquals(key, got.single()?.toString(Charsets.US_ASCII), "the metadata")
        waitFor("removed with files") { engine.calls.contains("remove $key true") }
        waitFor("temp folder gone") { !File(dir, "temp/torrent-meta-$key").exists() }
    }

    @Test
    fun `a metadata fetch whose add fails answers null and deletes its temp folder`() {
        engine.inSession.add(key) // in the session but not ours: the add is refused
        val got = CopyOnWriteArrayList<ByteArray?>()
        dm.torrents.fetchMetadata("magnet:?xt=urn:btih:$key", File(dir, "temp").absolutePath) { got.add(it) }
        waitFor("answered") { got.isNotEmpty() }
        assertEquals(null, got.single(), "no metadata")
        assertFalse(File(dir, "temp/torrent-meta-$key").exists(), "temp folder deleted")
    }

    // ---- fakes ------------------------------------------------------------------------------------------

    private class MemoryStore : DownloadStore {
        private val records = ArrayList<DbRecord>()

        @Synchronized override fun getById(id: Long): DbRecord? = records.firstOrNull { it.id == id }
        @Synchronized override fun filter(predicate: (DbRecord) -> Boolean): List<DbRecord> = records.filter(predicate)
        @Synchronized override fun add(rec: DbRecord) { records.add(rec) }
        override fun update(rec: DbRecord) {}

        @Synchronized
        override fun remove(id: Long): Int? {
            val index = records.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
            records.removeAt(index)
            return index
        }

        @Synchronized
        override fun removeWhere(predicate: (DbRecord) -> Boolean): List<DbRecord> {
            val removed = records.filter(predicate)
            records.removeAll(removed)
            return removed
        }
    }

    private class RecordingUi : UiSink {
        val failed = CopyOnWriteArrayList<Pair<Long, DownloadError>>()
        val torrentUpdates = CopyOnWriteArrayList<DownloadStatusInfo.TorrentProgress>()

        override fun downloadAdded(id: Long) {}
        override fun downloadUpdated(id: Long) {}
        override fun downloadRemoved(id: Long, position: Int) {}
        override fun downloadsRemoved() {}
        override fun showProgress(id: Long, fileName: String) {}
        override fun hideProgress(id: Long) {}
        override fun progress(
            id: Long, fileName: String?, downloaded: Long, size: Long, speed: Float, eta: Long, progress: Int,
            segments: Collection<SegmentProgress>, files: BatchFileCounts?,
        ) {}

        override fun torrentProgress(id: Long, progress: DownloadStatusInfo.TorrentProgress) { torrentUpdates.add(progress) }
        override fun moveProgress(id: Long, phase: MovePhase, progress: Int) {}
        override fun downloadFailed(id: Long, error: DownloadError) { failed.add(id to error) }
        override fun spaceWarning(folder: String, needed: Long, free: Long, isTempFolder: Boolean) {}
        override fun downloadCompleteDialog(id: Long, folder: String, fileName: String, fileSize: Long) {}
        override fun downloadCompleteNotification(folder: String, fileName: String) {}
    }

    private class RecordingHooks : PlatformHooks {
        val finished = CopyOnWriteArrayList<Pair<String, DownloadType>>()

        @Volatile var awake = false

        override val httpAuth = HttpAuth { _, _ -> null }
        override fun keepAwake(on: Boolean) { awake = on }
        override fun usableSpace(folder: String): Long? = null
        val marked = CopyOnWriteArrayList<File>()

        override fun markDownloadedFile(file: File, source: DownloadSource) = marked.add(file)
        override fun downloadFinished(path: String, type: DownloadType) { finished.add(path to type) }
        override fun downloadFailed(id: Long) {}
        override fun allDownloadsDone() {}
        override fun downloadDeleted(id: Long) {}
    }

    private class TestConfig(override var tempFolder: String) : EngineConfig {
        @Volatile var seedAfterFinish = false
        @Volatile var ratio = 1.0f

        override val torrentSeedAfterFinish get() = seedAfterFinish
        override val torrentSeedRatioLimit get() = ratio
        override val maxParallelDownloads = 2
        override val showDownloadProgressWindow = false
        override val downloadCompleteNotification = DownloadCompleteNotification.NONE
        override val overwriteExistingFiles = false
        override val autoRenameOnConflict = true
        override val keepAwake = true
        @Volatile override var markDownloadedFiles = false
        override val categories = emptyList<DownloadCategory>()
        override val minVideoSize = 0L
        override val skipDuplicateManifests = false
        override val videoExtensions = emptyList<String>()
        override val blockedHosts = emptyList<String>()
        override val detectSubtitles = true
        override var speedLimit = 0
        override var speedLimiterEnabled = false
        override var maxSegments = 8
        override var maxRetries = 3
        override var useProxy = false
        override var socksProxy = false
        override var proxyHost = ""
        override var proxyPort = 0
        override var proxyUser = ""
        override var proxyPass = ""
        override fun toProxy(): Proxy? = null
    }
}
