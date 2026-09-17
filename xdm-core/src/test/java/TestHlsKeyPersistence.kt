import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.DownloadStatusInfo
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Regression for CODE_REVIEW B4: AES-128 HLS keys lived only in memory, so any pause or restart
 * made the final decryption fail with "Key missing". Keys are now stored on disk in a separate
 * owner-only `<id>.keys` file (not in the periodically saved `.state`), removed once every segment is
 * decrypted.
 *
 * Also covers: a key that cannot be fetched fails the download up front; a decryption failure is
 * reported as its own error; `.state`, `.info` and `.keys` files are owner-only.
 */
class TestHlsKeyPersistence : StreamingE2EBase() {

    private var nextId = 5000L

    /** Forwards to [inner] but runs [onInit] right after init (state and keys already saved). */
    private class InitHookHost(val inner: StreamingTestHost, val onInit: () -> Unit) : DownloadHost by inner {
        override fun onDownloadInit(data: DownloadStatusInfo.InitInfo, downloadType: DownloadType) {
            inner.onDownloadInit(data, downloadType)
            onInit()
        }
    }

    private fun info(id: Long, caseName: String, url: String) = HlsDownloadTaskInfo(
        id = id, fileName = "$caseName.mp4",
        tempDir = File(root, "$caseName/tmp").absolutePath, respectFileName = false,
        cookie = null, headers = null, origin = null, autoCategorize = false,
        defaultDownloadFolder = File(root, "$caseName/out").absolutePath,
        userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null,
        url = url, audioUrl = null, audioOnly = false, independent = false,
    )

    private fun ivFromSeq(seq: Long): ByteArray {
        val b = ByteArray(16); var v = seq
        for (i in 15 downTo 0) { b[i] = (v and 0xff).toByte(); v = v ushr 8 }
        return b
    }

    /**
     * Video-only H.264 TS segments encrypted with [encryptKey]; the playlist points at `enc.key`,
     * whose served content is [servedKey] (defaults to the real key).
     */
    private fun buildAesCase(caseName: String, encryptKey: ByteArray, servedKey: ByteArray = encryptKey): String {
        val d = caseDir(caseName)
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25", "-t", "3", "-an",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "plain%d.ts").path, File(d, "plain.m3u8").path
        )
        val plain = d.listFiles { f -> f.name.matches(Regex("plain\\d+\\.ts")) }!!.sortedBy { it.name }
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"enc.key\"\n")
        plain.forEachIndexed { i, seg ->
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptKey, "AES"), IvParameterSpec(ivFromSeq(i.toLong())))
            File(d, "enc$i.ts").outputStream().use { out ->
                CipherOutputStream(out, c).use { cos -> seg.inputStream().use { it.copyTo(cos) } }
            }
            sb.append("#EXTINF:1.000000,\nenc$i.ts\n")
        }
        sb.append("#EXT-X-ENDLIST\n")
        File(d, "index.m3u8").writeText(sb.toString())
        File(d, "enc.key").writeBytes(servedKey)
        return url("$caseName/index.m3u8")
    }

    private fun randomKey() = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /** Starts [caseName], pauses it right after init, and returns the id once the pause is acknowledged. */
    private fun startAndPauseAfterInit(caseName: String, url: String): Long {
        val id = nextId++
        val paused = host(caseName)
        lateinit var task: HlsDownloaderTask
        val hookHost = InitHookHost(paused) {
            task.stop()
            assertTrue("pause not acknowledged", paused.latch.await(10, TimeUnit.SECONDS))
        }
        task = HlsDownloaderTask(info(id, caseName, url), newClient(), muxer(), hookHost, root.absolutePath, config())
        task.start()
        assertTrue("first run never reached init", paused.latch.await(30, TimeUnit.SECONDS))
        assertNull("first run failed: ${paused.failure}", paused.failure)
        return id
    }

    private fun resume(id: Long, caseName: String, url: String): StreamingTestHost {
        val h = host("$caseName-resume")
        HlsDownloaderTask(info(id, caseName, url), newClient(), muxer(), h, root.absolutePath, config()).start()
        return h
    }

    private fun assertOwnerOnly(file: File) {
        assertTrue("missing file ${file.name}", file.exists())
        assertEquals(
            "${file.name} must be readable/writable by the owner only",
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file.toPath())
        )
    }

    @Test
    fun resumeAfterPause_keyUrlGone_decryptsWithStoredKey() {
        requireFfmpeg()
        val url = buildAesCase("resume", randomKey())
        val id = startAndPauseAfterInit("resume", url)

        // The key URL has expired / needs a session that is gone.
        File(caseDir("resume"), "enc.key").delete()

        val h = resume(id, "resume", url)
        awaitSuccess(h)
        verifyOutput(h, expectVideo = true, expectAudio = false, minDuration = 2.0)
    }

    @Test
    fun keysFile_existsOwnerOnlyWhilePaused_andIsDeletedAfterSuccess() {
        requireFfmpeg()
        Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val url = buildAesCase("keys", randomKey())
        val id = startAndPauseAfterInit("keys", url)

        val keys = File(root, "$id.keys")
        assertOwnerOnly(keys)

        awaitSuccess(resume(id, "keys", url))
        assertFalse("keys file left on disk after every segment was decrypted", keys.exists())
    }

    @Test
    fun stateAndTaskInfoFiles_areOwnerOnly() {
        Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        requireFfmpeg()
        val url = buildAesCase("perm", randomKey())
        val id = startAndPauseAfterInit("perm", url)
        assertOwnerOnly(File(root, "$id.state"))

        TaskInfoDB(root.absolutePath).saveHttpTask(
            HttpDownloadTaskInfo(
                id = 77, url = "https://example.com/f", fileName = "f", respectFileName = false,
                cookie = "session=1", headers = null, origin = null, autoCategorize = false,
                defaultDownloadFolder = root.absolutePath, userSelectedDownloadFolder = null,
                maxPiece = 0, authInfo = null, knownFileSize = null,
            )
        )
        assertOwnerOnly(File(root, "task-77.info"))
    }

    @Test
    fun wrongKey_reportsDecryptionError() {
        requireFfmpeg()
        val url = buildAesCase("wrong", encryptKey = randomKey(), servedKey = randomKey())
        val h = host("wrong")
        HlsDownloaderTask(info(nextId++, "wrong", url), newClient(), muxer(), h, root.absolutePath, config()).start()
        assertEquals("DecryptionError", awaitFailure(h).name)
    }

    @Test
    fun keyNotFetchable_failsBeforeDownloadingSegments() {
        val d = caseDir("nokey")
        File(d, "seg0.ts").writeBytes(ByteArray(32))
        File(d, "index.m3u8").writeText(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"missing.key\"\n#EXTINF:1.0,\nseg0.ts\n#EXT-X-ENDLIST\n"
        )
        val h = host("nokey")
        HlsDownloaderTask(info(nextId++, "nokey", url("nokey/index.m3u8")), newClient(), muxer(), h, root.absolutePath, config())
            .start()

        assertEquals(DownloadError.InvalidResponse, awaitFailure(h, 30))
        assertNull("init should fail before segments are known", h.initInfo)
    }
}
