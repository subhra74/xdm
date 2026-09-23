import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.streaming.downloader.StreamingChunk
import xdm.core.downloaders.web.streaming.downloader.StreamingChunkRetriever
import xdm.core.network.http.impl.HttpClientImpl
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A segment that resumes must end up with its *total* length, not the remaining bytes the 206
 * reports. Getting that wrong made the per-segment progress read as more than 100% after a resume.
 */
class TestStreamingResumeProgress {
    private lateinit var root: File
    private lateinit var server: FileServer
    private lateinit var client: HttpClientImpl

    private val total = 64 * 1024
    private val alreadyOnDisk = 20 * 1024

    @Before
    fun setup() {
        root = java.nio.file.Files.createTempDirectory("xdm-resume-progress").toFile()
        server = FileServer(root)
        client = HttpClientImpl(4)
    }

    @After
    fun tearDown() {
        client.close()
        server.stop()
        root.deleteRecursively()
    }

    private fun chunk(url: String, downloaded: Long) = StreamingChunk(
        id = 1,
        sequence = 0,
        downloaded = AtomicLong(downloaded),
        length = AtomicLong(0),
        status = AtomicReference(ChunkStatus.Ready),
        error = AtomicReference<DownloadError?>(null),
        url = url,
        byteRange = null,
        keyUrl = null,
        tag = "v",
        fileHandle = AtomicReference<RandomAccessFile?>(null),
        encrypted = false,
    )

    private fun run(piece: StreamingChunk, tempFile: File) {
        StreamingChunkRetriever(
            piece = piece,
            httpClient = client,
            stopFlag = AtomicBoolean(false),
            headers = null,
            cookie = null,
            tempDir = root.absolutePath,
            progressCallback = { _, _ -> },
            fileNameCallback = { tempFile.absolutePath },
            maxRetries = 1,
            completionCallback = { },
        ).run()
    }

    @Test
    fun `resumed segment reports the total length`() {
        val body = ByteArray(total) { (it % 251).toByte() }
        File(root, "seg.ts").writeBytes(body)

        // Half-downloaded segment, as a pause would leave it.
        val tempFile = File(root, "seg.part")
        tempFile.writeBytes(body.copyOf(alreadyOnDisk))

        val piece = chunk(server.url("seg.ts"), alreadyOnDisk.toLong())
        run(piece, tempFile)

        assertEquals("segment must finish", ChunkStatus.Finished, piece.status.get())
        assertEquals("length must be the whole segment", total.toLong(), piece.length.get())
        assertEquals("downloaded must be the whole segment", total.toLong(), piece.downloaded.get())
        assertEquals("the resumed file must match the source", body.toList(), tempFile.readBytes().toList())
    }

    @Test
    fun `fresh segment reports the total length`() {
        val body = ByteArray(total) { (it % 251).toByte() }
        File(root, "seg.ts").writeBytes(body)
        val tempFile = File(root, "fresh.part")

        val piece = chunk(server.url("seg.ts"), 0)
        run(piece, tempFile)

        assertEquals(ChunkStatus.Finished, piece.status.get())
        assertEquals(total.toLong(), piece.length.get())
    }
}
