import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.CommitResult
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.media.muxer.Muxer
import xdm.core.media.muxer.transmux.mkv.MkvWriter
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Regression for CODE_REVIEW B7 (engine side): streaming downloads mux straight into the path the
 * host gives (inside the destination folder), so the commit never has to cross drives; a failed
 * commit is retried without muxing again; decrypted HLS no longer keeps its `.enc` segments; the MKV
 * spool stays in the temp folder.
 */
class TestStreamingOutputFolder : StreamingE2EBase() {

    private var nextId = 7000L

    /** Counts mux calls on top of the real muxer. */
    private class CountingMuxer(val inner: Muxer) : Muxer by inner {
        val calls = AtomicInteger(0)
        override fun mux(
            segments: List<String>, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String,
            independentSegement: Boolean, isMp4: Boolean, discontinuous: Boolean,
        ): Boolean {
            calls.incrementAndGet()
            return inner.mux(segments, outputFile, progressCallback, tempDir, independentSegement, isMp4, discontinuous)
        }
    }

    /**
     * Gives [outputPath] as the output location; records every commit's temp path; fails the first
     * [failCommits] commits with [DownloadError.OutputWriteError].
     */
    private class OutputHost(
        val inner: StreamingTestHost,
        val outputPath: String,
        var failCommits: Int = 0,
        val beforeCommit: () -> Unit = {},
    ) : DownloadHost by inner {
        val committed = CopyOnWriteArrayList<String>()
        override fun outputFilePath(id: Long, downloadType: DownloadType, ext: String) = outputPath
        override fun commitOutputFile(id: Long, tmpFilePath: String, downloadType: DownloadType): CommitResult {
            beforeCommit()
            committed.add(tmpFilePath)
            if (failCommits-- > 0) return CommitResult.Failed(DownloadError.OutputWriteError)
            return inner.commitOutputFile(id, tmpFilePath, downloadType)
        }
    }

    private fun info(id: Long, caseName: String, url: String) = HlsDownloadTaskInfo(
        id = id, fileName = "$caseName.mp4", tempDir = File(root, "$caseName/tmp").absolutePath,
        respectFileName = false, cookie = null, headers = null, origin = null, autoCategorize = false,
        defaultDownloadFolder = File(root, "$caseName/out").absolutePath, userSelectedDownloadFolder = null,
        maxPiece = 0, authInfo = null, url = url, audioUrl = null, audioOnly = false, independent = false,
    )

    private fun genTs(caseName: String) {
        val d = caseDir(caseName)
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25", "-t", "3", "-an",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "seg%d.ts").path, File(d, "index.m3u8").path
        )
    }

    @Test
    fun muxesIntoDestinationFolder_andCommitsFromThere() {
        requireFfmpeg()
        genTs("dest")
        val id = nextId++
        val h = host("dest")
        val destPart = File(root, "dest/out/.$id.xdm-part.mp4")
        val oh = OutputHost(h, destPart.absolutePath)
        HlsDownloaderTask(info(id, "dest", url("dest/index.m3u8")), newClient(), muxer(), oh, root.absolutePath, config())
            .start()
        awaitSuccess(h)
        verifyOutput(h, expectVideo = true, expectAudio = false, minDuration = 2.0)

        assertEquals("commit must receive the file muxed in the destination folder", listOf(destPart.absolutePath), oh.committed)
        assertFalse("partial output left behind", destPart.exists())
        assertFalse("temp folder not cleaned up", File(root, "dest/tmp").exists())
    }

    @Test
    fun failedCommit_isRetriedWithoutMuxingAgain() {
        requireFfmpeg()
        genTs("retry")
        val id = nextId++
        val part = File(root, "retry/out/.$id.xdm-part.mp4")
        val counting = CountingMuxer(muxer())

        val first = host("retry")
        HlsDownloaderTask(
            info(id, "retry", url("retry/index.m3u8")), newClient(), counting,
            OutputHost(first, part.absolutePath, failCommits = 1), root.absolutePath, config()
        ).start()
        assertEquals(DownloadError.OutputWriteError, awaitFailure(first))
        assertTrue("muxed output must be kept for the retry", part.isFile)

        val second = host("retry-2")
        HlsDownloaderTask(
            info(id, "retry", url("retry/index.m3u8")), newClient(), counting,
            OutputHost(second, part.absolutePath), root.absolutePath, config()
        ).start()
        awaitSuccess(second)
        assertEquals("the retry must only commit, not mux again", 1, counting.calls.get())
    }

    @Test
    fun unwritableDestination_failsBeforeMuxing() {
        requireFfmpeg()
        genTs("blocked")
        val blocker = File(root, "blocked/out").apply { parentFile.mkdirs(); writeText("a file, not a folder") }
        val id = nextId++
        val counting = CountingMuxer(muxer())
        val h = host("blocked")
        HlsDownloaderTask(
            info(id, "blocked", url("blocked/index.m3u8")), newClient(), counting,
            OutputHost(h, File(blocker, ".$id.xdm-part.mp4").absolutePath), root.absolutePath, config()
        ).start()
        assertEquals(DownloadError.OutputWriteError, awaitFailure(h))
        assertEquals("must not mux into an unwritable folder", 0, counting.calls.get())
    }

    @Test
    fun encryptedSegments_areDeletedAfterDecryption() {
        requireFfmpeg()
        genTs("aes")
        val d = caseDir("aes")
        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
        File(d, "enc.key").writeBytes(key)
        val segs = d.listFiles { f -> f.name.matches(Regex("seg\\d+\\.ts")) }!!.sortedBy { it.name }
        val playlist = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n")
        playlist.append("#EXT-X-KEY:METHOD=AES-128,URI=\"enc.key\"\n")
        segs.forEachIndexed { i, seg ->
            val iv = ByteArray(16).also { it[15] = i.toByte() }
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            File(d, "e$i.ts").outputStream().use { o -> CipherOutputStream(o, c).use { seg.inputStream().copyTo(it) } }
            playlist.append("#EXTINF:1.0,\ne$i.ts\n")
        }
        playlist.append("#EXT-X-ENDLIST\n")
        File(d, "enc.m3u8").writeText(playlist.toString())

        val id = nextId++
        val tempDir = File(root, "aes/tmp")
        var encAtCommit = -1
        val h = host("aes")
        val oh = OutputHost(h, File(root, "aes/out/.$id.xdm-part.mp4").absolutePath) {
            encAtCommit = tempDir.listFiles { f -> f.name.endsWith(".enc") }?.size ?: 0
        }
        HlsDownloaderTask(info(id, "aes", url("aes/enc.m3u8")), newClient(), muxer(), oh, root.absolutePath, config()).start()
        awaitSuccess(h)
        assertEquals("encrypted segments still on disk after decryption", 0, encAtCommit)
    }

    @Test
    fun mkvSpool_isWrittenToSpoolDir() {
        val out = File(caseDir("mkv"), "out/.1.xdm-part.mkv").apply { parentFile.mkdirs() }
        val spoolDir = File(caseDir("mkv"), "tmp").apply { mkdirs() }
        val writer = MkvWriter(out.absolutePath, spoolDir.absolutePath)
        try {
            assertTrue("spool not in the temp folder", File(spoolDir, "${out.name}.spool").exists())
            assertFalse("spool must not be written next to the output", File("${out.absolutePath}.spool").exists())
        } finally {
            writer.abort()
        }
    }
}
