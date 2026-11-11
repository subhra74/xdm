// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.mp4

import xdm.core.media.muxer.transmux.ContainerWriter
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.SampleTable
import xdm.core.media.muxer.transmux.sample.upperMedian
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.media.muxer.transmux.ts.UnsupportedCodecException
import xdm.core.storage.LocalStorage
import xdm.core.storage.StorageFile
import xdm.core.storage.asOutputStream
import java.io.BufferedOutputStream

/**
 * Writes a progressive (single-file) MP4 from demuxed [Track]s.
 *
 * Layout: `ftyp` then a streaming `mdat` (64-bit largesize) then `moov`. Sample bytes are appended
 * to mdat as the demuxer produces them (this class is the [SampleSink]); only lightweight sample
 * records are kept (in [xdm.core.media.muxer.transmux.sample.SampleTable]s, spilled to scratch files
 * for long streams), and the `moov` sample tables are streamed from them at the end.
 * Each sample is emitted as its own MP4 chunk, which correctly handles audio/video interleaving.
 */
class Mp4Writer(private val output: StorageFile, repairTimeline: Boolean = false) : ContainerWriter {
    /** Repair discontinuities in the timeline at [finish]; may be turned on after construction. */
    var repairTimeline: Boolean = repairTimeline
    constructor(outputPath: String, repairTimeline: Boolean = false) : this(LocalStorage.file(outputPath), repairTimeline)

    /** Written front to back through [bos]; the mdat size is then patched through the same handle. */
    private val handle = output.open(create = true).apply { setLength(0) }
    private val bos = BufferedOutputStream(handle.asOutputStream(), 1 shl 20)
    private var position = 0L
    private var mdatHeaderPos = 0L
    private var mdatDataStart = 0L

    private val movieTimescale = 1000

    init {
        writeFtyp()
        mdatHeaderPos = position
        // 64-bit mdat header: size=1, type, largesize placeholder.
        writeRaw(byteArrayOf(0, 0, 0, 1))
        writeRaw("mdat".toByteArray(Charsets.US_ASCII))
        writeRaw(ByteArray(8)) // largesize placeholder
        mdatDataStart = position
    }

    override fun writeSampleData(data: ByteArray, offset: Int, length: Int): Long {
        val at = position
        bos.write(data, offset, length)
        position += length
        return at
    }

    /** Finalizes the file: patches the mdat size and appends the moov. Returns false if no media. */
    override fun finish(tracks: List<Track>): Boolean {
        val usable = tracks.filter { it.samples.isNotEmpty() && it.isReady() }
        if (usable.isEmpty()) {
            bos.close()
            return false
        }
        // A track this writer has no sample entry for would be written with an empty stsd, which no
        // player opens. Matroska input never comes here (MuxContainer picks Matroska for it), so this
        // only stops a caller that forces MP4.
        for (t in usable) {
            if (t.matroskaCodecId != null || (t.sampleEntryBox == null && SampleEntries.build(t) == null)) {
                throw UnsupportedCodecException("MP4 can't carry the ${t.matroskaCodecId ?: t.codec} track; write Matroska instead")
            }
        }
        val mdatEnd = position
        // Streamed: the sample tables are written from the tracks' sample tables as they go out.
        val moov = buildMoov(usable)
        moov.writeTo(bos)
        position += moov.totalSize()
        bos.flush()

        // Patch the mdat largesize. Forced before the publish rename: the segments are deleted once the
        // download succeeds, so after a power loss nothing could rebuild an unflushed output.
        handle.use {
            val mdatSize = mdatEnd - mdatHeaderPos
            it.seek(mdatHeaderPos + 8)
            it.write(longToBytes(mdatSize), 0, 8)
            it.force()
        }
        return true
    }

    override fun abort() {
        try { bos.close() } catch (_: Exception) {}
        output.delete()
    }

    private fun writeFtyp() {
        val b = BoxBuf()
        b.box("ftyp") {
            fourcc("isom"); u32(0x200)
            fourcc("isom"); fourcc("iso2"); fourcc("avc1"); fourcc("mp41")
        }
        writeRaw(b.toByteArray())
    }

    private fun writeRaw(bytes: ByteArray) {
        bos.write(bytes)
        position += bytes.size
    }

    private fun longToBytes(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (v ushr (56 - i * 8)).toByte()
        return out
    }

    // ---- moov construction ----

    private class TrackPlan(
        val track: Track,
        val trackId: Int,
        /** Media time (from the first DTS) of the first presented sample: the edit's media_time. */
        val mediaTime: Long,
        val mediaDuration: Long,
        /** Media ticks from [mediaTime] to the end of the last presented sample: the edit's length. */
        val presentedDuration: Long,
        val emptyEditMovie: Long,
        val trackDurationMovie: Long
    )

    private fun buildMoov(tracks: List<Track>): BoxBuf {
        // A/V sync: each track's content becomes visible at its earliest PTS (not its first DTS,
        // which precedes it by the B-frame reorder delay). Align tracks on that, then let the edit
        // list skip the reorder delay inside each track.
        // After a discontinuity the timestamps restart: move each section as a whole (all tracks by
        // one offset) so it follows the previous one and keeps its own A/V sync.
        val sectionsAligned = repairTimeline && alignSections(tracks)
        val startPts = tracks.map { presentationStart(it.samples) }
        val globalStartSec = tracks.indices.minOf { startPts[it].toDouble() / tracks[it].timescale }

        val plans = ArrayList<TrackPlan>()
        for ((idx, t) in tracks.withIndex()) {
            // durations from successive DTS deltas (repairing discontinuity gaps when enabled).
            assignSampleDurations(t.samples, repairTimeline && !sectionsAligned)

            val mediaDuration = t.durationTicks()
            val mediaTime = (startPts[idx] - t.samples.dts(0)).coerceIn(0, mediaDuration)
            val presentedDuration = (presentationEnd(t.samples) - mediaTime).coerceAtLeast(0)
            val startSec = startPts[idx].toDouble() / t.timescale
            val emptyEditMovie = ((startSec - globalStartSec) * movieTimescale).toLong().coerceAtLeast(0)
            val presentedMovie = toMovieUnitsCeil(presentedDuration, t.timescale)
            plans.add(
                TrackPlan(t, idx + 1, mediaTime, mediaDuration, presentedDuration, emptyEditMovie, emptyEditMovie + presentedMovie)
            )
        }
        val movieDuration = plans.maxOf { it.trackDurationMovie }

        val buf = BoxBuf()
        buf.box("moov") {
            writeMvhd(this, movieDuration, plans.size + 1)
            for (p in plans) writeTrak(this, p)
        }
        return buf
    }

    private fun toMovieUnitsCeil(mediaTicks: Long, mediaTimescale: Int): Long =
        (mediaTicks * movieTimescale + mediaTimescale - 1) / mediaTimescale

    private fun writeMvhd(buf: BoxBuf, duration: Long, nextTrackId: Int) {
        buf.box("mvhd") {
            if (duration > U32_MAX) {
                fullBoxHeader(1, 0); u64(0); u64(0); u32(movieTimescale); u64(duration)
            } else {
                fullBoxHeader(0, 0)
                u32(0); u32(0)                 // creation/modification time
                u32(movieTimescale)
                u32(duration)
            }
            u32(0x00010000)                // rate 1.0
            u16(0x0100)                    // volume 1.0
            u16(0); u32(0); u32(0)         // reserved
            unityMatrix(this)
            repeat(6) { u32(0) }           // pre_defined
            u32(nextTrackId)
        }
    }

    private fun writeTrak(buf: BoxBuf, p: TrackPlan) {
        val t = p.track
        buf.box("trak") {
            box("tkhd") {
                if (p.trackDurationMovie > U32_MAX) {
                    fullBoxHeader(1, 0x000007); u64(0); u64(0); u32(p.trackId); u32(0); u64(p.trackDurationMovie)
                } else {
                    fullBoxHeader(0, 0x000007)  // enabled | in movie | in preview
                    u32(0); u32(0)
                    u32(p.trackId)
                    u32(0)                      // reserved
                    u32(p.trackDurationMovie)
                }
                u32(0); u32(0)              // reserved
                u16(0)                      // layer
                u16(0)                      // alternate_group
                u16(if (t.codec.isVideo) 0 else 0x0100) // volume
                u16(0)                      // reserved
                unityMatrix(this)
                // Display size: the sample aspect ratio applied to the width, as ffmpeg writes for MP4.
                val displayWidth = if (t.sarNum > 0 && t.sarDen > 0) t.width.toLong() * t.sarNum / t.sarDen else t.width.toLong()
                u32((displayWidth.coerceIn(0, 0xFFFF)) shl 16)
                u32((t.height.toLong() and 0xFFFF) shl 16)
            }
            // Edit list: optional empty edit for A/V sync delay + composition (B-frame) trim.
            box("edts") {
                box("elst") {
                    val entries = ArrayList<Triple<Long, Long, Int>>() // segDur(movie), mediaTime(media), rate
                    if (p.emptyEditMovie > 0) entries.add(Triple(p.emptyEditMovie, -1L, 0x00010000))
                    // Rounded up: rounding down would trim the tail of the last frame.
                    val normalDur = toMovieUnitsCeil(p.presentedDuration, t.timescale)
                    entries.add(Triple(normalDur, p.mediaTime, 0x00010000))
                    // Version 1 (64-bit) when a value outgrows the 32-bit fields, as ffmpeg does.
                    val wide = entries.any { (seg, mt, _) -> seg > U32_MAX || mt > Int.MAX_VALUE }
                    fullBoxHeader(if (wide) 1 else 0, 0)
                    u32(entries.size)
                    for ((seg, mt, rate) in entries) {
                        if (wide) { u64(seg); u64(mt) } else { u32(seg); u32(mt.toInt()) } // media_time is signed
                        u32(rate)
                    }
                }
            }
            writeMdia(this, p)
        }
    }

    private fun writeMdia(buf: BoxBuf, p: TrackPlan) {
        val t = p.track
        buf.box("mdia") {
            box("mdhd") {
                // Version 1 (64-bit times) once the duration outgrows 32 bits: 13.2 h at 90 kHz.
                if (p.mediaDuration > U32_MAX) {
                    fullBoxHeader(1, 0); u64(0); u64(0); u32(t.timescale); u64(p.mediaDuration)
                } else {
                    fullBoxHeader(0, 0); u32(0); u32(0); u32(t.timescale); u32(p.mediaDuration)
                }
                u16(0x55C4)                 // language 'und'
                u16(0)
            }
            box("hdlr") {
                fullBoxHeader(0, 0)
                u32(0)
                fourcc(if (t.codec.isVideo) "vide" else "soun")
                u32(0); u32(0); u32(0)
                fourcc(if (t.codec.isVideo) "VideoHandler" else "SoundHandler")
                u8(0)                       // trailing of the name + null
            }
            writeMinf(this, p)
        }
    }

    private fun writeMinf(buf: BoxBuf, p: TrackPlan) {
        val t = p.track
        buf.box("minf") {
            if (t.codec.isVideo) {
                box("vmhd") { fullBoxHeader(0, 1); u16(0); u16(0); u16(0); u16(0) }
            } else {
                box("smhd") { fullBoxHeader(0, 0); u16(0); u16(0) }
            }
            box("dinf") {
                box("dref") {
                    fullBoxHeader(0, 0)
                    u32(1)
                    box("url ") { fullBoxHeader(0, 1) } // self-contained
                }
            }
            writeStbl(this, p)
        }
    }

    private fun writeStbl(buf: BoxBuf, p: TrackPlan) {
        val t = p.track
        buf.box("stbl") {
            writeStsd(this, t)
            writeStts(this, t)
            writeCtts(this, t)
            if (t.codec.isVideo) writeStss(this, t)
            writeStsc(this, t)
            writeStsz(this, t)
            writeStco(this, t)
        }
    }

    private fun writeStsd(buf: BoxBuf, t: Track) {
        buf.box("stsd") {
            fullBoxHeader(0, 0)
            val entry = t.sampleEntryBox
            if (entry != null) {
                // Sample entries copied from an MP4 input (avc1/mp4a/...) or frozen by a reader when the
                // stream's config changed mid-way (see Track.additionalSampleEntries).
                u32(1 + t.additionalSampleEntries.size)
                bytes(entry)
                for (extra in t.additionalSampleEntries) bytes(extra)
                return@box
            }
            u32(1)
            SampleEntries.write(this, t)
        }
    }

    // The tables below are counted in one pass and streamed in a second (see BoxBuf.deferred), so
    // neither the tables nor their run lists are held in memory.

    private fun writeStts(buf: BoxBuf, t: Track) {
        // Run-length encode equal durations.
        val s = t.samples
        val n = s.size
        var runs = 0
        var last = 0L
        for (i in 0 until n) {
            val d = s.duration(i)
            if (i == 0 || d != last) { runs++; last = d }
        }
        buf.box("stts") {
            fullBoxHeader(0, 0)
            u32(runs)
            deferred(8L * runs) { out ->
                var count = 0L
                var delta = 0L
                for (i in 0 until n) {
                    val d = s.duration(i)
                    if (count > 0 && d == delta) count++
                    else {
                        if (count > 0) { out.u32(count); out.u32(delta) }
                        delta = d; count = 1
                    }
                }
                if (count > 0) { out.u32(count); out.u32(delta) }
            }
        }
    }

    private fun writeCtts(buf: BoxBuf, t: Track) {
        val s = t.samples
        val n = s.size
        var any = false
        var negative = false
        var runs = 0
        var last = 0
        for (i in 0 until n) {
            val cts = (s.pts(i) - s.dts(i)).toInt()
            if (cts != 0) any = true
            if (cts < 0) negative = true
            if (i == 0 || cts != last) { runs++; last = cts }
        }
        if (!any) return
        buf.box("ctts") {
            // Version 1 (signed offsets) only when one is negative, as ffmpeg does; version 0 otherwise.
            fullBoxHeader(if (negative) 1 else 0, 0)
            u32(runs)
            deferred(8L * runs) { out ->
                var count = 0L
                var offset = 0
                for (i in 0 until n) {
                    val cts = (s.pts(i) - s.dts(i)).toInt()
                    if (count > 0 && cts == offset) count++
                    else {
                        if (count > 0) { out.u32(count); out.u32(offset) }
                        offset = cts; count = 1
                    }
                }
                if (count > 0) { out.u32(count); out.u32(offset) }
            }
        }
    }

    private fun writeStss(buf: BoxBuf, t: Track) {
        val s = t.samples
        val n = s.size
        var keys = 0
        for (i in 0 until n) if (s.isKeyframe(i)) keys++
        if (keys == n) return // all sync -> omit
        buf.box("stss") {
            fullBoxHeader(0, 0)
            if (keys == 0) {
                // No keyframe found: an absent stss would declare every sample a sync point (seeking into
                // P-frames); decoding can only start at the beginning, so list just the first sample.
                u32(1); u32(1)
            } else {
                u32(keys)
                deferred(4L * keys) { out -> for (i in 0 until n) if (s.isKeyframe(i)) out.u32(i + 1) }
            }
        }
    }

    /** One sample per chunk; a new run wherever the sample description changes. */
    private fun writeStsc(buf: BoxBuf, t: Track) {
        val s = t.samples
        val n = s.size
        var runs = 0
        var last = -1
        for (i in 0 until n) {
            val d = s.descriptionIndex(i)
            if (d != last) { runs++; last = d }
        }
        buf.box("stsc") {
            fullBoxHeader(0, 0)
            u32(runs)
            deferred(12L * runs) { out ->
                var prev = -1
                for (i in 0 until n) {
                    val d = s.descriptionIndex(i)
                    if (d != prev) { out.u32(i + 1); out.u32(1); out.u32(d); prev = d } // first_chunk, samples, desc
                }
            }
        }
    }

    private fun writeStsz(buf: BoxBuf, t: Track) {
        val s = t.samples
        val n = s.size
        buf.box("stsz") {
            fullBoxHeader(0, 0)
            u32(0)                      // sample_size = 0 (varying)
            u32(n)
            deferred(4L * n) { out -> for (i in 0 until n) out.u32(s.sampleSize(i)) }
        }
    }

    private fun writeStco(buf: BoxBuf, t: Track) {
        val s = t.samples
        val n = s.size
        var needs64 = false
        for (i in 0 until n) if (s.fileOffset(i) > 0xFFFFFFFFL) { needs64 = true; break }
        if (needs64) {
            buf.box("co64") {
                fullBoxHeader(0, 0)
                u32(n)
                deferred(8L * n) { out -> for (i in 0 until n) out.u64(s.fileOffset(i)) }
            }
        } else {
            buf.box("stco") {
                fullBoxHeader(0, 0)
                u32(n)
                deferred(4L * n) { out -> for (i in 0 until n) out.u32(s.fileOffset(i)) }
            }
        }
    }

    private fun unityMatrix(buf: BoxBuf) {
        buf.u32(0x00010000); buf.u32(0); buf.u32(0)
        buf.u32(0); buf.u32(0x00010000); buf.u32(0)
        buf.u32(0); buf.u32(0); buf.u32(0x40000000)
    }
}

private const val U32_MAX = 0xFFFFFFFFL

/** Samples scanned for the earliest PTS; far deeper than any real B-frame reorder window. */
private const val REORDER_WINDOW = 64

/**
 * Earliest presentation time among the first samples in decode order. Only the leading window is
 * scanned: later samples may sit on a reset timeline after a discontinuity.
 */
internal fun presentationStart(samples: SampleTable): Long {
    var min = Long.MAX_VALUE
    for (i in 0 until minOf(samples.size, REORDER_WINDOW)) min = minOf(min, samples.pts(i))
    return min
}

/**
 * End of the last presented sample on the written timeline (decode times are the running sum of
 * [SampleTable.duration], composition adds pts - dts), in media ticks from the first sample's DTS.
 * With B-frames the last frames in decode order are presented after the last DTS, so an edit that
 * ended at the decode duration would cut them off.
 */
internal fun presentationEnd(samples: SampleTable): Long {
    var decodeTime = 0L
    var end = 0L
    for (i in 0 until samples.size) {
        val d = samples.duration(i)
        end = maxOf(end, decodeTime + (samples.pts(i) - samples.dts(i)) + d)
        decodeTime += d
    }
    return end
}

/**
 * Places the sections of a stream with discontinuities (timestamps restarting or jumping at
 * each one) back to back on one timeline, as players do: every track of section k is shifted by
 * the same amount, so the A/V offset inside a section is kept, and by just enough that no track
 * overlaps its previous section. A track that ended earlier is left with a small gap (a video
 * frame shown a little longer, or audio timestamps resuming a little later).
 *
 * Sections are found per track as the timestamp jumps of [isJump]; the tracks must agree on
 * their number, else nothing is changed and false is returned (the caller then repairs each
 * track on its own).
 */
internal fun alignSections(tracks: List<Track>): Boolean {
    val usable = tracks.filter { it.samples.size > 1 && it.timescale > 0 }
    if (usable.isEmpty()) return false
    val starts = usable.map { sectionStarts(it.samples) }
    val count = starts.first().size
    if (count <= 1 || starts.any { it.size != count }) return false

    // Seconds where the previous section ended, after its shift (the latest end over all tracks).
    var previousEnd = Double.NEGATIVE_INFINITY
    for (k in 0 until count) {
        var shift = 0.0
        if (k > 0) {
            // Smallest shift that puts every track's section start at or after its previous end.
            shift = Double.NEGATIVE_INFINITY
            for ((i, t) in usable.withIndex()) {
                val from = starts[i][k]
                val sectionStart = sectionFirstPresented(t.samples, from, starts[i].getOrElse(k + 1) { t.samples.size })
                val trackEnd = sectionEnd(t, starts[i][k - 1], from)
                shift = maxOf(shift, trackEnd - sectionStart.toDouble() / t.timescale)
            }
        }
        var end = previousEnd
        for ((i, t) in usable.withIndex()) {
            val from = starts[i][k]
            val to = starts[i].getOrElse(k + 1) { t.samples.size }
            t.samples.shift(from, to, Math.round(shift * t.timescale))
            end = maxOf(end, sectionEnd(t, from, to))
        }
        previousEnd = end
    }
    return true
}

/** Upper median of the positive DTS steps into the samples [from, to) (each from the one before), or -1. */
private fun typicalStep(samples: SampleTable, from: Int, to: Int): Long = upperMedian { sink ->
    for (k in from until to) {
        val d = samples.dts(k) - samples.dts(k - 1)
        if (d > 0) sink.accept(d)
    }
}

/** Indices where each section of [samples] starts: 0, then every timestamp jump. */
private fun sectionStarts(samples: SampleTable): List<Int> {
    val typical = typicalStep(samples, 1, samples.size)
    if (typical < 0) return listOf(0)
    val out = arrayListOf(0)
    for (k in 1 until samples.size) {
        val d = samples.dts(k) - samples.dts(k - 1)
        if (d < 0 || d > typical * DISCONTINUITY_GAP_FACTOR) out += k
    }
    return out
}

/** Earliest PTS among the first samples of the section [from, to). */
private fun sectionFirstPresented(samples: SampleTable, from: Int, to: Int): Long {
    var min = Long.MAX_VALUE
    for (j in from until minOf(to, from + REORDER_WINDOW)) min = minOf(min, samples.pts(j))
    return min
}

/** Seconds at which the section [from, to) of [t] stops being presented: its latest PTS plus one frame. */
private fun sectionEnd(t: Track, from: Int, to: Int): Double {
    var maxPts = Long.MIN_VALUE
    for (j in maxOf(from, to - REORDER_WINDOW) until to) maxPts = maxOf(maxPts, t.samples.pts(j))
    // One frame: the section's typical DTS step.
    val step = if (to - from >= 2) typicalStep(t.samples, from + 1, to).coerceAtLeast(0) else 0
    return (maxPts + step).toDouble() / t.timescale
}

/** A discontinuity gap is an inter-sample delta larger than this multiple of the typical frame. */
private const val DISCONTINUITY_GAP_FACTOR = 8

/**
 * Derives each sample's duration ([SampleTable.duration]) from successive DTS deltas.
 *
 * When [repairTimeline] is true (the manifest signalled a discontinuity), inter-sample gaps that
 * are negative or implausibly large — the artefact of a mid-stream timestamp reset — are replaced
 * with the track's typical (median) frame duration, so the output timeline stays monotonic and
 * continuous across the boundary. When false, behaviour is unchanged (negative gaps clamp to 0,
 * legitimate large gaps are preserved). The last sample reuses the previous duration (or 1).
 */
internal fun assignSampleDurations(samples: SampleTable, repairTimeline: Boolean) {
    if (samples.isEmpty()) return
    if (!repairTimeline) {
        samples.deriveDurations()
        return
    }
    val typical = upperMedian { sink ->
        for (i in 0 until samples.size) {
            val d = samples.rawDuration(i)
            if (d > 0) sink.accept(d)
        }
    }
    if (typical > 0) samples.deriveDurations(typical, typical * DISCONTINUITY_GAP_FACTOR)
    else samples.deriveDurations()
}
