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
 * DTS reader (ETSI TS 102 114) for TS stream types 0x82/0x85/0x86/0x8A, the DTS descriptor or a
 * DTS1/2/3 registration. Core frames start with the 16-bit big-endian sync 0x7FFE8001; a DTS-HD
 * extension substream (sync 0x64582025) following a core frame belongs to the same sample, as in
 * ffmpeg's DTS parser. One sample = (NBLKS + 1) * 32 audio samples.
 *
 * Frames split across PES packets are carried over; the PES PTS belongs to the first core frame
 * starting in that PES; a header found by scanning needs another sync right after its frame.
 */
class DtsReader(private val sink: SampleSink) : ElementaryStreamReader {
    override val track = Track(Codec.DTS)

    private val clock = FrameClock()
    private var configured = false
    private var rate = 0
    private var descriptionIndex = 1
    private var configKey = -1

    private val pending = ByteArrayBuilder(MAX_FRAME)
    private var pendingInSync = false
    private var carriedPts = -1L

    private val unit = ByteArrayBuilder(MAX_FRAME * 2)
    private var unitTs = 0L
    private var unitDescription = 1

    override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) {
        val buf: ByteArray
        var i: Int
        val end: Int
        val carried = pending.size
        if (carried > 0) {
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
        val pesStart = i + carried
        var pesAnchor = pts
        var carryAnchor = carriedPts
        carriedPts = -1
        var inSync = carried > 0 && pendingInSync

        while (i + 16 <= end) {
            val core = isCoreSync(buf, i)
            val ext = !core && isExtSync(buf, i)
            if (!core && !ext) { i++; inSync = false; continue }
            val size = if (core) coreFrameSize(buf, i) else extFrameSize(buf, i)
            if (size < 16) { i++; inSync = false; continue }
            if (i + size > end) break // incomplete: carried over below
            if (!inSync && i + size + 4 <= end && !isCoreSync(buf, i + size) && !isExtSync(buf, i + size)) {
                i++; continue
            }
            if (ext) {
                // DTS-HD extension substream: part of the current core frame's sample.
                if (unit.size > 0) unit.write(buf, i, size)
                i += size; inSync = true
                continue
            }
            emitUnit()
            val header = ParsableBitArray(buf.copyOfRange(i + 4, i + 16))
            header.skipBits(1 + 5 + 1)                      // FTYPE, SHORT, CPF
            val samples = (header.readBits(7) + 1) * 32     // NBLKS
            header.skipBits(14)                             // FSIZE
            val amode = header.readBits(6)
            val sampleRate = SAMPLE_RATES[header.readBits(4)]
            header.skipBits(5 + 1 + 1 + 1 + 1 + 1 + 3 + 1 + 1) // RATE .. ASPF
            val lfe = if (header.readBits(2) != 0) 1 else 0
            if (sampleRate == 0) { i++; inSync = false; continue }
            updateConfig(sampleRate, (if (amode < CHANNELS.size) CHANNELS[amode] else 2) + lfe)

            val timescale = track.timescale
            val anchor = if (i < pesStart) carryAnchor else pesAnchor
            if (anchor >= 0) {
                clock.anchor(anchor, timescale, samples.toLong() * timescale / rate)
                if (i < pesStart) carryAnchor = -1 else pesAnchor = -1
            }
            unitTs = clock.current()
            unitDescription = descriptionIndex
            unit.write(buf, i, size)
            clock.advance(samples, rate, timescale)
            i += size
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
        val at = sink.writeSampleData(unit.buffer, 0, unit.size)
        track.samples.add(at, unit.size, unitTs, unitTs, true, unitDescription)
        unit.reset()
    }

    private fun updateConfig(sampleRate: Int, channels: Int) {
        val key = sampleRate * 16 + channels
        if (key == configKey) return
        if (!configured) {
            track.sampleRate = sampleRate; track.channelCount = channels; track.timescale = sampleRate
            configured = true
        } else {
            if (track.sampleEntryBox == null) track.sampleEntryBox = SampleEntries.build(track)
            val next = Track(Codec.DTS).also { it.sampleRate = sampleRate; it.channelCount = channels }
            descriptionIndex = track.descriptionIndexFor(SampleEntries.build(next)!!)
            Logger.info("XDM", "DTS config changed to $sampleRate Hz, $channels ch (sample entry $descriptionIndex)")
        }
        configKey = key
        rate = sampleRate
    }

    private fun isCoreSync(b: ByteArray, i: Int) = i + 4 <= b.size &&
        (b[i].toInt() and 0xFF) == 0x7F && (b[i + 1].toInt() and 0xFF) == 0xFE &&
        (b[i + 2].toInt() and 0xFF) == 0x80 && (b[i + 3].toInt() and 0xFF) == 0x01

    private fun isExtSync(b: ByteArray, i: Int) = i + 4 <= b.size &&
        (b[i].toInt() and 0xFF) == 0x64 && (b[i + 1].toInt() and 0xFF) == 0x58 &&
        (b[i + 2].toInt() and 0xFF) == 0x20 && (b[i + 3].toInt() and 0xFF) == 0x25

    /** FSIZE + 1: 14 bits starting 46 bits into the frame. */
    private fun coreFrameSize(b: ByteArray, i: Int): Int =
        ((((b[i + 5].toInt() and 0x03) shl 12) or ((b[i + 6].toInt() and 0xFF) shl 4) or
            ((b[i + 7].toInt() and 0xF0) shr 4))) + 1

    /** Extension substream size (nuExtSSFSIZE + 1), from its header after the sync word. */
    private fun extFrameSize(b: ByteArray, i: Int): Int {
        val bits = ParsableBitArray(b.copyOfRange(i + 4, minOf(b.size, i + 12)))
        bits.skipBits(8 + 2)                                // UserDefinedBits, nExtSSIndex
        return if (!bits.readBit()) {
            bits.skipBits(8); bits.readBits(16) + 1         // nuExtSSHeaderSize(8), nuExtSSFSIZE(16)
        } else {
            bits.skipBits(12); bits.readBits(20) + 1        // (12), (20)
        }
    }

    private companion object {
        /** Largest core frame (FSIZE is 14 bits) plus an extension substream. */
        const val MAX_FRAME = 1 shl 17
        val SAMPLE_RATES = intArrayOf(0, 8000, 16000, 32000, 0, 0, 11025, 22050, 44100, 0, 0, 12000, 24000, 48000, 0, 0)
        /** Channels by AMODE 0..15. */
        val CHANNELS = intArrayOf(1, 2, 2, 2, 2, 3, 3, 4, 4, 5, 6, 6, 6, 7, 8, 8)
    }
}
