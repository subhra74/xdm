// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.impl

import xdm.core.media.muxer.MuxContainer
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
import java.util.IdentityHashMap
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
 * Inputs are remuxed into a single progressive MP4 or into Matroska, by [MuxContainer.plan]: the
 * requested container ([container], else the output name's extension) unless a segment list is
 * WebM/Matroska, which always gives Matroska (`mkv`, `mka`); a WebM request is written as `mkv`.
 * [outputContainer] then says what was written, and the caller names the file after it. The
 * container of each segment list is detected from its bytes, so callers don't need to tell us
 * which it is. (The legacy [FFmpegMuxer] class is retained but no longer invoked.)
 */
class TransmuxingMuxer(
    @Suppress("UNUSED_PARAMETER") appDir: String,
    /**
     * The container asked for (`mp4`, `m4a`, `mkv`, ...), when it is not the output name's extension
     * (the part file of a download is named by the task, not by the chosen container).
     */
    private val container: String? = null,
) : Muxer {
    private val stopFlag = AtomicBoolean(false)

    /** A segment list switched containers in this mux: its parts have their own timelines. */
    private var mixedContainers = false

    @Volatile
    override var timelineStartSec: Double? = null
        private set

    @Volatile
    override var lastError: Throwable? = null
        private set

    @Volatile
    override var outputContainer: String? = null
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
    ): Boolean = transmux(output, tempDir, listOf(segments), discontinuous) { writer ->
        demuxList(segments, writer, progressCallback)
    }

    // ---- two separate files (e.g. video + audio) ----
    override fun mux(
        file1: String, file2: String, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String
    ): Boolean = transmux(output, tempDir, listOf(listOf(file1), listOf(file2))) { writer ->
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
    ): Boolean = transmux(output, tempDir, listOf(audioSegments, videoSegments), discontinuous) { writer ->
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

    /**
     * [tempDir] holds scratch files (the MKV spool), so the output folder only needs the output's size.
     * [inputs] are the segment lists, looked at (their first segments) to pick the container.
     */
    private inline fun transmux(
        output: StorageFile, tempDir: String, inputs: List<List<String>>, discontinuous: Boolean = false,
        body: (ContainerWriter) -> List<Track>
    ): Boolean {
        lastError = null
        outputContainer = null
        mixedContainers = false
        val requested = container ?: output.name.substringAfterLast('.', "")
        val matroskaInput = inputs.any { it.isNotEmpty() && containerOf(it.first()) == Container.MKV }
        val format = MuxContainer.plan(requested, matroskaInput)
        if (!format.equals(requested, ignoreCase = true)) Logger.info("XDM", "Muxing as $format (asked for '$requested')")
        val writer: ContainerWriter =
            if (MuxContainer.isMatroska(format)) MkvWriter(output, tempDir) { stopFlag.get() }
            else Mp4Writer(output, repairTimeline = discontinuous)
        // Sample records of long streams spill to scratch files here; dropped when muxing ends.
        val spill = SampleSpill.open(File(tempDir))
        return try {
            val tracks = body(writer)
            // An ad or other insert in another container restarts its timestamps, whatever the manifest says.
            if (mixedContainers && writer is Mp4Writer) writer.repairTimeline = true
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
                if (!ok) output.delete() else outputContainer = format
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

    /** One demuxer instance over part of a segment list. */
    private class Demux(val container: Container, writer: SampleSink, stop: AtomicBoolean) {
        val feed: (String, Long) -> Unit
        private val end: () -> Unit
        val tracks: () -> List<Track>
        var finished = false
            private set

        init {
            when (container) {
                Container.TS -> {
                    val demux = TsDemuxer(writer)
                    val buffer = ByteArray(256 * 1024)
                    feed = { seg, from ->
                        FileInputStream(seg).use { input ->
                            skipFully(input, from)
                            while (!stop.get()) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                demux.feed(buffer, n)
                            }
                        }
                    }
                    end = { demux.finish() }; tracks = { demux.tracks }
                }
                Container.MKV -> MatroskaDemuxer(writer).let { d -> feed = { seg, from -> d.parseSegment(seg, from) }; end = { d.finish() }; tracks = { d.tracks } }
                Container.PACKED_AUDIO -> PackedAudioDemuxer(writer).let { d -> feed = { seg, from -> d.parseSegment(seg, from) }; end = { d.finish() }; tracks = { d.tracks } }
                Container.MP4 -> Mp4Demuxer(writer).let { d -> feed = { seg, from -> d.parseSegment(seg, from) }; end = { d.finish() }; tracks = { d.tracks } }
            }
        }

        /**
         * Whether the demuxer can be left and fed again later. TS and packed audio hold back the
         * last frames until finished, so they are finished when left and a new one starts on return;
         * MP4 and Matroska keep their init segment's tracks for the media segments that follow.
         */
        val resumable get() = container == Container.MP4 || container == Container.MKV

        fun finish() { if (!finished) { finished = true; end() } }
    }

    /**
     * One playlist's segments fed one at a time into the demuxer for their container; demuxer state
     * carries across segments. Each segment is read from where its media starts ([SegmentProbe]),
     * past any bytes the server put in front of it. The container comes from the first segment, and
     * a later segment found to be another one (a TS ad inside an fMP4 stream, or the reverse) goes to
     * a demuxer for that; the tracks of all of them are then joined by [TrackMerge]. A segment whose
     * container isn't recognised stays with the current demuxer.
     */
    private inner class SegmentSource(private val segments: List<String>, private val writer: SampleSink) {
        var fed = 0
            private set
        private val first: Container = if (segments.isEmpty()) Container.MP4 else containerOf(segments.first())
        /** Every demuxer used, in the order started; [active] is the one being fed. */
        private val used = ArrayList<Demux>()
        private var active: Demux? = null
        /** Samples of each track already put into a [TrackMerge.Run]. */
        private val recorded = IdentityHashMap<Track, Int>()
        private val runs = ArrayList<TrackMerge.Run>()
        private var switches = 0
        /** Segments whose media started past byte 0, for one log line at the end. */
        private var shifted = 0
        private var maxShift = 0L

        fun hasNext() = fed < segments.size

        /** Share of the segments fed so far. */
        fun done() = if (segments.isEmpty()) 1.0 else fed.toDouble() / segments.size

        fun feedNext() {
            val seg = segments[fed]
            val found = SegmentProbe.locate(seg)
            val current = active
            val target = when {
                current == null -> first
                found != null && found.container != current.container -> found.container
                else -> current.container
            }
            val demux = if (current != null && current.container == target) current else switchTo(target, seg)
            demux.feed(seg, startOf(found, target))
            fed++
            record()
        }

        private fun switchTo(target: Container, seg: String): Demux {
            active?.let { left ->
                switches++
                mixedContainers = true
                Logger.info("XDM", "Segment ${fed + 1} is $target, after ${left.container}: switching demuxer ($seg)")
                if (!left.resumable) { left.finish(); record() }
            }
            val next = used.lastOrNull { it.container == target && !it.finished } ?: Demux(target, writer, stopFlag).also { used.add(it) }
            active = next
            return next
        }

        /** Notes the samples each track gained since the last call, as runs in reading order. */
        private fun record() {
            for (d in used) for (t in d.tracks()) {
                val n = t.samples.size
                val before = recorded[t] ?: 0
                if (n > before) { runs.add(TrackMerge.Run(t, before, n)); recorded[t] = n }
            }
        }

        /**
         * Where this segment's media starts. A segment whose own start is another container (or none
         * found) is read from byte 0, as before.
         */
        private fun startOf(found: SegmentProbe.Located?, container: Container): Long {
            if (found == null || found.container != container || found.offset == 0L) return 0
            shifted++
            if (found.offset > maxShift) maxShift = found.offset
            return found.offset
        }

        fun finish(): List<Track> {
            if (segments.isEmpty()) return emptyList()
            if (shifted > 0) {
                Logger.info("XDM", "$first: $shifted of ${segments.size} segments had other bytes in front of the media (up to $maxShift), skipped")
            }
            for (d in used) d.finish()
            record()
            val tracks = if (used.size == 1) used[0].tracks() else {
                Logger.info("XDM", "Containers changed $switches times in ${segments.size} segments: joining the tracks of ${used.size} demuxers")
                TrackMerge.merge(runs, used.map { it.tracks() })
            }
            if (tracks.none { it.samples.isNotEmpty() }) {
                Logger.error("XDM", "$first: no audio or video found in ${segments.size} segments, first ${segments.first()}")
            }
            return tracks
        }
    }

    /** A segment list's container, from its first segment: its bytes, else its name (from its URL), else MP4. */
    private fun containerOf(firstSegment: String): Container =
        SegmentProbe.locate(firstSegment)?.container ?: SegmentProbe.fromName(firstSegment) ?: Container.MP4

    private companion object {
        fun skipFully(input: FileInputStream, n: Long) {
            var left = n
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) break
                left -= skipped
            }
        }
    }
}

/** The segments held no audio or video that the transmuxer recognised. */
class NoMediaException(message: String) : java.io.IOException(message)
