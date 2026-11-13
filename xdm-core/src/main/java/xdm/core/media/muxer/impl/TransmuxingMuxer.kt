// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.impl

import xdm.core.media.muxer.MuxContainer
import xdm.core.media.muxer.Muxer
import xdm.core.media.muxer.OutputKind
import xdm.core.media.muxer.SegmentInfo
import xdm.core.media.muxer.transmux.mkv.TrackLostException
import xdm.core.storage.asOutputStream
import xdm.core.util.isOutOfSpace
import xdm.core.media.muxer.impl.SegmentProbe.Container
import xdm.core.storage.StorageFile
import xdm.core.media.muxer.transmux.ContainerWriter
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.flv.FlvDemuxer
import xdm.core.media.muxer.transmux.iso.Mp4Demuxer
import xdm.core.media.muxer.transmux.mkv.MatroskaDemuxer
import xdm.core.media.muxer.transmux.mkv.MkvWriter
import xdm.core.media.muxer.transmux.mp4.Mp4Writer
import xdm.core.media.muxer.transmux.sample.presentationStart
import xdm.core.media.muxer.transmux.sample.SampleSpill
import xdm.core.media.muxer.transmux.packed.PackedAudioDemuxer
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.media.muxer.transmux.ts.TsDemuxer
import xdm.core.util.Logger
import java.io.File
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
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

    @Volatile
    override var outputKind: OutputKind = OutputKind.REMUXED
        private set

    /** Segment files as their playlist describes them ([setSegmentInfo]): sections for the TS merge. */
    @Volatile
    private var segmentInfo: Map<String, SegmentInfo> = emptyMap()

    override fun setSegmentInfo(info: Map<String, SegmentInfo>) { segmentInfo = info }

    /** Tests: make every remux attempt fail, so the fallbacks after it run on ordinary input. */
    internal var failRemuxForTest = false

    /** Tests: make the one-file fallbacks fail too, so the downloader's own fallbacks run. */
    internal var failOneFileFallbacksForTest = false

    /** Audio/video streams the demuxers of the current pass left out: the remux is then not used. */
    private val lost = LinkedHashSet<String>()

    // ---- single list of (possibly A+V) segments ----
    override fun mux(
        segments: List<String>,
        output: StorageFile,
        progressCallback: (Int) -> Unit,
        tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
        discontinuous: Boolean,
    ): Boolean {
        outputKind = OutputKind.REMUXED
        if (remux(output, tempDir, listOf(segments), discontinuous) { writer -> demuxList(segments, writer, progressCallback) }) return true
        if (!mayFallBack()) return false
        return singleListFallback(segments, output)
    }

    // ---- fallbacks: a download is never lost to a remux that can't be done ----

    /** Not after a stop or a full disk: those pause the download, and the next start tries again. */
    private fun mayFallBack() = !stopFlag.get() && !isOutOfSpace(lastError)

    /**
     * The remux, and once more as MP4 when Matroska couldn't name a track ([TrackLostException]): MP4
     * copies a sample entry it doesn't know as it is.
     */
    private fun remux(
        output: StorageFile, tempDir: String, inputs: List<List<String>>, discontinuous: Boolean,
        body: (ContainerWriter) -> List<Track>,
    ): Boolean {
        if (transmux(output, tempDir, inputs, discontinuous, null, body)) return true
        if (mayFallBack() && lastError is TrackLostException) {
            Logger.info("XDM", "Fallback: ${lastError?.message}; muxing as MP4 instead")
            if (transmux(output, tempDir, inputs, discontinuous, "mp4", body)) return true
        }
        return false
    }

    /**
     * One list that couldn't be remuxed without leaving something out: TS segments joined into one
     * `.ts`, an fMP4 rendition joined into one fragmented `.mp4`, a container no demuxer reads published
     * as it is. Keeps the remux's error when none applies (the downloader then keeps the segments).
     */
    private fun singleListFallback(segments: List<String>, output: StorageFile): Boolean {
        val reason = lastError
        if (failOneFileFallbacksForTest) return false
        val located = segments.map { SegmentProbe.locate(it) }
        val done = when {
            located.all { it?.container == Container.TS } ->
                join(segments.zip(located.map { it!!.offset }), output, "ts", OutputKind.TS_FALLBACK, "TS segments joined into one .ts")
            singleInitFmp4(segments, located) ->
                join(segments.zip(located.map { it!!.offset }), output, "mp4", OutputKind.AS_IS, "fMP4 segments joined into one fragmented .mp4")
            else -> publishAsIs(segments, output)
        }
        if (!done && mayFallBack() && lastError == null) lastError = reason
        return done
    }

    /**
     * Two renditions that couldn't be remuxed together: merged into one `.ts` when the [primary] is TS
     * and the [secondary] TS or HLS packed audio ([TsMerge]). Otherwise the remux's error stays.
     */
    private fun mergeFallback(primary: List<String>, secondary: List<String>, output: StorageFile): Boolean {
        if (primary.isEmpty()) return singleListFallback(secondary, output)
        if (secondary.isEmpty()) return singleListFallback(primary, output)
        val reason = lastError
        if (failOneFileFallbacksForTest) return false
        val p = primary.map { SegmentProbe.locate(it) }
        val s = secondary.map { SegmentProbe.locate(it) }
        val secondaryKind = s.map { it?.container }.toSet().singleOrNull()
        if (p.any { it?.container != Container.TS } || (secondaryKind != Container.TS && secondaryKind != Container.PACKED_AUDIO)) {
            Logger.info("XDM", "Fallback: no one-file merge for these renditions (${p.map { it?.container }.toSet()} + ${s.map { it?.container }.toSet()})")
            lastError = reason
            return false
        }
        fun inputs(list: List<String>, loc: List<SegmentProbe.Located?>) =
            list.indices.map { TsMerge.Input(list[it], loc[it]!!.offset, segmentInfo[list[it]]?.section) }
        return write(output, "ts", OutputKind.TS_FALLBACK, "renditions merged into one .ts (${secondaryKind.name.lowercase()} secondary)") { out ->
            TsMerge(stopFlag).merge(inputs(primary, p), inputs(secondary, s), secondaryKind == Container.PACKED_AUDIO, out)
        }.also { if (!it && mayFallBack() && lastError == null) lastError = reason }
    }

    /** An fMP4 rendition: its first segment is the only one with a `moov` (the init), the rest are fragments. */
    private fun singleInitFmp4(segments: List<String>, located: List<SegmentProbe.Located?>): Boolean {
        if (segments.size < 2 || located.any { it?.container != Container.MP4 }) return false
        val inits = segments.indices.filter { "moov" in topLevelBoxes(segments[it], located[it]!!.offset) }
        return inits == listOf(0) && segments.drop(1).indices.all { "moof" in topLevelBoxes(segments[it + 1], located[it + 1]!!.offset) }
    }

    private fun topLevelBoxes(path: String, from: Long): Set<String> = RandomAccessFile(path, "r").use { raf ->
        val types = HashSet<String>()
        var pos = from
        val h = ByteArray(16)
        while (pos + 8 <= raf.length() && types.size < 32) {
            raf.seek(pos); raf.readFully(h, 0, 8)
            var size = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
            types.add(String(h, 4, 4, Charsets.US_ASCII))
            if (size == 1L && pos + 16 <= raf.length()) { raf.readFully(h, 8, 8); size = (8 until 16).fold(0L) { v, i -> (v shl 8) or (h[i].toLong() and 0xFF) } }
            if (size < 8) break
            pos += size
        }
        types
    }

    /** Files (each from its media start) joined byte for byte into [output]. */
    private fun join(files: List<Pair<String, Long>>, output: StorageFile, container: String, kind: OutputKind, what: String): Boolean =
        write(output, container, kind, what) { out ->
            val buf = ByteArray(256 * 1024)
            for ((path, from) in files) {
                FileInputStream(path).use { input ->
                    skipFully(input, from)
                    while (!stopFlag.get()) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
                if (stopFlag.get()) return@write false
            }
            true
        }

    /** Writes [output] through [body] and records it as [container] / [kind]; deletes it on a stop or an error. */
    private inline fun write(output: StorageFile, container: String, kind: OutputKind, what: String, body: (OutputStream) -> Boolean): Boolean {
        return try {
            val ok = output.open(create = true).use { handle ->
                handle.setLength(0)
                val out = BufferedOutputStream(handle.asOutputStream(), 1 shl 20)
                val ok = body(out)
                out.flush()
                handle.force()
                ok
            }
            if (!ok || stopFlag.get()) { output.delete(); return false }
            Logger.info("XDM", "Fallback: $what")
            lastError = null
            timelineStartSec = null
            outputContainer = container
            outputKind = kind
            true
        } catch (ex: Exception) {
            output.delete()
            if (!stopFlag.get()) { Logger.error("XDM", "Fallback failed: $what", ex); lastError = ex }
            false
        }
    }

    /**
     * Publishes [segments] that no demuxer reads but that are all one [SegmentProbe.Foreign]
     * container: a single file as it is, several joined byte for byte when the container allows it
     * (chained Ogg, MPEG program stream). [outputContainer] then names the file after it. Several
     * files of a container that can't be joined (FLV with codecs we don't read, AVI, ASF...) fail
     * with an [UnsupportedContainerException]; anything else keeps the [NoMediaException].
     */
    private fun publishAsIs(segments: List<String>, output: StorageFile): Boolean {
        val kinds = segments.map { SegmentProbe.foreign(it) }
        val kind = kinds.first()
        if (kind == null || kinds.any { it != kind }) return false
        if (segments.size > 1 && !kind.concatenable) {
            lastError = UnsupportedContainerException(
                "${segments.size} ${kind.label} segments with no audio or video XDM can read; ${kind.label} files can't be joined as they are"
            )
            Logger.error("XDM", lastError!!.message!!)
            return false
        }
        val what = "${kind.label}: ${if (segments.size == 1) "the file is" else "${segments.size} segments joined and"} published as it is (no demuxer reads it)"
        return join(segments.map { it to 0L }, output, kind.extension, OutputKind.AS_IS, what)
    }

    /**
     * Throws when the non-empty input [list] (one track of a merge) gave no audio or video: the merge
     * would otherwise go on without that track and lose it silently.
     */
    private fun requireMedia(list: List<String>, tracks: List<Track>, what: String) {
        if (list.isEmpty() || stopFlag.get() || tracks.any { it.samples.isNotEmpty() }) return
        val kind = SegmentProbe.foreign(list.first())
        throw UnsupportedContainerException(
            "The $what (${kind?.label ?: "an unrecognised format"}, ${list.first().substringAfterLast('/')}) has no audio or video XDM can read; not merging without it"
        )
    }

    // ---- two separate files (e.g. video + audio) ----
    override fun mux(
        file1: String, file2: String, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String
    ): Boolean {
        outputKind = OutputKind.REMUXED
        val ok = remux(output, tempDir, listOf(listOf(file1), listOf(file2)), false) { writer ->
            val first = demuxList(listOf(file1), writer) {}
            requireMedia(listOf(file1), first, "first track")
            val second = demuxList(listOf(file2), writer) {}
            requireMedia(listOf(file2), second, "second track")
            progressCallback(100)
            first + second
        }
        if (ok || !mayFallBack()) return ok
        // Callers pass the video first.
        return mergeFallback(listOf(file1), listOf(file2), output)
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
    ): Boolean {
        outputKind = OutputKind.REMUXED
        val ok = remux(output, tempDir, listOf(audioSegments, videoSegments), discontinuous) { writer ->
            muxLists(audioSegments, videoSegments, writer, progressCallback)
        }
        if (ok || !mayFallBack()) return ok
        return mergeFallback(videoSegments, audioSegments, output)
    }

    private fun muxLists(audioSegments: List<String>, videoSegments: List<String>, writer: SampleSink, progressCallback: (Int) -> Unit): List<Track> {
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
        val videoTracks = video.finish()
        val audioTracks = audio.finish()
        requireMedia(videoSegments, videoTracks, "video track")
        requireMedia(audioSegments, audioTracks, "audio track")
        return videoTracks + audioTracks
    }

    override fun stop() {
        stopFlag.set(true)
    }

    // ---- core ----

    /**
     * [tempDir] holds scratch files (the MKV spool), so the output folder only needs the output's size.
     * [inputs] are the segment lists, looked at (their first segments) to pick the container.
     */
    private fun transmux(
        output: StorageFile, tempDir: String, inputs: List<List<String>>, discontinuous: Boolean,
        /** A container to write instead of the planned one (the MP4 retry). */
        forceFormat: String?,
        body: (ContainerWriter) -> List<Track>
    ): Boolean {
        lastError = null
        outputContainer = null
        mixedContainers = false
        lost.clear()
        if (failRemuxForTest) { lastError = java.io.IOException("remux failed (test)"); return false }
        val requested = container ?: output.name.substringAfterLast('.', "")
        val matroskaInput = inputs.any { it.isNotEmpty() && containerOf(it.first()) == Container.MKV }
        val format = forceFormat ?: MuxContainer.plan(requested, matroskaInput)
        if (!format.equals(requested, ignoreCase = true)) Logger.info("XDM", "Muxing as $format (asked for '$requested')")
        val writer: ContainerWriter =
            (if (MuxContainer.isMatroska(format)) MkvWriter(output, tempDir) { stopFlag.get() }
            else Mp4Writer(output)).also { it.repairTimeline = discontinuous }
        // Sample records of long streams spill to scratch files here; dropped when muxing ends.
        val spill = SampleSpill.open(File(tempDir))
        return try {
            val tracks = body(writer)
            // A remux that leaves an audio or video stream out is not used: a fallback keeps everything.
            if (lost.isNotEmpty() && !stopFlag.get()) throw StreamLostException("Streams the remux can't carry: ${lost.joinToString()}")
            // An ad or other insert in another container restarts its timestamps, whatever the manifest says.
            if (mixedContainers) writer.repairTimeline = true
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
        /** Audio/video streams this demuxer left out. */
        var lost: () -> Collection<String> = { emptyList() }
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
                    end = { demux.finish() }; tracks = { demux.tracks }; lost = { demux.lostStreams }
                }
                Container.MKV -> MatroskaDemuxer(writer).let { d -> feed = { seg, from -> d.parseSegment(seg, from) }; end = { d.finish() }; tracks = { d.tracks }; lost = { d.lostStreams } }
                Container.PACKED_AUDIO -> PackedAudioDemuxer(writer).let { d -> feed = { seg, from -> d.parseSegment(seg, from) }; end = { d.finish() }; tracks = { d.tracks } }
                Container.MP4 -> Mp4Demuxer(writer).let { d -> feed = { seg, from -> d.parseSegment(seg, from) }; end = { d.finish() }; tracks = { d.tracks } }
                Container.FLV -> FlvDemuxer(writer).let { d -> feed = { seg, from -> d.parseSegment(seg, from) }; end = { d.finish() }; tracks = { d.tracks }; lost = { d.lostStreams } }
            }
        }

        /**
         * Whether the demuxer can be left and fed again later. TS and packed audio hold back the
         * last frames until finished, so they are finished when left and a new one starts on return;
         * MP4 and Matroska keep their init segment's tracks for the media segments that follow.
         */
        val resumable get() = container == Container.MP4 || container == Container.MKV || container == Container.FLV

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
            for (d in used) lost += d.lost()
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

/** A remux would have left an audio or video stream out (a codec no reader or writer carries). */
class StreamLostException(message: String) : java.io.IOException(message)

/** Input in a container the transmuxer doesn't read where it can't be published as it is (a merge, or parts that can't be joined). */
class UnsupportedContainerException(message: String) : java.io.IOException(message)
