// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.mp4

import xdm.core.media.muxer.transmux.ContainerWriter
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.RemuxCheck
import xdm.core.media.muxer.transmux.sample.Timeline
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.media.muxer.transmux.sample.assignSampleDurations
import xdm.core.media.muxer.transmux.sample.presentationEnd
import xdm.core.media.muxer.transmux.sample.presentationStart
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
    override var repairTimeline: Boolean = repairTimeline
    override var remuxCheckFallback: (() -> Boolean)? = null
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
        val trackDurationMovie: Long,
        /** The first track of its type (tkhd enabled flag). */
        val enabled: Boolean,
    )

    private fun buildMoov(tracks: List<Track>): BoxBuf {
        // A/V sync: each track's content becomes visible at its earliest PTS (not its first DTS,
        // which precedes it by the B-frame reorder delay). Time zero is the first video frame
        // (presentationZero); the edit list skips the reorder delay, and audio before zero.
        // After a discontinuity the timestamps restart: move each section as a whole (all tracks by
        // one offset) so it follows the previous one and keeps its own A/V sync.
        // The shared timeline step (also run by the MKV writer): sections placed back to back, gaps
        // and backward jumps repaired, the timestamps rewritten to match.
        Timeline.prepare(tracks, repairTimeline)
        RemuxCheck.review(tracks, mp4 = true, remuxCheckFallback)
        val startPts = tracks.map { presentationStart(it) }
        val zeroSec = presentationZero(tracks) ?: 0.0

        val mediaTimes = LongArray(tracks.size)
        for ((idx, t) in tracks.withIndex()) {
            // Durations from successive DTS deltas (already repaired by the timeline step).
            assignSampleDurations(t.samples, false)
            val startSec = startPts[idx].toDouble() / t.timescale
            // A track that starts before time zero (audio before the first video frame) skips that part
            // through its edit's media time; one that starts after it waits with an empty edit.
            val skip = if (startSec < zeroSec) Math.round((zeroSec - startSec) * t.timescale) else 0L
            mediaTimes[idx] = (startPts[idx] - t.samples.dts(0) + skip).coerceIn(0, t.durationTicks())
        }
        // Where an edit skips media, every track opens with the same short empty edit (LEAD_EMPTY_EDIT).
        val lead = if (mediaTimes.any { it > 0 }) LEAD_EMPTY_EDIT else 0L

        val plans = ArrayList<TrackPlan>()
        for ((idx, t) in tracks.withIndex()) {
            val mediaDuration = t.durationTicks()
            val startSec = startPts[idx].toDouble() / t.timescale
            val mediaTime = mediaTimes[idx]
            val presentedDuration = (presentationEnd(t.samples) - mediaTime).coerceAtLeast(0)
            val emptyEditMovie = ((startSec - zeroSec) * movieTimescale).toLong().coerceAtLeast(0) + lead
            val presentedMovie = toMovieUnitsCeil(presentedDuration, t.timescale)
            plans.add(
                TrackPlan(t, idx + 1, mediaTime, mediaDuration, presentedDuration, emptyEditMovie, emptyEditMovie + presentedMovie,
                    enabled = tracks.indexOfFirst { it.codec.isVideo == t.codec.isVideo } == idx)
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

    /**
     * Time zero is the first video frame: audio that starts before it is skipped (by the edit's media
     * time) rather than the video delayed by an empty edit, which Media Foundation (Films & TV, Media
     * Player) ignores, showing the video that much early. Within [MAX_LEADING_AUDIO_SEC]: a longer
     * lead is audio of its own (an intro), kept, with the video delayed as before.
     */
    override fun presentationZero(tracks: List<Track>): Double? {
        val usable = tracks.filter { it.samples.isNotEmpty() && it.timescale > 0 }
        if (usable.isEmpty()) return null
        val start = { t: Track -> presentationStart(t).toDouble() / t.timescale }
        val earliest = usable.minOf(start)
        val video = usable.filter { it.codec.isVideo }.minOfOrNull(start) ?: return earliest
        return if (video - earliest <= MAX_LEADING_AUDIO_SEC) video else earliest
    }

    /** ISO 639-2 code packed as three 5-bit letters (mdhd); 'und' when not known. */
    private fun packedLanguage(code: String?): Int {
        val c = code?.takeIf { it.length == 3 && it.all { ch -> ch in 'a'..'z' } } ?: "und"
        return ((c[0] - '`') shl 10) or ((c[1] - '`') shl 5) or (c[2] - '`')
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
                // In movie; enabled only on the first track of each type (as ffmpeg), so a player that
                // honours the flags plays one audio track, not all of them at once.
                val flags = if (p.enabled) 0x000003 else 0x000002
                if (p.trackDurationMovie > U32_MAX) {
                    fullBoxHeader(1, flags); u64(0); u64(0); u32(p.trackId); u32(0); u64(p.trackDurationMovie)
                } else {
                    fullBoxHeader(0, flags)
                    u32(0); u32(0)
                    u32(p.trackId)
                    u32(0)                      // reserved
                    u32(p.trackDurationMovie)
                }
                u32(0); u32(0)              // reserved
                u16(0)                      // layer
                u16(if (t.codec.isVideo) 0 else 1) // alternate_group: the audio tracks are alternatives
                u16(if (t.codec.isVideo) 0 else 0x0100) // volume
                u16(0)                      // reserved
                // Rotation / flip from the input, else the identity.
                val m = t.matrix
                if (m != null && m.size == 9) m.forEach { u32(it.toLong() and 0xFFFFFFFFL) } else unityMatrix(this)
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
                u16(packedLanguage(t.language))
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
            if (t.codec == Codec.AAC || t.codec == Codec.OPUS) writeRollGroups(this, t)
        }
    }

    /**
     * Pre-roll as ffmpeg writes it (`sgpd`/`sbgp` of type 'roll'): AAC needs the frame before a seek
     * point decoded first (roll distance -1 for every sample); Opus 80 ms of frames before it (the
     * samples without that much before them get none). Players use it to avoid a click after a seek.
     */
    private fun writeRollGroups(buf: BoxBuf, t: Track) {
        val s = t.samples
        val n = s.size
        if (n == 0) return
        if (t.codec == Codec.AAC) {
            buf.box("sgpd") { fullBoxHeader(1, 0); fourcc("roll"); u32(2); u32(1); u16(-1) }
            buf.box("sbgp") { fullBoxHeader(0, 0); fourcc("roll"); u32(1); u32(n); u32(1) }
            return
        }
        // Opus: runs of samples with the same roll distance, found in two passes over the table (count,
        // then write); per pass a ring of the last few durations, nothing per sample.
        val rollTicks = OPUS_PREROLL_SAMPLES * t.timescale / 48_000L
        fun runs(each: (count: Int, distance: Int) -> Unit) {
            val ring = LongArray(MAX_ROLL_PACKETS + 1)
            var runCount = 0
            var runDistance = -1
            for (i in 0 until n) {
                var remaining = rollTicks
                var distance = 0
                var j = i - 1
                while (j >= 0 && distance <= MAX_ROLL_PACKETS) {
                    remaining -= ring[j % ring.size]; distance++; j--
                    if (remaining <= 0) break
                }
                if (remaining > 0 || distance > MAX_ROLL_PACKETS) distance = 0
                ring[i % ring.size] = s.duration(i)
                if (distance == runDistance) runCount++
                else { if (runCount > 0) each(runCount, runDistance); runCount = 1; runDistance = distance }
            }
            if (runCount > 0) each(runCount, runDistance)
        }
        var entries = 0
        var groups = 0
        runs { _, d -> entries++; if (d != 0) groups++ }
        if (groups == 0) return
        buf.box("sgpd") {
            fullBoxHeader(1, 0); fourcc("roll"); u32(2); u32(groups)
            runs { _, d -> if (d != 0) u16(-d) }
        }
        buf.box("sbgp") {
            fullBoxHeader(0, 0); fourcc("roll"); u32(entries)
            var group = 0
            runs { count, d -> u32(count); u32(if (d != 0) ++group else 0) }
        }
    }

    private fun writeStsd(buf: BoxBuf, t: Track) {
        buf.box("stsd") {
            fullBoxHeader(0, 0)
            // Sample entries copied from an MP4 input (avc1/mp4a/...) or frozen by a reader when the
            // stream's config changed mid-way (see Track.additionalSampleEntries); else built now.
            val entries = (listOf(t.sampleEntryBox ?: SampleEntries.build(t)!!) + t.additionalSampleEntries)
                .map { SampleEntries.withVuiBoxes(it, t.width, t.height) }
            // An esds without bitrates (ours) gets the track's, measured in one pass, as ffmpeg fills them.
            val rates = if (entries.any { CodecBoxes.esdsRatesUnset(it) }) EsdsRates.of(t) else null
            u32(entries.size)
            for (e in entries) bytes(if (rates != null) CodecBoxes.withEsdsRates(e, rates) else e)
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

/** Opus seek pre-roll, 80 ms at 48 kHz (RFC 7845), and the most packets it may span (ffmpeg's limit). */
private const val OPUS_PREROLL_SAMPLES = 3840L
private const val MAX_ROLL_PACKETS = 32

/**
 * Movie ticks (1 ms) of the empty edit every track's edit list opens with. Media Foundation (Films & TV,
 * Media Player) applies an edit's media_time (the B-frame delay, the audio skipped before the first
 * video frame) only after an empty edit: with a lone edit it plays from the first sample's composition
 * time instead, 80 ms late for two B-frames, the audio not skipped at all. It ignores the empty edit's
 * length, so every track gets the same: other players then start everything 1 ms later, in sync.
 * Written only when some track's edit skips media; without a skip there is nothing to misplace.
 */
private const val LEAD_EMPTY_EDIT = 1L

/** Audio before the first video frame up to this long is skipped (see Mp4Writer.presentationZero). */
private const val MAX_LEADING_AUDIO_SEC = 10.0
