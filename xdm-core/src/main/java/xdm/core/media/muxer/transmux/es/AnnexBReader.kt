// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.es


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
        if (pts >= 0) {
            pesPts = pts
            pesDts = if (dts >= 0) dts else pts
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
            nalPts = pesPts; nalDts = pesDts
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
    }

    /** NAL bytes [start, end) are complete; trailing zero bytes (4-byte start codes, padding) dropped. */
    private fun completeNal(buf: ByteArray, start: Int, end: Int) {
        var e = end
        while (e > start && buf[e - 1].toInt() == 0) e--
        if (e - start < headerSize()) return
        val ts = nalPts; val tsDts = nalDts
        nalPts = NO_TS; nalDts = NO_TS
        onNal(buf, start, e, ts, tsDts)
    }

    private fun onNal(buf: ByteArray, start: Int, end: Int, pts: Long, dts: Long) {
        if (!isBaseLayer(buf, start)) {
            // Enhancement-layer NAL units (MV-HEVC, SHVC) travel with the base picture, never split it.
            if (auHasVcl || au.size > 0) { au.writeInt(end - start); au.write(buf, start, end - start) }
            return
        }
        val vcl = isVcl(buf, start)
        val startsAu = if (vcl) auHasVcl && isFirstSliceOfPicture(buf, start, end)
        else auHasVcl && startsAccessUnit(buf, start)
        if (startsAu) emitAccessUnit()
        if (pts != NO_TS && auPts == NO_TS) { auPts = pts; auDts = dts }

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
        if (!auHasVcl) return
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
        track.samples.add(fileOffset, au.size, pts, dts, auKeyframe)
        if (real) {
            provisional.clear()
            lastRealDts = dts
        } else {
            provisional += track.samples.size - 1
        }
        au.reset()
        auHasVcl = false
        auKeyframe = false
        auPts = NO_TS; auDts = NO_TS
    }

    /** Samples since the last real timestamp whose times were extrapolated (indices into track.samples). */
    private val provisional = ArrayList<Int>()
    private var lastRealDts = NO_TS

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
    }
}
