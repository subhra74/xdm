// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.impl

import xdm.core.media.muxer.Muxer
import xdm.core.media.muxer.impl.SegmentProbe.Container
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
            else if (tracks.none { it.samples.isNotEmpty() }) {
                // Nothing the demuxers recognised: say so, rather than fail with no reason.
                writer.abort()
                lastError = NoMediaException("No audio or video found in the downloaded segments")
                false
            } else {
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
     * the first segment); demuxer state carries across segments. Each segment is read from where its
     * media starts ([SegmentProbe]), past any bytes the server put in front of it.
     */
    private inner class SegmentSource(private val segments: List<String>, writer: SampleSink) {
        var fed = 0
            private set
        private val feedOne: (String, Long) -> Unit
        private val end: () -> List<Track>
        // When the bytes don't say, the segment's name (from its URL) may; else MP4, as before.
        private val container: Container =
            if (segments.isEmpty()) Container.MP4
            else SegmentProbe.locate(segments.first())?.container ?: SegmentProbe.fromName(segments.first()) ?: Container.MP4
        /** Segments whose media started past byte 0, for one log line at the end. */
        private var shifted = 0
        private var maxShift = 0L

        init {
            when (container) {
                Container.TS -> {
                    val demux = TsDemuxer(writer)
                    val buffer = ByteArray(256 * 1024)
                    feedOne = { seg, from ->
                        FileInputStream(seg).use { input ->
                            skipFully(input, from)
                            while (!stopFlag.get()) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                demux.feed(buffer, n)
                            }
                        }
                    }
                    end = { demux.finish(); demux.tracks }
                }
                Container.MKV -> MatroskaDemuxer(writer).let { d -> feedOne = { seg, from -> d.parseSegment(seg, from) }; end = { d.finish(); d.tracks } }
                Container.PACKED_AUDIO -> PackedAudioDemuxer(writer).let { d -> feedOne = { seg, from -> d.parseSegment(seg, from) }; end = { d.finish(); d.tracks } }
                Container.MP4 -> Mp4Demuxer(writer).let { d -> feedOne = { seg, from -> d.parseSegment(seg, from) }; end = { d.finish(); d.tracks } }
            }
        }

        /**
         * Where this segment's media starts. A segment whose own start is another container (or none
         * found) is read from byte 0, as before.
         */
        private fun startOf(seg: String): Long {
            val found = SegmentProbe.locate(seg) ?: return 0
            if (found.container != container || found.offset == 0L) return 0
            shifted++
            if (found.offset > maxShift) maxShift = found.offset
            return found.offset
        }

        fun hasNext() = fed < segments.size

        /** Share of the segments fed so far. */
        fun done() = if (segments.isEmpty()) 1.0 else fed.toDouble() / segments.size

        fun feedNext() {
            val seg = segments[fed]
            feedOne(seg, startOf(seg))
            fed++
        }

        fun finish(): List<Track> {
            if (segments.isEmpty()) return emptyList()
            if (shifted > 0) {
                Logger.info("XDM", "$container: $shifted of ${segments.size} segments had other bytes in front of the media (up to $maxShift), skipped")
            }
            return end().also { tracks ->
                if (tracks.none { it.samples.isNotEmpty() }) {
                    Logger.error("XDM", "$container: no audio or video found in ${segments.size} segments, first ${segments.first()}")
                }
            }
        }
    }

    private fun skipFully(input: FileInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) break
            left -= skipped
        }
    }
}

/** The segments held no audio or video that the transmuxer recognised. */
class NoMediaException(message: String) : java.io.IOException(message)
