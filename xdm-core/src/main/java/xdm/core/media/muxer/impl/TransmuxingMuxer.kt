package xdm.core.media.muxer.impl

import xdm.core.media.muxer.Muxer
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.iso.Mp4Demuxer
import xdm.core.media.muxer.transmux.mp4.Mp4Writer
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.media.muxer.transmux.ts.TsDemuxer
import xdm.core.util.Logger
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-Kotlin transmuxer implementing [Muxer]. It handles both container families used by HLS/DASH
 * downloads natively, with no external ffmpeg:
 *  - MPEG-TS segments (.ts) via [TsDemuxer]
 *  - ISO-BMFF / fragmented-MP4 / CMAF segments (.mp4/.m4s/.fmp4) via [Mp4Demuxer]
 *
 * All inputs are remuxed into a single progressive MP4. The container of each segment list is
 * detected from its bytes, so callers don't need to tell us which it is. (The legacy [FFmpegMuxer]
 * class is retained but no longer invoked.)
 */
class TransmuxingMuxer(@Suppress("UNUSED_PARAMETER") appDir: String) : Muxer {
    private val stopFlag = AtomicBoolean(false)

    // ---- single list of (possibly A+V) segments ----
    override fun mux(
        segments: List<String>,
        outputFile: String,
        progressCallback: (Int) -> Unit,
        tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
    ): Boolean = transmux(outputFile) { writer ->
        demuxList(segments, writer, progressCallback)
    }

    // ---- two separate files (e.g. video + audio) ----
    override fun mux(
        file1: String, file2: String, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String
    ): Boolean = transmux(outputFile) { writer ->
        val tracks = ArrayList<Track>()
        tracks += demuxList(listOf(file1), writer) {}
        tracks += demuxList(listOf(file2), writer) {}
        progressCallback(100)
        tracks
    }

    // ---- separate audio playlist + video playlist ----
    override fun mux(
        audioSegments: List<String>,
        videoSegments: List<String>,
        outputFile: String,
        progressCallback: (Int) -> Unit,
        tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
    ): Boolean = transmux(outputFile) { writer ->
        val total = (videoSegments.size + audioSegments.size).coerceAtLeast(1)
        val done = intArrayOf(0)
        val tick = { _: Int -> done[0]++; progressCallback(minOf(100, done[0] * 100 / total)) }
        val tracks = ArrayList<Track>()
        // Video first, then audio; both stream into the same mdat (one writer).
        tracks += demuxList(videoSegments, writer, tick)
        tracks += demuxList(audioSegments, writer, tick)
        tracks
    }

    override fun stop() {
        stopFlag.set(true)
    }

    // ---- core ----

    private inline fun transmux(outputFile: String, body: (Mp4Writer) -> List<Track>): Boolean {
        val writer = Mp4Writer(outputFile)
        return try {
            val tracks = body(writer)
            if (stopFlag.get()) { writer.abort(); false }
            else {
                val ok = writer.finish(tracks)
                if (!ok) File(outputFile).delete()
                ok
            }
        } catch (ex: Throwable) {
            Logger.error("XDM", "Transmux failed", ex)
            writer.abort()
            false
        }
    }

    /** Demuxes a (homogeneous) list of segments into [writer], picking the demuxer by container. */
    private fun demuxList(segments: List<String>, writer: SampleSink, progress: (Int) -> Unit): List<Track> {
        if (segments.isEmpty()) return emptyList()
        return if (isMpegTs(segments.first())) {
            val demux = TsDemuxer(writer)
            feedTs(segments, demux, progress)
            demux.finish()
            demux.tracks
        } else {
            val demux = Mp4Demuxer(writer)
            for ((i, seg) in segments.withIndex()) {
                if (stopFlag.get()) break
                demux.parseSegment(seg)
                progress(((i + 1) * 100) / segments.size)
            }
            demux.finish()
            demux.tracks
        }
    }

    private fun feedTs(segments: List<String>, demux: TsDemuxer, progress: (Int) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        for ((i, seg) in segments.withIndex()) {
            if (stopFlag.get()) return
            FileInputStream(seg).use { input ->
                while (true) {
                    if (stopFlag.get()) return
                    val n = input.read(buffer)
                    if (n <= 0) break
                    demux.feed(buffer, n)
                }
            }
            progress(((i + 1) * 100) / segments.size)
        }
    }

    /** Sniffs the container: 0x47 sync at packet stride => MPEG-TS; otherwise treat as MP4/ISO-BMFF. */
    private fun isMpegTs(path: String): Boolean {
        RandomAccessFile(path, "r").use { raf ->
            val head = ByteArray(minOf(8, raf.length().toInt()))
            raf.readFully(head)
            if (head.size >= 8) {
                val type = String(head, 4, 4, Charsets.US_ASCII)
                if (type in MP4_TOP_LEVEL_TYPES) return false
            }
            return head.isNotEmpty() && (head[0].toInt() and 0xFF) == 0x47
        }
    }

    private companion object {
        val MP4_TOP_LEVEL_TYPES = setOf("ftyp", "styp", "moov", "moof", "sidx", "free", "skip", "mdat")
    }
}
