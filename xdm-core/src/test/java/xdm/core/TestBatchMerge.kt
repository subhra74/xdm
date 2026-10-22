package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.core.downloaders.BatchDownloadTaskInfo
import xdm.core.downloaders.BatchItem
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.web.batch.BatchDownloaderTask
import xdm.core.downloaders.web.batch.BatchFile
import xdm.core.downloaders.web.batch.BatchTaskContext
import xdm.core.downloaders.web.batch.loadBatchState
import xdm.core.downloaders.web.batch.saveBatchState
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.network.http.impl.HttpClientImpl
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Merged items in a batch: a video played as a video-only and an audio-only track is downloaded as
 * the two tracks and merged into one file in the batch folder, next to ordinary files.
 */
class TestBatchMerge {
    private lateinit var root: File
    private lateinit var configDir: File
    private lateinit var tempDir: File
    private lateinit var server: MockHttpServer

    @BeforeEach
    fun setup() {
        root = Files.createTempDirectory("xdm-batch-merge").toFile()
        configDir = File(root, "config").apply { mkdirs() }
        tempDir = File(root, "tmp").apply { mkdirs() }
        server = MockHttpServer()
    }

    @AfterEach
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    /** A 2 s video-only and an audio-only fragmented MP4, as such sites serve them. */
    private fun tracks(): Pair<ByteArray, ByteArray> {
        assumeTrue(StreamingE2EBase.ffmpeg != null, "ffmpeg not on PATH; skipping")
        val v = File(root, "v.mp4")
        val a = File(root, "a.mp4")
        val frag = arrayOf("-movflags", "frag_keyframe+empty_moov+default_base_moof")
        ffmpeg("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-t", "2", "-an",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25", *frag, v.path)
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2", "-vn",
            "-c:a", "aac", *frag, a.path)
        return v.readBytes() to a.readBytes()
    }

    private fun ffmpeg(vararg args: String) {
        val (code, out) = StreamingE2EBase.exec(StreamingE2EBase.ffmpeg!!, "-y", "-loglevel", "error", *args)
        check(code == 0) { "ffmpeg failed: $out" }
    }

    private fun task(id: Long, items: List<BatchItem>) = BatchDownloadTaskInfo(
        id = id, name = "Batch", folder = root.absolutePath, headers = null, origin = null,
        maxPiece = 4, cookies = emptyList(), items = items, tempDir = tempDir.absolutePath,
    )

    private fun plain(path: String, name: String) =
        BatchItem(url = server.url(path), fileName = name, cookieGroup = -1, knownSize = null, nameFromPage = true)

    private fun merged(video: String, audio: String, name: String) = BatchItem(
        url = server.url(video), fileName = name, cookieGroup = -1, knownSize = null, nameFromPage = true,
        audioUrl = server.url(audio),
    )

    private fun run(task: BatchDownloadTaskInfo): StreamingTestHost {
        val host = StreamingTestHost(configDir.absolutePath, root.absolutePath, root.absolutePath)
        BatchDownloaderTask(task, HttpClientImpl(8), host, configDir.absolutePath, E2EConfig(4, maxRetries = 1)) {
            TransmuxingMuxer(root.absolutePath)
        }.start()
        assertTrue(host.latch.await(60, TimeUnit.SECONDS), "batch did not finish")
        return host
    }

    private fun streamTypes(file: File): List<String>? {
        val ffprobe = StreamingE2EBase.ffprobe ?: return null
        val (_, out) = StreamingE2EBase.exec(
            ffprobe, "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", file.path,
        )
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun mergedItem_landsAsOneFileWithBothTracks_nextToPlainFiles() {
        val (v, a) = tracks()
        val plainBytes = Random(1).nextBytes(20_000)
        server.register("/plain", Endpoint(plainBytes))
        server.register("/v", Endpoint(v))
        server.register("/a", Endpoint(a))
        val t = task(1, listOf(plain("/plain", "notes.bin"), merged("/v", "/a", "clip.mp4")))

        val host = run(t)

        assertEquals(0, host.success!!.failedFiles, "nothing failed: ${host.failure}")
        val folder = File(t.batchFolder)
        assertEquals(setOf("notes.bin", "clip.mp4"), folder.list()!!.toSet(), "only the two files, no part files")
        assertArrayEquals(plainBytes, File(folder, "notes.bin").readBytes(), "plain file untouched")
        streamTypes(File(folder, "clip.mp4"))?.let {
            assertEquals(setOf("video", "audio"), it.toSet(), "merged file has both tracks")
        }
        assertFalse(File(t.trackFolder!!).exists(), "tracks deleted once merged")
        val state = loadBatchState(1, configDir.absolutePath).getOrThrow()
        assertEquals(File(folder, "clip.mp4").length(), state.files[1].downloaded.get(), "item size is the merged file's")
    }

    @Test
    fun failedTrack_failsOnlyThatItem() {
        val (v, _) = tracks()
        server.register("/plain", Endpoint(Random(2).nextBytes(5_000)))
        server.register("/v", Endpoint(v))
        server.register("/a", Endpoint(ByteArray(10)) { ConnPlan(failStatus = 404) })
        val t = task(2, listOf(plain("/plain", "notes.bin"), merged("/v", "/a", "clip.mp4")))

        val host = run(t)

        assertEquals(1, host.success!!.failedFiles, "the merged item failed")
        assertTrue(File(t.batchFolder, "notes.bin").isFile, "the plain file is there")
        assertFalse(File(t.batchFolder, "clip.mp4").exists(), "no merged file")
        val state = loadBatchState(2, configDir.absolutePath).getOrThrow()
        assertEquals(ChunkStatus.Failed, state.files[1].status.get(), "item failed")
        assertEquals(DownloadError.InvalidResponse, state.files[1].error.get(), "with the track's error")
    }

    @Test
    fun bothTracksAlreadyDownloaded_resumeOnlyMerges() {
        val (v, a) = tracks()
        val video = server.register("/v", Endpoint(v))
        val audio = server.register("/a", Endpoint(a))
        val t = task(3, listOf(merged("/v", "/a", "clip.mp4")))
        // As left by a stop during the merge: both tracks complete in the track folder.
        val tracks = File(t.trackFolder!!).apply { mkdirs() }
        File(tracks, "0.video").writeBytes(v)
        File(tracks, "0.audio").writeBytes(a)
        fun done(bytes: ByteArray) = BatchFile(
            0, AtomicLong(bytes.size.toLong()), AtomicLong(bytes.size.toLong()), AtomicReference(ChunkStatus.Finished),
        )
        val item = BatchFile(0, video = done(v), audio = done(a))
        saveBatchState(BatchTaskContext(3, t.batchFolder, listOf(item)), configDir.absolutePath)

        val host = run(t)

        assertEquals(0, host.success!!.failedFiles, "merged: ${host.failure}")
        assertTrue(File(t.batchFolder, "clip.mp4").isFile, "merged file written")
        assertEquals(0, video.connCount.get() + audio.connCount.get(), "no track fetched again")
    }

    @Test
    fun state_tracksSurviveSaveAndLoad() {
        fun track(status: ChunkStatus, done: Long, len: Long, error: DownloadError? = null) =
            BatchFile(1, AtomicLong(done), AtomicLong(len), AtomicReference(status), AtomicReference(error))
        val plainFile = BatchFile(0, AtomicLong(10), AtomicLong(10), AtomicReference(ChunkStatus.Finished), finalName = "a.bin")
        val mergedFile = BatchFile(
            1, finalName = "clip.mp4",
            video = track(ChunkStatus.Finished, 500, 500),
            audio = track(ChunkStatus.Failed, 20, 100, DownloadError.LinkExpired),
        )
        saveBatchState(BatchTaskContext(9, "/x/Batch", listOf(plainFile, mergedFile)), configDir.absolutePath)

        val back = loadBatchState(9, configDir.absolutePath).getOrThrow()
        assertNull(back.files[0].video, "plain item has no tracks")
        assertEquals("a.bin", back.files[0].finalName, "plain record unchanged")
        val m = back.files[1]
        assertEquals(500L, m.video!!.downloaded.get(), "video bytes")
        assertEquals(ChunkStatus.Finished, m.video!!.status.get(), "video done")
        assertEquals(20L, m.audio!!.downloaded.get(), "audio bytes")
        assertEquals(100L, m.audio!!.length.get(), "audio size")
        assertEquals(DownloadError.LinkExpired, m.audio!!.error.get(), "audio error")
        assertEquals(600L, m.size, "full size across tracks")
        assertEquals(520L, m.bytes, "bytes downloaded across tracks")
    }

    @Test
    fun state_withoutMergedItems_hasNoTail() {
        val ctx = BatchTaskContext(10, "/x/Batch", listOf(BatchFile(0, AtomicLong(5), AtomicLong(5), AtomicReference(ChunkStatus.Finished))))
        saveBatchState(ctx, configDir.absolutePath)
        val back = loadBatchState(10, configDir.absolutePath).getOrThrow()
        assertEquals(1, back.files.size, "one file")
        assertNull(back.files[0].audio, "no tracks")
    }

    @Test
    fun taskInfo_audioUrlAndTempDirRoundTrip() {
        val db = TaskInfoDB(configDir.absolutePath)
        val t = task(11, listOf(
            BatchItem("https://cdn.example/v.mp4", "clip.mp4", -1, 123, true, audioUrl = "https://cdn.example/a.mp4"),
            BatchItem("https://cdn.example/x.bin", "x.bin", -1, null, false),
        ))
        db.saveBatchTask(t)
        val back = db.getBatchTask(11)!!
        assertEquals(t.items, back.items, "items, with the audio URL")
        assertEquals(tempDir.absolutePath, back.tempDir, "temp dir")
        assertTrue(back.items[0].merged && !back.items[1].merged, "merged flag")
    }
}
