// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.io.ParsableBitArray
import xdm.core.media.muxer.transmux.mp4.SampleEntries
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.util.Logger

/**
 * (Enhanced) AC-3 reader. Frames are delimited by the 0x0B77 syncword. For AC-3 the frame size
 * comes from the frmsizecod table; for E-AC-3 it is the explicit `frmsiz` field. The dac3/dec3
 * config box payload is synthesized from the first frame header. One frame = 1536 audio samples.
 * A frame split across PES packets is carried over and completed by the next [consume].
 */
class Ac3Reader(private val sink: SampleSink, eac3: Boolean) : ElementaryStreamReader {
    override val track = Track(if (eac3) Codec.EAC3 else Codec.AC3)
    private val eac3 = eac3
    private val clock = FrameClock()
    private var configured = false
    /** Sampling rate of the current config (the track timescale stays the first config's). */
    private var rate = 0
    private var descriptionIndex = 1
    /** dac3/dec3 payload + rate of the current config, to notice a change. */
    private var configKey: ByteArray? = null

    /**
     * The access unit being collected: an AC-3 frame, or an E-AC-3 independent substream frame
     * followed by its dependent substreams (7.1) and further independent substreams, which all
     * share one timestamp and form one MP4 sample.
     */
    private val unit = ByteArrayBuilder(MAX_FRAME * 2)
    private var unitTs = 0L
    private var unitDescription = 1

    private val pending = ByteArrayBuilder(MAX_FRAME)
    /** Whether [pending] starts at a frame boundary we were already in sync with. */
    private var pendingInSync = false
    /** PTS of the PES the carried partial frame started in, when that frame was its first. */
    private var carriedPts = -1L

    override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) {
        val buf: ByteArray
        var i: Int
        val end: Int
        val carried = pending.size
        if (carried > 0) {
            // Complete the frame left over from the previous PES.
            buf = ByteArray(carried + length)
            System.arraycopy(pending.buffer, 0, buf, 0, carried)
            System.arraycopy(data, offset, buf, carried, length)
            i = 0
            end = buf.size
            pending.reset()
        } else {
            buf = data
            i = offset
            end = offset + length
        }
        // Frames starting at or after this index start in the current PES, which its PTS belongs to.
        val pesStart = i + carried
        var pesAnchor = pts
        var carryAnchor = carriedPts
        carriedPts = -1
        var inSync = carried > 0 && pendingInSync

        while (i + 6 <= end) {
            if (!isSync(buf, i)) {
                i++; inSync = false
                continue
            }
            // AC-3 or E-AC-3 is decided per frame by bsid, as ffmpeg does: an E-AC-3 stream may carry an
            // AC-3 core (Blu-ray 7.1: AC-3 frame + E-AC-3 dependent substream).
            val bsid = (buf[i + 5].toInt() shr 3) and 0x1F
            val isEac3 = bsid > 10
            if (bsid > 16 || (isEac3 && ((buf[i + 2].toInt() shr 6) and 3) == 3)) { i++; inSync = false; continue }
            val frameSize = if (isEac3) eac3FrameSize(buf, i) else ac3FrameSize(buf, i)
            if (frameSize <= 0) { i++; inSync = false; continue }
            if (i + frameSize > end) break // incomplete: carried over below
            // 0x0B77 also occurs inside frame data: a header found by scanning needs a successor.
            if (!inSync && i + frameSize + 2 <= end && !isSync(buf, i + frameSize)) {
                i++; continue
            }

            // E-AC-3: a dependent substream (strmtyp 1) or another independent substream
            // (substreamid > 0) belongs to the access unit begun by substream 0.
            val partOfUnit = isEac3 && (((buf[i + 2].toInt() shr 6) and 3) == 1 || ((buf[i + 2].toInt() shr 3) and 7) != 0)
            if (partOfUnit) {
                if (unit.size > 0) unit.write(buf, i, frameSize)
                i += frameSize
                inSync = true
                continue
            }
            emitUnit()
            if (!updateConfig(buf, i, end)) { i++; inSync = false; continue }
            val timescale = track.timescale
            val samples = if (isEac3) eac3Samples(buf, i) else SAMPLES_PER_FRAME.toInt()

            val anchor = if (i < pesStart) carryAnchor else pesAnchor
            if (anchor >= 0) {
                clock.anchor(anchor, timescale, samples.toLong() * timescale / rate)
                if (i < pesStart) carryAnchor = -1 else pesAnchor = -1
            }
            unitTs = clock.current()
            unitDescription = descriptionIndex
            unit.write(buf, i, frameSize)
            clock.advance(samples, rate, timescale)
            i += frameSize
            inSync = true
        }
        val tail = end - i
        if (tail in 1..MAX_FRAME) {
            pending.write(buf, i, tail)
            carriedPts = if (i < pesStart) carryAnchor else pesAnchor
        }
        pendingInSync = inSync
    }

    override fun finish() = emitUnit()

    private fun emitUnit() {
        if (unit.size == 0) return
        val fileOffset = sink.writeSampleData(unit.buffer, 0, unit.size)
        track.samples.add(fileOffset, unit.size, unitTs, unitTs, true, unitDescription)
        unit.reset()
    }

    /**
     * Reads the config of the frame at [i]. The first one configures the track; a different later
     * one (an ad at another rate or channel layout) freezes the first config's sample entry and
     * points the following samples at an entry of their own. False if the header is unusable.
     */
    private fun updateConfig(buf: ByteArray, i: Int, end: Int): Boolean {
        if (i + 8 > end) return configured
        val probe = Track(track.codec)
        try {
            configure(probe, buf, i)
        } catch (_: Exception) {
            return false
        }
        if (probe.sampleRate <= 0) return false
        val key = (probe.decoderConfigRecord ?: ByteArray(0)) + byteArrayOf((probe.sampleRate shr 8).toByte(), probe.sampleRate.toByte())
        if (configKey?.contentEquals(key) == true) return true
        if (!configured) {
            configure(track, buf, i)
            track.timescale = track.sampleRate
            configured = true
        } else {
            if (track.sampleEntryBox == null) track.sampleEntryBox = SampleEntries.build(track)
            descriptionIndex = track.descriptionIndexFor(SampleEntries.build(probe)!!)
            Logger.info("XDM", "${track.codec} config changed to ${probe.sampleRate} Hz, ${probe.channelCount} ch (sample entry $descriptionIndex)")
        }
        configKey = key
        rate = probe.sampleRate
        return true
    }

    /** Audio samples in an E-AC-3 frame: 256 per block, numblkscod 0..3 = 1/2/3/6 blocks (6 when fscod is 3). */
    private fun eac3Samples(b: ByteArray, i: Int): Int {
        val fscod = (b[i + 4].toInt() shr 6) and 3
        val blocks = if (fscod == 3) 6 else NUM_BLOCKS[(b[i + 4].toInt() shr 4) and 3]
        return blocks * 256
    }

    private fun isSync(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) == 0x0B && (b[i + 1].toInt() and 0xFF) == 0x77

    private fun ac3FrameSize(data: ByteArray, i: Int): Int {
        val fscod = (data[i + 4].toInt() and 0xC0) shr 6
        val frmsizecod = data[i + 4].toInt() and 0x3F
        if (fscod >= 3 || frmsizecod >= FRAME_SIZE_TAB.size) return -1
        return FRAME_SIZE_TAB[frmsizecod][fscod] * 2
    }

    private fun eac3FrameSize(data: ByteArray, i: Int): Int {
        val frmsiz = ((data[i + 2].toInt() and 0x07) shl 8) or (data[i + 3].toInt() and 0xFF)
        return (frmsiz + 1) * 2
    }

    private fun configure(track: Track, data: ByteArray, i: Int) {
        if (((data[i + 5].toInt() shr 3) and 0x1F) > 10) configureEac3(track, data, i) else configureAc3(track, data, i)
    }

    private fun configureAc3(track: Track, data: ByteArray, i: Int) {
        val bit = ParsableBitArray(data.copyOfRange(i + 4, minOf(i + 8, data.size)))
        val fscod = bit.readBits(2)
        val frmsizecod = bit.readBits(6)
        val bsid = bit.readBits(5)
        val bsmod = bit.readBits(3)
        val acmod = bit.readBits(3)
        if ((acmod and 0x1) != 0 && acmod != 0x1) bit.skipBits(2) // cmixlev
        if ((acmod and 0x4) != 0) bit.skipBits(2)                  // surmixlev
        if (acmod == 0x2) bit.skipBits(2)                          // dsurmod
        val lfeon = if (bit.readBit()) 1 else 0
        val bitRateCode = frmsizecod shr 1
        // bsid 9 and 10 are the half and quarter sample rate variants (A/52 annex).
        track.sampleRate = AC3_SAMPLE_RATES[fscod] shr maxOf(0, bsid - 8)
        track.channelCount = AC3_CHANNELS[acmod] + lfeon
        if (track.codec == Codec.EAC3) {
            // AC-3 core of an E-AC-3 stream: the track is ec-3, so describe the core in a dec3.
            track.decoderConfigRecord = dec3(fscod, bsid, bsmod, acmod, lfeon)
            return
        }
        // dac3: fscod(2) bsid(5) bsmod(3) acmod(3) lfeon(1) bit_rate_code(5) reserved(5)
        var v = 0
        v = (v shl 2) or fscod
        v = (v shl 5) or bsid
        v = (v shl 3) or bsmod
        v = (v shl 3) or acmod
        v = (v shl 1) or lfeon
        v = (v shl 5) or bitRateCode
        v = (v shl 5) // reserved
        track.decoderConfigRecord = byteArrayOf(
            (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()
        )
    }

    private fun configureEac3(track: Track, data: ByteArray, i: Int) {
        // Parse just enough of the E-AC-3 BSI for dec3.
        val bit = ParsableBitArray(data.copyOfRange(i + 2, minOf(i + 8, data.size)))
        bit.skipBits(2)            // strmtyp
        bit.skipBits(3)            // substreamid
        bit.skipBits(11)           // frmsiz
        val fscod = bit.readBits(2)
        val sr: Int
        val numblkscod: Int
        if (fscod == 3) {
            val fscod2 = bit.readBits(2)
            sr = AC3_SAMPLE_RATES_HALF[fscod2]
            numblkscod = 3
        } else {
            sr = AC3_SAMPLE_RATES[fscod]
            numblkscod = bit.readBits(2)
        }
        val acmod = bit.readBits(3)
        val lfeon = if (bit.readBit()) 1 else 0
        val bsid = bit.readBits(5)
        track.sampleRate = sr
        track.channelCount = AC3_CHANNELS[acmod] + lfeon
        @Suppress("UNUSED_VARIABLE") val numBlocks = NUM_BLOCKS[numblkscod]
        track.decoderConfigRecord = dec3(fscod, bsid, 0, acmod, lfeon)
    }

    /**
     * dec3 (ETSI TS 102 366 F.6) for one independent substream: data_rate(13) num_ind_sub(3) = 0,
     * then fscod(2) bsid(5) reserved(1) asvc(1) bsmod(3) acmod(3) lfeon(1) reserved(3)
     * num_dep_sub(4) reserved(1).
     */
    private fun dec3(fscod: Int, bsid: Int, bsmod: Int, acmod: Int, lfeon: Int): ByteArray {
        val dataRate = 0 // unknown; players tolerate 0
        val b0 = (dataRate shr 5) and 0xFF
        val b1 = ((dataRate and 0x1F) shl 3) or 0 // num_ind_sub - 1 = 0
        var s = 0
        s = (s shl 2) or fscod
        s = (s shl 5) or bsid
        s = (s shl 1) or 0        // reserved
        s = (s shl 1) or 0        // asvc
        s = (s shl 3) or bsmod
        s = (s shl 3) or acmod
        s = (s shl 1) or lfeon
        s = (s shl 3) or 0        // reserved
        // s is 19 bits; pad to 24 with num_dep_sub(4)+reserved(1)=0 -> but spec layout: append num_dep_sub(4), chan_loc absent
        s = (s shl 5) or 0
        return byteArrayOf(
            b0.toByte(), b1.toByte(),
            (s shr 16).toByte(), (s shr 8).toByte(), s.toByte()
        )
    }

    companion object {
        const val SAMPLES_PER_FRAME = 1536L
        /** Largest frame: AC-3 1920 words, E-AC-3 frmsiz 2048 words. */
        private const val MAX_FRAME = 4096
        val AC3_SAMPLE_RATES = intArrayOf(48000, 44100, 32000, 0)
        val AC3_SAMPLE_RATES_HALF = intArrayOf(24000, 22050, 16000, 0)
        val NUM_BLOCKS = intArrayOf(1, 2, 3, 6)
        val AC3_CHANNELS = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5) // by acmod
        // frmsizecod -> 16-bit words, columns [48k, 44.1k, 32k] (ATSC A/52 table 5.18)
        val FRAME_SIZE_TAB = arrayOf(
            intArrayOf(64, 69, 96), intArrayOf(64, 70, 96), intArrayOf(80, 87, 120), intArrayOf(80, 88, 120),
            intArrayOf(96, 104, 144), intArrayOf(96, 105, 144), intArrayOf(112, 121, 168), intArrayOf(112, 122, 168),
            intArrayOf(128, 139, 192), intArrayOf(128, 140, 192), intArrayOf(160, 174, 240), intArrayOf(160, 175, 240),
            intArrayOf(192, 208, 288), intArrayOf(192, 209, 288), intArrayOf(224, 243, 336), intArrayOf(224, 244, 336),
            intArrayOf(256, 278, 384), intArrayOf(256, 279, 384), intArrayOf(320, 348, 480), intArrayOf(320, 349, 480),
            intArrayOf(384, 417, 576), intArrayOf(384, 418, 576), intArrayOf(448, 487, 672), intArrayOf(448, 488, 672),
            intArrayOf(512, 557, 768), intArrayOf(512, 558, 768), intArrayOf(640, 696, 960), intArrayOf(640, 697, 960),
            intArrayOf(768, 835, 1152), intArrayOf(768, 836, 1152), intArrayOf(896, 975, 1344), intArrayOf(896, 976, 1344),
            intArrayOf(1024, 1114, 1536), intArrayOf(1024, 1115, 1536), intArrayOf(1152, 1253, 1728), intArrayOf(1152, 1254, 1728),
            intArrayOf(1280, 1393, 1920), intArrayOf(1280, 1394, 1920)
        )
    }
}
