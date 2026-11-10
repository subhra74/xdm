package xdm.app.ytdlp

import xdm.core.downloaders.ExtractorSource
import xdm.core.media.muxer.Muxer
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.storage.StorageFile
import xdm.core.util.Logger
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Merges a Video download (yt-dlp) with the built-in transmuxer, into the container picked in the
 * dialog ([ExtractorSource.container]), whatever the part file is named.
 *
 * yt-dlp's formats come as HLS segments, fragments that stand on their own (handed over as they are),
 * or a plain file cut into byte ranges at arbitrary offsets ([ExtractorSource.pieces]). The demuxers
 * read whole files, so a track of byte ranges is joined into one file in the temp folder first; a single
 * track of byte ranges needs no remux at all, and its pieces are joined straight into the output.
 */
class YtDlpMuxer(
    private val source: ExtractorSource,
    private val appDir: String,
) : Muxer {
    private val stopped = AtomicBoolean(false)

    @Volatile
    private var inner: TransmuxingMuxer? = null

    @Volatile
    private var error: Throwable? = null

    override val lastError: Throwable? get() = error ?: inner?.lastError
    override val timelineStartSec: Double? get() = inner?.timelineStartSec

    override fun mux(
        segments: List<String>, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String,
        independentSegement: Boolean, isMp4: Boolean, discontinuous: Boolean,
    ): Boolean {
        if (source.pieces) return attempt { concat(segments, output) }
        return transmuxer().mux(segments, output, progressCallback, tempDir, independentSegement, isMp4, discontinuous)
    }

    override fun mux(
        file1: String, file2: String, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String,
    ): Boolean = transmuxer().mux(file1, file2, output, progressCallback, tempDir)

    override fun mux(
        audioSegments: List<String>, videoSegments: List<String>, output: StorageFile,
        progressCallback: (Int) -> Unit, tempDir: String, independentSegement: Boolean, isMp4: Boolean,
        discontinuous: Boolean,
    ): Boolean {
        val tracks = listOf(videoSegments, audioSegments).filter { it.isNotEmpty() }
        if (source.pieces && tracks.size == 1) return attempt { concat(tracks[0], output) }
        if (!source.pieces) {
            return transmuxer().mux(audioSegments, videoSegments, output, progressCallback, tempDir,
                independentSegement, isMp4, discontinuous)
        }
        val made = ArrayList<File>()
        return try {
            val video = join(videoSegments, tempDir).also { made.add(it) }
            val audio = join(audioSegments, tempDir).also { made.add(it) }
            transmuxer().mux(video.path, audio.path, output, progressCallback, tempDir)
        } catch (e: Exception) {
            if (!stopped.get()) {
                Logger.error("ytdlp", "Merge failed", e)
                error = e
            }
            false
        } finally {
            made.forEach { it.delete() }
        }
    }

    override fun stop() {
        stopped.set(true)
        inner?.stop()
    }

    private fun transmuxer(): TransmuxingMuxer {
        error = null
        return TransmuxingMuxer(appDir, source.container).also {
            inner = it
            if (stopped.get()) it.stop()
        }
    }

    private inline fun attempt(body: () -> Unit): Boolean {
        error = null
        return try {
            body()
            !stopped.get()
        } catch (e: Exception) {
            if (!stopped.get()) {
                Logger.error("ytdlp", "Joining the pieces failed", e)
                error = e
            }
            false
        }
    }

    /** The pieces joined into [output]: the file they were cut from. */
    private fun concat(pieces: List<String>, output: StorageFile) {
        try {
            output.open(create = true).use { o ->
                o.setLength(0)
                copyAll(pieces) { b, n -> o.write(b, 0, n) }
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        }
    }

    private fun join(pieces: List<String>, tempDir: String): File {
        val f = File(tempDir, "joined-${UUID.randomUUID()}.bin")
        try {
            f.outputStream().use { o -> copyAll(pieces) { b, n -> o.write(b, 0, n) } }
        } catch (e: Exception) {
            f.delete()
            throw e
        }
        return f
    }

    private inline fun copyAll(pieces: List<String>, write: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(1 shl 20)
        for (piece in pieces) {
            File(piece).inputStream().use { input ->
                while (true) {
                    if (stopped.get()) throw IOException("stopped")
                    val n = input.read(buf)
                    if (n < 0) break
                    write(buf, n)
                }
            }
        }
    }
}
