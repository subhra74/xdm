// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.web.SegmentProgress
import xdm.core.network.http.CertExceptions
import xdm.core.network.http.HttpAuth
import xdm.core.util.MovePhase
import java.io.File
import java.io.FileInputStream
import java.net.Proxy
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A download from a self-signed server fails with a TLS error and remembers the refused certificate;
 * [DownloadManager.resumeTrustingRefused] trusts it for that host and the download then finishes.
 */
class DownloadManagerCertTest {
    private lateinit var dir: File
    private lateinit var server: SSLServerSocket
    private lateinit var dm: DownloadManager
    private val errors = ConcurrentHashMap<Long, DownloadError>()
    private val body = ByteArray(64 * 1024) { (it % 251).toByte() }

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-manager-cert").toFile()
        CertExceptions.init(File(dir, "data").apply { mkdirs() }.path)
        server = startServer()
        val taskDB = TaskInfoDB(dir.absolutePath)
        val config = TestConfig(File(dir, "temp").absolutePath)
        EngineContext.host = object : EngineHost {
            override val config: EngineConfig = config
            override val ui: UiSink = FailureUi(errors)
            override val hooks: PlatformHooks = QuietHooks()
            override val taskInfoDB: TaskInfoDB = taskDB
        }
        dm = DownloadManager(MemoryStore(), taskDB, dir.absolutePath)
    }

    @AfterEach
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun `refused certificate, trusted on resume, then the download finishes`() {
        val id = dm.startHttpDownload(task(1))
        val failed = waitFor(id) { it.status == RecordStatus.ERROR || it.status == RecordStatus.FINISHED }
        assertEquals(RecordStatus.ERROR, failed, "a self-signed server fails the download")
        assertEquals(DownloadError.TlsError, errors[id], "as a TLS error")
        assertTrue(dm.hasRefusedCerts(id), "the refused certificate is remembered")
        assertTrue(CertExceptions.list().isEmpty(), "nothing trusted before the user says so")

        dm.resumeTrustingRefused(id)
        assertFalse(dm.hasRefusedCerts(id), "the refusals were used up")
        assertEquals(listOf("127.0.0.1"), CertExceptions.list().map { it.host }, "trusted for its host")
        val done = waitFor(id) { it.status == RecordStatus.FINISHED || it.status == RecordStatus.ERROR }
        assertEquals(RecordStatus.FINISHED, done, "the retry passes (error ${errors[id]})")
        assertTrue(body.contentEquals(File(dir, "f$id.bin").readBytes()), "downloaded bytes")
    }

    private fun task(id: Long) = HttpDownloadTaskInfo(
        id = id, url = "https://127.0.0.1:${server.localPort}/file.bin", fileName = "f$id.bin", respectFileName = true,
        cookie = null, headers = null, origin = null, categoryId = null, targetFolder = dir.absolutePath,
        maxPiece = 1, authInfo = null, knownFileSize = null,
    )

    /** Waits for [id]'s record to reach a status [done] accepts, after leaving the one it had. */
    private fun waitFor(id: Long, done: (DbRecord) -> Boolean): RecordStatus {
        val deadline = System.currentTimeMillis() + 30_000
        Thread.sleep(200)
        while (System.currentTimeMillis() < deadline) {
            val rec = dm.store.getById(id)
            if (rec != null && done(rec) && !dm.hasPendingDownloads()) return rec.status
            Thread.sleep(100)
        }
        return dm.store.getById(id)?.status ?: error("no record $id")
    }

    private fun startServer(): SSLServerSocket {
        val store = File(dir, "server.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "keytool: $output")
        val keys = KeyStore.getInstance("PKCS12").apply { FileInputStream(store).use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "changeit".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val socket = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        thread(isDaemon = true, name = "tls-download-server") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        client.use {
                            val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            while (input.readLine()?.isNotEmpty() == true) Unit
                            val out = it.getOutputStream()
                            out.write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
                            )
                            out.write(body)
                            out.flush()
                        }
                    } catch (_: Exception) { // a rejected handshake
                    }
                }
            }
        }
        return socket
    }

    private class MemoryStore : DownloadStore {
        private val records = ArrayList<DbRecord>()

        @Synchronized
        override fun getById(id: Long): DbRecord? = records.firstOrNull { it.id == id }

        @Synchronized
        override fun filter(predicate: (DbRecord) -> Boolean): List<DbRecord> = records.filter(predicate)

        @Synchronized
        override fun add(rec: DbRecord) {
            records.add(rec)
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

    private class FailureUi(private val errors: MutableMap<Long, DownloadError>) : UiSink {
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
        override fun moveProgress(id: Long, phase: MovePhase, progress: Int) {}
        override fun downloadFailed(id: Long, error: DownloadError) {
            errors[id] = error
        }
        override fun spaceWarning(folder: String, needed: Long, free: Long, isTempFolder: Boolean) {}
        override fun downloadCompleteDialog(id: Long, folder: String, fileName: String, fileSize: Long) {}
        override fun downloadCompleteNotification(folder: String, fileName: String) {}
    }

    private class QuietHooks : PlatformHooks {
        override val httpAuth = HttpAuth { _, _ -> null }
        override fun keepAwake(on: Boolean) {}
        override fun usableSpace(folder: String): Long? = null
        override fun markDownloadedFile(file: File, source: DownloadSource) = true
        override fun downloadFinished(path: String, type: DownloadType) {}
        override fun downloadFailed(id: Long) {}
        override fun allDownloadsDone() {}
        override fun downloadDeleted(id: Long) {}
    }

    private class TestConfig(override var tempFolder: String) : EngineConfig {
        override val maxParallelDownloads = 2
        override val showDownloadProgressWindow = false
        override val downloadCompleteNotification = DownloadCompleteNotification.NONE
        override val overwriteExistingFiles = true
        override val autoRenameOnConflict = false
        override val keepAwake = false
        override val markDownloadedFiles = false
        override val categories = emptyList<DownloadCategory>()
        override val minVideoSize = 0L
        override val skipDuplicateManifests = false
        override val videoExtensions = emptyList<String>()
        override val blockedHosts = emptyList<String>()
        override val detectSubtitles = true
        override var speedLimit = 0
        override var speedLimiterEnabled = false
        override var maxSegments = 1
        override var maxRetries = 1
        override var useProxy = false
        override var socksProxy = false
        override var proxyHost = ""
        override var proxyPort = 0
        override var proxyUser = ""
        override var proxyPass = ""
        override fun toProxy(): Proxy? = null
    }
}
