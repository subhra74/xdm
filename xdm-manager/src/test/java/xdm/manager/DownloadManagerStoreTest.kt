// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.web.SegmentProgress
import xdm.core.network.http.HttpAuth
import xdm.core.util.MovePhase
import java.io.File
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manager on a store and host that are not the desktop's, as on Android: every list change goes
 * through [DownloadStore] and every notification through [UiSink] / [PlatformHooks].
 */
class DownloadManagerStoreTest {
    private lateinit var dir: File
    private lateinit var taskDB: TaskInfoDB
    private lateinit var store: MemoryStore
    private lateinit var dm: DownloadManager
    private val ui = RecordingUi()
    private val hooks = RecordingHooks()

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-manager-test").toFile()
        taskDB = TaskInfoDB(dir.absolutePath)
        store = MemoryStore()
        val config = TestConfig(File(dir, "temp").absolutePath)
        EngineContext.host = object : EngineHost {
            override val config: EngineConfig = config
            override val ui: UiSink = this@DownloadManagerStoreTest.ui
            override val hooks: PlatformHooks = this@DownloadManagerStoreTest.hooks
            override val taskInfoDB: TaskInfoDB = taskDB
        }
        dm = DownloadManager(store, taskDB, dir.absolutePath)
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun httpTask(id: Long) = HttpDownloadTaskInfo(
        id = id, url = "http://127.0.0.1:9/file", fileName = "f$id.bin", respectFileName = false, cookie = null,
        headers = null, origin = null, categoryId = null, targetFolder = dir.absolutePath,
        maxPiece = 0, authInfo = null, knownFileSize = null,
    )

    @Test
    fun `a download added paused is stored once and shown`() {
        dm.startHttpDownload(httpTask(1), runNow = false)

        val rec = assertNotNull(store.getById(1), "the record is in the store")
        assertEquals(RecordStatus.PAUSED, rec.status, "added without running, so paused")
        assertEquals(listOf<Long>(1), store.added, "added through the store")
        assertEquals(listOf("added 1"), ui.events, "the list was told")
        assertNotNull(taskDB.getHttpTask(1), "task info written")
    }

    @Test
    fun `deleting an idle download removes it from the store and its metadata`() {
        dm.startHttpDownload(httpTask(2), runNow = false)
        dm.deleteDownload(2, fromDisk = false)

        assertNull(store.getById(2), "the record is gone")
        assertNull(taskDB.getHttpTask(2), "task info deleted")
        assertTrue("removed" in ui.events, "the list refreshed once")
        assertEquals(listOf<Long>(2), hooks.deleted, "the app was told to drop what it keeps for it")
    }

    @Test
    fun `nothing queued means no wake lock`() {
        dm.startHttpDownload(httpTask(3), runNow = false)
        assertFalse(dm.hasPendingDownloads(), "a paused download is not pending")
        assertFalse(hooks.awake, "no wake lock held")
    }

    private class MemoryStore : DownloadStore {
        private val records = ArrayList<DbRecord>()
        val added = CopyOnWriteArrayList<Long>()

        @Synchronized
        override fun getById(id: Long): DbRecord? = records.firstOrNull { it.id == id }

        @Synchronized
        override fun filter(predicate: (DbRecord) -> Boolean): List<DbRecord> = records.filter(predicate)

        @Synchronized
        override fun add(rec: DbRecord) {
            records.add(rec)
            added.add(rec.id)
        }

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
        val events = CopyOnWriteArrayList<String>()

        override fun downloadAdded(id: Long) {
            events.add("added $id")
        }

        override fun downloadUpdated(id: Long) {}

        override fun downloadRemoved(id: Long, position: Int) {
            events.add("removed $id at $position")
        }

        override fun downloadsRemoved() {
            events.add("removed")
        }

        override fun showProgress(id: Long, fileName: String) {}
        override fun hideProgress(id: Long) {}
        override fun progress(
            id: Long, fileName: String?, downloaded: Long, size: Long, speed: Float, eta: Long, progress: Int,
            segments: Collection<SegmentProgress>, files: BatchFileCounts?,
        ) {}

        override fun moveProgress(id: Long, phase: MovePhase, progress: Int) {}
        override fun downloadFailed(id: Long, error: DownloadError) {}
        override fun spaceWarning(folder: String, needed: Long, free: Long, isTempFolder: Boolean) {}
        override fun downloadCompleteDialog(id: Long, folder: String, fileName: String, fileSize: Long) {}
        override fun downloadCompleteNotification(folder: String, fileName: String) {}
    }

    private class RecordingHooks : PlatformHooks {
        val deleted = CopyOnWriteArrayList<Long>()

        @Volatile
        var awake = false

        override val httpAuth = HttpAuth { _, _ -> null }

        override fun keepAwake(on: Boolean) {
            awake = on
        }

        override fun usableSpace(folder: String): Long? = null
        override fun markDownloadedFile(file: File, source: DownloadSource) = true
        override fun downloadFinished(filePath: String) {}
        override fun downloadFailed(id: Long) {}
        override fun allDownloadsDone() {}

        override fun downloadDeleted(id: Long) {
            deleted.add(id)
        }
    }

    private class TestConfig(override var tempFolder: String) : EngineConfig {
        override val maxParallelDownloads = 2
        override val showDownloadProgressWindow = false
        override val downloadCompleteNotification = DownloadCompleteNotification.NONE
        override val overwriteExistingFiles = false
        override val autoRenameOnConflict = true
        override val keepAwake = true
        override val markDownloadedFiles = false
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
