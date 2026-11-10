// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.impl

import xdm.core.media.muxer.Muxer
import xdm.core.storage.StorageFile
import xdm.core.media.muxer.transmux.ContainerWriter
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.iso.Mp4Demuxer
import xdm.core.media.muxer.transmux.mkv.MatroskaDemuxer
import xdm.core.media.muxer.transmux.mkv.MkvWriter
import xdm.core.media.muxer.transmux.mp4.Mp4Writer
import xdm.core.media.muxer.transmux.mp4.presentationStart
import xdm.core.media.muxer.transmux.sample.SampleSpill
import xdm.core.media.muxer.transmux.packed.PackedAudioDemuxer
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
 *  - Matroska / WebM segments (.webm/.mkv) via [MatroskaDemuxer]
 *  - HLS packed audio (.aac/.mp3/.ac3/.ec3: raw frames behind an ID3 timestamp) via [PackedAudioDemuxer]
 *
 * Inputs are remuxed into a single progressive MP4, or into Matroska (`.mkv`) when the requested
 * output path ends in `.mkv` (WebM, Matroska with DocType `webm`, for `.webm`); [container] overrides
 * the output's extension — the case DASH uses for WebM/VP8/VP9/Opus streams that MP4 can't
 * carry cleanly. The container of each segment list is detected from its bytes, so callers don't
 * need to tell us which it is. (The legacy [FFmpegMuxer] class is retained but no longer invoked.)
 */
class TransmuxingMuxer(
    @Suppress("UNUSED_PARAMETER") appDir: String,
    /**
     * The container to write (`mp4`, `m4a`, `mkv`, `webm`), when it is not the output name's
     * extension (the part file of a download is named by the task, not by the chosen container).
     */
    private val container: String? = null,
) : Muxer {
    private val stopFlag = AtomicBoolean(false)

    @Volatile
    override var timelineStartSec: Double? = null
        private set

    @Volatile
    override var lastError: Throwable? = null
        private set

    // ---- single list of (possibly A+V) segments ----
    override fun mux(
        segments: List<String>,
        output: StorageFile,
        progressCallback: (Int) -> Unit,
        tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
        discontinuous: Boolean,
    ): Boolean = transmux(output, tempDir, discontinuous) { writer ->
        demuxList(segments, writer, progressCallback)
    }

    // ---- two separate files (e.g. video + audio) ----
    override fun mux(
        file1: String, file2: String, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String
    ): Boolean = transmux(output, tempDir) { writer ->
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
        output: StorageFile,
        progressCallback: (Int) -> Unit,
        tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
        discontinuous: Boolean,
    ): Boolean = transmux(output, tempDir, discontinuous) { writer ->
        val total = (videoSegments.size + audioSegments.size).coerceAtLeast(1)
        val done = intArrayOf(0)
        val tick = { _: Int -> done[0]++; progressCallback(minOf(100, done[0] * 100 / total)) }
        // Interleaved, as ffmpeg interleaves by timestamp: the list that is behind (by the share of its
        // segments done; both cover the same time) goes next, so the mdat holds the two streams in
        // segment-sized turns instead of all video then all audio. Players reading over a network or
        // from a slow disk then don't have to jump between two distant halves of the file.
        val video = SegmentSource(videoSegments, writer)
        val audio = SegmentSource(audioSegments, writer)
        while (!stopFlag.get() && (video.hasNext() || audio.hasNext())) {
            val next = when {
                !audio.hasNext() -> video
                !video.hasNext() -> audio
                video.done() <= audio.done() -> video
                else -> audio
            }
            next.feedNext()
            tick(0)
        }
        video.finish() + audio.finish()
    }

    override fun stop() {
        stopFlag.set(true)
    }

    // ---- core ----

    /** [tempDir] holds scratch files (the MKV spool), so the output folder only needs the output's size. */
    private inline fun transmux(
        output: StorageFile, tempDir: String, discontinuous: Boolean = false, body: (ContainerWriter) -> List<Track>
    ): Boolean {
        lastError = null
        val format = (container ?: output.name.substringAfterLast('.', "")).lowercase()
        val writer: ContainerWriter = when (format) {
            "mkv", "mka" -> MkvWriter(output, tempDir) { stopFlag.get() }
            "webm", "weba" -> MkvWriter(output, tempDir, "webm") { stopFlag.get() }
            else -> Mp4Writer(output, repairTimeline = discontinuous)
        }
        // Sample records of long streams spill to scratch files here; dropped when muxing ends.
        val spill = SampleSpill.open(File(tempDir))
        return try {
            val tracks = body(writer)
            if (stopFlag.get()) { writer.abort(); false }
            else {
                // The earliest presented PTS, which the MP4 writer aligns the tracks on: time zero of the output.
                timelineStartSec = tracks.filter { it.samples.isNotEmpty() && it.timescale > 0 }
                    .minOfOrNull { presentationStart(it.samples).toDouble() / it.timescale }
                val ok = writer.finish(tracks)
                if (!ok) output.delete()
                ok
            }
        } catch (ex: Throwable) {
            writer.abort()
            // A stop can surface as an error from a closed handle: that is a pause, not a failure.
            if (!stopFlag.get()) {
                Logger.error("XDM", "Transmux failed", ex)
                lastError = ex
            }
            false
        } finally {
            spill.close()
        }
    }

    /** Demuxes a (homogeneous) list of segments into [writer], picking the demuxer by container. */
    private fun demuxList(segments: List<String>, writer: SampleSink, progress: (Int) -> Unit): List<Track> {
        val source = SegmentSource(segments, writer)
        while (!stopFlag.get() && source.hasNext()) {
            source.feedNext()
            progress((source.fed * 100) / segments.size)
        }
        return source.finish()
    }

    /**
     * One playlist's segments fed one at a time into the demuxer for their container (sniffed from
     * the first segment); demuxer state carries across segments.
     */
    private inner class SegmentSource(private val segments: List<String>, writer: SampleSink) {
        var fed = 0
            private set
        private val feedOne: (String) -> Unit
        private val end: () -> List<Track>

        init {
            when (if (segments.isEmpty()) Container.MP4 else containerOf(segments.first())) {
                Container.TS -> {
                    val demux = TsDemuxer(writer)
                    val buffer = ByteArray(256 * 1024)
                    feedOne = { seg ->
                        FileInputStream(seg).use { input ->
                            while (!stopFlag.get()) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                demux.feed(buffer, n)
                            }
                        }
                    }
                    end = { demux.finish(); demux.tracks }
                }
                Container.MKV -> MatroskaDemuxer(writer).let { d -> feedOne = { d.parseSegment(it) }; end = { d.finish(); d.tracks } }
                Container.PACKED_AUDIO -> PackedAudioDemuxer(writer).let { d -> feedOne = { d.parseSegment(it) }; end = { d.finish(); d.tracks } }
                Container.MP4 -> Mp4Demuxer(writer).let { d -> feedOne = { d.parseSegment(it) }; end = { d.finish(); d.tracks } }
            }
        }

        fun hasNext() = fed < segments.size

        /** Share of the segments fed so far. */
        fun done() = if (segments.isEmpty()) 1.0 else fed.toDouble() / segments.size

        fun feedNext() {
            feedOne(segments[fed])
            fed++
        }

        fun finish(): List<Track> = if (segments.isEmpty()) emptyList() else end()
    }

    private enum class Container { TS, MP4, MKV, PACKED_AUDIO }

    /**
     * Sniffs the container from the file header: EBML magic => Matroska, MP4 box type => MP4,
     * 0x47 => TS (or 0x47 after M2TS's 4-byte header, repeating every 192 bytes), ID3 tag or a raw
     * audio sync word => packed audio, else MP4.
     */
    private fun containerOf(path: String): Container {
        RandomAccessFile(path, "r").use { raf ->
            val head = ByteArray(minOf((M2TS_PACKET + 5).toLong(), raf.length()).toInt())
            raf.readFully(head)
            // EBML magic (0x1A45DFA3) => Matroska/WebM.
            if (head.size >= 4 && (head[0].toInt() and 0xFF) == 0x1A && (head[1].toInt() and 0xFF) == 0x45 &&
                (head[2].toInt() and 0xFF) == 0xDF && (head[3].toInt() and 0xFF) == 0xA3
            ) return Container.MKV
            if (head.size >= 8) {
                val type = String(head, 4, 4, Charsets.US_ASCII)
                if (type in MP4_TOP_LEVEL_TYPES) return Container.MP4
            }
            // A media segment that begins mid-Matroska-Segment starts with a bare Cluster (0x1F43B675).
            if (head.size >= 4 && (head[0].toInt() and 0xFF) == 0x1F && (head[1].toInt() and 0xFF) == 0x43 &&
                (head[2].toInt() and 0xFF) == 0xB6 && (head[3].toInt() and 0xFF) == 0x75
            ) return Container.MKV
            if (head.isNotEmpty() && (head[0].toInt() and 0xFF) == 0x47) return Container.TS
            if (head.size > M2TS_PACKET + 4 && (head[4].toInt() and 0xFF) == 0x47 &&
                (head[M2TS_PACKET + 4].toInt() and 0xFF) == 0x47
            ) return Container.TS
            if (PackedAudioDemuxer.isId3(head, 0) || isAudioSync(head)) return Container.PACKED_AUDIO
            return Container.MP4
        }
    }

    /** ADTS / MPEG audio (0xFFE sync) or AC-3 / E-AC-3 (0x0B77) frame at the start of [head]. */
    private fun isAudioSync(head: ByteArray): Boolean {
        if (head.size < 2) return false
        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF
        return (b0 == 0xFF && (b1 and 0xE0) == 0xE0) || (b0 == 0x0B && b1 == 0x77)
    }

    private companion object {
        const val M2TS_PACKET = 192
        val MP4_TOP_LEVEL_TYPES = setOf("ftyp", "styp", "moov", "moof", "sidx", "free", "skip", "mdat")
    }
}
