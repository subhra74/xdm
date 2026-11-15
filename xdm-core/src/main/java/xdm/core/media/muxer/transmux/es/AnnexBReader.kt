// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.mp4.SampleEntries
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.util.Logger

/**
 * Base for H.264/H.265 readers: turns an Annex-B byte stream into MP4 samples (one per access
 * unit, NAL units re-emitted with 4-byte length prefixes).
 *
 * PES packets are only a transport: an encoder may put one access unit in each PES, split a large
 * one over several PES (the continuations often carry no PTS), or cut a NAL unit or even a start
 * code across the boundary. So the byte stream is treated as continuous across [consume] calls: the
 * unfinished NAL unit is carried to the next PES, and access units are delimited by their content
 * (an access unit delimiter, a parameter set / SEI after a slice, or the first slice of a new
 * picture), never by PES boundaries.
 *
 * A PES PTS belongs to the first access unit that starts in that PES. An access unit that starts
 * in a PES without one (several pictures in one PES) gets a timestamp extrapolated from the
 * previous frame interval.
 */
abstract class AnnexBReader(protected val sink: SampleSink) : ElementaryStreamReader {

    /** Bytes of the NAL unit being read, from its header on (no start code). */
    private val nal = ByteArrayBuilder(256 * 1024)
    /** False until the first start code: bytes before it can't be attributed to any NAL unit. */
    private var synced = false
    /** Timestamps of the PES the current NAL unit started in, if not yet used by an access unit. */
    private var nalPts = NO_TS
    private var nalDts = NO_TS
    /** PTS/DTS of the latest PES, until the first NAL unit starting after it takes them. */
    private var pesPts = NO_TS
    private var pesDts = NO_TS
    /** Whether the PES (NAL unit, access unit) carried a DTS of its own, not just a PTS. */
    private var pesDtsGiven = false
    private var nalDtsGiven = false
    private var auDtsGiven = false
    /**
     * Timestamps of the current PES that reached a NAL unit of a picture which began in an earlier PES
     * (a segmenter that cuts PES inside pictures: the PES opens with the last slices of one picture and
     * the next one's delimiter comes later). They belong to the first access unit that starts in this PES
     * (ISO/IEC 13818-1 2.4.3.7), so they wait for it: a NAL unit that starts a picture takes them only
     * when it began in the same PES ([heldPes]). A NAL unit is finished by the next start code, which may
     * come in the next PES, so each one carries the number of the PES it began in ([nalPes]).
     */
    private var heldPts = NO_TS
    private var heldDts = NO_TS
    private var heldDtsGiven = false
    private var heldPes = -1L
    private var pesCount = 0L
    private var nalPes = -1L

    // ---- the access unit being assembled ----
    private val au = ByteArrayBuilder()
    private var auHasVcl = false
    private var auKeyframe = false
    private var auPts = NO_TS
    private var auDts = NO_TS

    // ---- timestamp extrapolation ----
    private var lastPts = NO_TS
    private var lastDts = NO_TS
    private var lastInterval = 0L

    override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) {
        pesCount++
        if (pts >= 0) {
            pesPts = pts
            pesDts = if (dts >= 0) dts else pts
            pesDtsGiven = dts >= 0
        }
        // Continue the scan 3 bytes back so a start code split across PES packets is still found.
        var scanFrom = maxOf(0, nal.size - 3)
        nal.write(data, offset, length)
        val buf = nal.buffer
        val end = nal.size
        var nalStart = 0
        while (true) {
            val sc = findStartCode(buf, scanFrom, end)
            if (sc < 0) break
            if (synced) completeNal(buf, nalStart, sc)
            synced = true
            nalStart = sc + 3
            scanFrom = nalStart
            // This NAL unit starts in the current PES: it takes the PES timestamps (once).
            nalPts = pesPts; nalDts = pesDts; nalDtsGiven = pesDtsGiven; nalPes = pesCount
            pesPts = NO_TS; pesDts = NO_TS
        }
        if (!synced) {
            // Keep only a possible partial start code.
            nal.compact(maxOf(0, end - 3))
        } else {
            nal.compact(nalStart)
        }
        // A PES timestamp only belongs to an access unit starting in that PES.
        pesPts = NO_TS; pesDts = NO_TS
    }

    override fun finish() {
        if (synced && nal.size > 0) completeNal(nal.buffer, 0, nal.size)
        nal.reset()
        emitAccessUnit()
        dtsInference.finish()
        if (dtsInference.inferred > 0) Logger.info("XDM", "${track.codec}: decode times inferred for ${dtsInference.inferred} frames (PES without a usable DTS)")
    }

    /** NAL bytes [start, end) are complete; trailing zero bytes (4-byte start codes, padding) dropped. */
    private fun completeNal(buf: ByteArray, start: Int, end: Int) {
        var e = end
        while (e > start && buf[e - 1].toInt() == 0) e--
        if (e - start < headerSize()) return
        val ts = nalPts; val tsDts = nalDts; val given = nalDtsGiven
        nalPts = NO_TS; nalDts = NO_TS
        onNal(buf, start, e, ts, tsDts, given, nalPes)
    }

    private fun onNal(buf: ByteArray, start: Int, end: Int, pts: Long, dts: Long, dtsGiven: Boolean, pes: Long) {
        if (!isBaseLayer(buf, start)) {
            // Enhancement-layer NAL units (MV-HEVC, SHVC) travel with the base picture, never split it.
            if (auHasVcl || au.size > 0) { au.writeInt(end - start); au.write(buf, start, end - start) }
            return
        }
        val vcl = isVcl(buf, start)
        // Every slice is looked at (a reader may remember the previous slice's position), not only
        // those after the first of the access unit.
        val startsAu = if (vcl) isFirstSliceOfPicture(buf, start, end) && auHasVcl
        else auHasVcl && startsAccessUnit(buf, start)
        if (startsAu) emitAccessUnit()
        if (!auOpen) { auOpen = true; auSection = section }
        if (pts != NO_TS && auPts != NO_TS) {
            // This picture began in an earlier PES: the timestamps are the next picture's.
            heldPts = pts; heldDts = dts; heldDtsGiven = dtsGiven; heldPes = pes
        } else if (pts != NO_TS) {
            auPts = pts; auDts = dts; auDtsGiven = dtsGiven
            heldPts = NO_TS
        } else if (heldPts != NO_TS && auPts == NO_TS && pes == heldPes) {
            auPts = heldPts; auDts = heldDts; auDtsGiven = heldDtsGiven
            heldPts = NO_TS; heldDts = NO_TS
        }

        if (vcl) {
            auHasVcl = true
            if (isKeyframe(buf, start)) auKeyframe = true
        } else if (marksRandomAccess(buf, start, end)) {
            auKeyframe = true
        }
        if (keepNal(buf, start, end)) {
            au.writeInt(end - start)
            au.write(buf, start, end - start)
        }
    }

    private fun emitAccessUnit() {
        if (!auHasVcl) { auOpen = false; return }
        // A decode-time jump (more than JUMP_BACK back or JUMP_FORWARD on) starts a section too, for
        // inputs with no playlist to say where the discontinuities are.
        if (auPts != NO_TS && lastRealDts != NO_TS && auSection == section &&
            (auDts < lastRealDts - JUMP_BACK || auDts > lastRealDts + JUMP_FORWARD)
        ) {
            section++
            auSection = section
        }
        if (auSection != keyframeSection) {
            if (!auKeyframe) {
                // Before the first keyframe of its section (the stream's start, or after a discontinuity):
                // its references were never sent or belong to other content, so a decoder that shows every
                // frame (hardware ones, system players) shows garbage, and AVFoundation stops decoding
                // there. Dropped, as ffmpeg's stream copy drops non-keyframes at the start.
                droppedLeading++
                droppedInSection++
                discardAccessUnit()
                return
            }
            keyframeSection = auSection
            if (!configFrozen) freezeConfig()
            if (droppedInSection > 0) {
                Logger.info("XDM", "${track.codec}: dropped $droppedInSection frames before the keyframe that opens section $auSection")
                droppedInSection = 0
            }
        }
        if (auKeyframe && configFrozen) followConfig()
        auParameterSets.clear()
        var pts = auPts
        var dts = auDts
        val real = pts != NO_TS
        if (!real) {
            // No PES timestamp reached this picture: continue the cadence for now; once the next real
            // timestamp arrives these samples are spread evenly up to it (fields of an interlaced
            // frame sharing one PES then get half a frame each).
            val step = if (lastInterval > 0) lastInterval else DEFAULT_INTERVAL
            dts = if (lastDts != NO_TS) lastDts + step else 0
            pts = if (lastPts != NO_TS) dts + (lastPts - lastDts) else dts
        } else if (provisional.isNotEmpty() && lastRealDts == NO_TS) {
            // Pictures before the stream's first timestamp: placed back from it.
            backFillLeading(track.samples, provisional.size, dts, DEFAULT_INTERVAL)
        } else if (provisional.isNotEmpty() && lastRealDts != NO_TS && dts > lastRealDts) {
            val n = provisional.size + 1
            val span = dts - lastRealDts
            for ((k, index) in provisional.withIndex()) {
                val s = track.samples[index]
                val offset = s.pts - s.dts
                s.dts = lastRealDts + span * (k + 1) / n
                s.pts = s.dts + offset
            }
            lastInterval = span / n
        } else if (lastDts != NO_TS && dts > lastDts) {
            lastInterval = dts - lastDts
        }
        lastPts = pts; lastDts = dts

        val fileOffset = sink.writeSampleData(au.buffer, 0, au.size)
        track.samples.add(fileOffset, au.size, pts, dts, auKeyframe, descriptionIndex)
        if (real) {
            provisional.clear()
            lastRealDts = dts
            dtsInference.added(track.samples.size - 1, pts, dts, auDtsGiven, auSection)
        } else {
            provisional += track.samples.size - 1
            track.guessedTimestamps++
            dtsInference.interrupted()
        }
        au.reset()
        auHasVcl = false
        auKeyframe = false
        auOpen = false
        auPts = NO_TS; auDts = NO_TS; auDtsGiven = false
    }

    private fun discardAccessUnit() {
        au.reset(); auHasVcl = false; auKeyframe = false; auOpen = false; auPts = NO_TS; auDts = NO_TS; auDtsGiven = false
        auParameterSets.clear()
    }

    /**
     * The segments that follow are another section (the playlist has a discontinuity before them): its
     * frames before its first keyframe are dropped. What has been fed so far (the last NAL unit, which
     * no start code has ended yet, and its access unit) is the section before's, and is written now.
     */
    override fun sectionStart() {
        if (synced && nal.size > 0) completeNal(nal.buffer, 0, nal.size)
        nal.reset()
        emitAccessUnit()
        section++
    }

    /** Decode times for frames whose PES carried only a PTS (or a DTS after it). */
    private val dtsInference by lazy { DtsInference(track.samples) }

    /** Samples since the last real timestamp whose times were extrapolated (indices into track.samples). */
    private val provisional = ArrayList<Int>()
    private var lastRealDts = NO_TS

    // ---- sections and decoder configs ----

    /** Sections of the stream so far (one more at each [sectionStart] or timestamp jump). */
    private var section = 0
    /** The section the access unit being assembled started in. */
    private var auSection = 0
    private var auOpen = false
    /** The section whose first keyframe has been written (-1: none yet). */
    private var keyframeSection = -1
    /** Access units dropped before a section's first keyframe. */
    var droppedLeading = 0
        private set
    private var droppedInSection = 0

    /** Latest parameter set per (NAL type, id), whole stream. */
    private val latestParameterSets = HashMap<Long, ByteArray>()
    /** Parameter sets carried in the access unit being assembled, per (NAL type, id). */
    private val auParameterSets = HashMap<Long, ByteArray>()
    private var configFrozen = false
    /** The parameter sets of the sample entry samples now point at, and its 1-based stsd index. */
    private var currentSets: List<ByteArray> = emptyList()
    private var descriptionIndex = 1

    /**
     * A parameter set seen in the stream (it also stays in the sample, in-band). Its fields are read
     * into [track] until the decoder config is fixed at the first keyframe (ffmpeg's extradata comes
     * from the first keyframe too); a later change starts a sample entry of its own ([followConfig]).
     * Collecting every SPS into one config record instead puts several sets with the same id there,
     * of which decoders that configure from the record take the last one.
     */
    protected fun offerParameterSet(type: Int, id: Int, nal: ByteArray) {
        val key = (type.toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)
        auParameterSets[key] = nal
        if (latestParameterSets[key]?.contentEquals(nal) == true) return
        latestParameterSets[key] = nal
        if (!configFrozen) try { onParameterSet(type, nal, track) } catch (_: Exception) { /* tolerate a malformed set */ }
    }

    /**
     * The parameter sets in effect for a keyframe: those it carries when it carries an SPS (an encoder
     * repeats the whole set with every keyframe), else the latest of each, ordered by type then id.
     */
    private fun setsInEffect(): List<ByteArray> {
        val carriesSps = auParameterSets.keys.any { (it shr 32).toInt() == spsType }
        val from = if (carriesSps) auParameterSets else latestParameterSets
        return from.entries.sortedBy { it.key }.map { it.value }
    }

    /** Fixes [track]'s parameter sets at the first keyframe and reads its fields from them. */
    private fun freezeConfig() {
        configFrozen = true
        currentSets = setsInEffect()
        track.parameterSets.clear()
        track.parameterSets.addAll(currentSets)
        for (nal in currentSets) try { onParameterSet(parameterSetType(nal), nal, track) } catch (ex: Exception) {
            // The track stays without a size; the muxer then reports it as lost and falls back.
            Logger.error("XDM", "${track.codec}: parameter set type ${parameterSetType(nal)} (${nal.size} bytes) doesn't parse: $ex")
        }
    }

    /**
     * At a later keyframe: parameter sets other than the current entry's (a resolution, profile or bit
     * depth change, e.g. at an ad) get a sample entry of their own, so a decoder set up from the entry
     * is set up again. AVFoundation follows only the entry and stops decoding at such a change; Media
     * Foundation and GStreamer take either. ffmpeg's stream copy keeps one entry. The first config
     * stays [track]'s own (its size and record).
     */
    private fun followConfig() {
        val sets = setsInEffect()
        if (sets.size == currentSets.size && sets.indices.all { sets[it].contentEquals(currentSets[it]) }) return
        if (sets.none { parameterSetType(it) == spsType }) return
        if (track.sampleEntryBox == null) track.sampleEntryBox = SampleEntries.build(track) ?: return
        val config = Track(track.codec)
        config.parameterSets.addAll(sets)
        for (nal in sets) try { onParameterSet(parameterSetType(nal), nal, config) } catch (_: Exception) {}
        val entry = SampleEntries.build(config) ?: return
        descriptionIndex = track.descriptionIndexFor(entry)
        currentSets = sets
        Logger.info("XDM", "${track.codec}: parameter sets changed (${config.width}x${config.height}, " +
            "${config.bitDepthLuma}-bit): sample entry $descriptionIndex from sample ${track.samples.size + 1}")
    }

    /** Reads a parameter set's fields (picture size, bit depth, colour...) into [into]. */
    protected abstract fun onParameterSet(type: Int, nal: ByteArray, into: Track)

    /** The NAL type of a parameter set (as passed to [offerParameterSet]). */
    protected abstract fun parameterSetType(nal: ByteArray): Int

    /** The NAL type of a sequence parameter set. */
    protected abstract val spsType: Int

    /** False for a NAL unit of an enhancement layer (nuh_layer_id > 0 in HEVC). */
    protected open fun isBaseLayer(buf: ByteArray, start: Int): Boolean = true

    /** True for a non-VCL NAL unit that makes its picture a random access point (a recovery-point SEI). */
    protected open fun marksRandomAccess(buf: ByteArray, start: Int, end: Int): Boolean = false

    /** NAL header length in bytes (1 for H.264, 2 for H.265). */
    protected abstract fun headerSize(): Int

    /** True for a VCL (slice) NAL unit. */
    protected abstract fun isVcl(buf: ByteArray, start: Int): Boolean

    /** True for a VCL NAL unit that makes its access unit a random access point. */
    protected abstract fun isKeyframe(buf: ByteArray, start: Int): Boolean

    /** True for a VCL NAL unit carrying the first slice of a picture. */
    protected abstract fun isFirstSliceOfPicture(buf: ByteArray, start: Int, end: Int): Boolean

    /** True for a non-VCL NAL unit that, after a slice, opens the next access unit (AUD, SPS, SEI...). */
    protected abstract fun startsAccessUnit(buf: ByteArray, start: Int): Boolean

    /**
     * Inspects any NAL unit (harvesting parameter sets) and returns whether it goes into the
     * sample; access unit delimiters and filler data are dropped.
     */
    protected abstract fun keepNal(buf: ByteArray, start: Int, end: Int): Boolean

    /**
     * Reads one parameter set NAL (without a start code) into [track] as if it came in the stream:
     * containers that carry them in a config record (FLV) use it for the picture size.
     */
    fun readParameterSet(nal: ByteArray) {
        if (nal.size > headerSize()) keepNal(nal, 0, nal.size)
    }

    private fun findStartCode(data: ByteArray, start: Int, end: Int): Int {
        var i = start
        while (i + 2 < end) {
            val b2 = data[i + 2].toInt() and 0xFF
            if (b2 > 1) {
                i += 3
            } else if (b2 == 1 && data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                return i
            } else {
                i++
            }
        }
        return -1
    }

    companion object {
        private const val NO_TS = Long.MIN_VALUE
        /** 90 kHz ticks of one frame at 30 fps, used only before any frame interval is known. */
        private const val DEFAULT_INTERVAL = 3000L
        /** A decode time this far back (1 s: no reorder is that deep) or on (2 s) starts a section. */
        private const val JUMP_BACK = 90_000L
        private const val JUMP_FORWARD = 180_000L
    }
}
