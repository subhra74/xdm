// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.mp4.SampleEntries
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.util.Logger

/**
 * MPEG-1/2/2.5 audio reader for Layers I, II (MP2) and III (MP3). Splits the stream into frames
 * using the frame header, each becoming one sample (384 samples for Layer I, 1152 for Layer II and
 * MPEG-1 Layer III, 576 for MPEG-2/2.5 Layer III). Frames are copied verbatim; the MP4 sample entry
 * needs no decoder-specific config.
 *
 * The 0xFFE sync pattern also occurs inside frame payloads, so a header found by scanning is only
 * accepted if the next frame's header follows where expected, and once the first frame is accepted
 * the stream is locked to its version/layer/sample-rate (as in media3's MpegAudioReader). A frame
 * split across PES packets is carried over and completed by the next [consume].
 */
class MpegAudioReader(private val sink: SampleSink) : ElementaryStreamReader {
    override val track = Track(Codec.MP3)
    /** `header and HEADER_MASK` of the frames being read; 0 until the first is accepted. */
    private var lockedHeader = 0
    private val clock = FrameClock()
    /** Sampling rate of the current frames (the track timescale stays the first one's). */
    private var rate = 0
    private var descriptionIndex = 1
    private val pending = ByteArrayBuilder(MAX_CARRY)
    /** Whether [pending] starts at a frame boundary we were already in sync with. */
    private var pendingInSync = false
    /** PTS of the PES the carried partial frame started in, when that frame was its first. */
    private var carriedPts = -1L

    private class Frame(val size: Int, val samples: Int, val sampleRate: Int, val channels: Int, val layer: Int)

    override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) {
        val buf: ByteArray
        var i: Int
        val end: Int
        val carried = pending.size
        if (carried > 0) {
            // Complete the frame left over from the previous PES.
            buf = ByteArray(pending.size + length)
            System.arraycopy(pending.buffer, 0, buf, 0, pending.size)
            System.arraycopy(data, offset, buf, pending.size, length)
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

        while (i + 4 <= end) {
            val header = readInt(buf, i)
            val frame = parseHeader(header)
            if (frame == null) {
                i++; inSync = false; continue
            }
            val changed = lockedHeader != 0 && (header and HEADER_MASK) != lockedHeader
            if (i + frame.size > end) break // incomplete: carried over below
            // A header with another version/layer/rate is a config change only when the next frame
            // confirms it (the sync pattern also occurs inside frame data).
            if (changed && i + frame.size + 4 > end) break // confirm once the next PES arrives
            if (changed && !confirmedByNextHeader(buf, i + frame.size, end, header, strict = true)) {
                i++; inSync = false; continue
            }
            if (!inSync && !confirmedByNextHeader(buf, i + frame.size, end, header)) {
                i++; continue
            }

            if (lockedHeader == 0) {
                track.sampleRate = frame.sampleRate
                track.channelCount = frame.channels
                track.timescale = frame.sampleRate
                track.mpegAudioLayer = frame.layer
            } else if (changed) {
                // E.g. an ad at another sampling rate: freeze the first entry, give these frames their own.
                if (track.sampleEntryBox == null) track.sampleEntryBox = SampleEntries.build(track)
                val next = Track(Codec.MP3).also {
                    it.sampleRate = frame.sampleRate; it.channelCount = frame.channels; it.mpegAudioLayer = frame.layer
                }
                descriptionIndex = track.descriptionIndexFor(SampleEntries.build(next)!!)
                Logger.info("XDM", "MPEG audio changed to layer ${frame.layer}, ${frame.sampleRate} Hz (sample entry $descriptionIndex)")
            }
            lockedHeader = header and HEADER_MASK
            rate = frame.sampleRate
            val timescale = track.timescale
            val anchor = if (i < pesStart) carryAnchor else pesAnchor
            if (anchor >= 0) {
                clock.anchor(anchor, timescale, frame.samples.toLong() * timescale / rate)
                if (i < pesStart) carryAnchor = -1 else pesAnchor = -1
            }

            val fileOffset = sink.writeSampleData(buf, i, frame.size)
            val ts = clock.current()
            track.samples.add(fileOffset, frame.size, ts, ts, true, descriptionIndex)
            clock.advance(frame.samples, rate, timescale)
            i += frame.size
            inSync = true
        }
        val tail = end - i
        if (tail in 1..MAX_CARRY) {
            pending.write(buf, i, tail)
            carriedPts = if (i < pesStart) carryAnchor else pesAnchor
        }
        pendingInSync = inSync
    }

    override fun finish() {}

    /**
     * True if a matching frame header starts at [next], or if [next] is too close to [end] to tell
     * (unless [strict]).
     */
    private fun confirmedByNextHeader(buf: ByteArray, next: Int, end: Int, header: Int, strict: Boolean = false): Boolean {
        if (next + 4 > end) return !strict
        val nextHeader = readInt(buf, next)
        return parseHeader(nextHeader) != null && (nextHeader and HEADER_MASK) == (header and HEADER_MASK)
    }

    private fun readInt(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    companion object {
        /** Sync, version, layer and sample-rate bits: constant for the whole stream. */
        private const val HEADER_MASK = 0xFFFE0C00.toInt()
        /** Larger than the biggest legal frame (MPEG-2 Layer II, 160 kbps @ 8 kHz = 2881 bytes). */
        private const val MAX_CARRY = 4096

        private val BITRATE_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
        private val BITRATE_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        private val BITRATE_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val BITRATE_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
        private val BITRATE_V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val SAMPLE_RATES_V1 = intArrayOf(44100, 48000, 32000)

        /** Parses a 32-bit MPEG audio frame header, or returns null if it isn't a valid one. */
        private fun parseHeader(h: Int): Frame? {
            if ((h and 0xFFE00000.toInt()) != 0xFFE00000.toInt()) return null
            val version = (h ushr 19) and 3      // 0 = MPEG-2.5, 1 = reserved, 2 = MPEG-2, 3 = MPEG-1
            val layerBits = (h ushr 17) and 3    // 1 = III, 2 = II, 3 = I, 0 = reserved
            val bitrateIndex = (h ushr 12) and 0xF
            val sampleRateIndex = (h ushr 10) and 3
            val padding = (h ushr 9) and 1
            val channelMode = (h ushr 6) and 3
            val emphasis = h and 3
            // Free-format (bitrate index 0) has no computable frame size; not supported.
            if (version == 1 || layerBits == 0 || bitrateIndex == 0 || bitrateIndex == 15 ||
                sampleRateIndex == 3 || emphasis == 2
            ) return null

            val mpeg1 = version == 3
            val layer = 4 - layerBits
            val sampleRate = SAMPLE_RATES_V1[sampleRateIndex] / (if (mpeg1) 1 else if (version == 2) 2 else 4)
            val kbps = when {
                mpeg1 && layer == 1 -> BITRATE_V1_L1
                mpeg1 && layer == 2 -> BITRATE_V1_L2
                mpeg1 -> BITRATE_V1_L3
                layer == 1 -> BITRATE_V2_L1
                else -> BITRATE_V2_L23
            }[bitrateIndex]
            val bitrate = kbps * 1000
            val size: Int
            val samples: Int
            when {
                layer == 1 -> { size = (12 * bitrate / sampleRate + padding) * 4; samples = 384 }
                layer == 2 || mpeg1 -> { size = 144 * bitrate / sampleRate + padding; samples = 1152 }
                else -> { size = 72 * bitrate / sampleRate + padding; samples = 576 }
            }
            if (size < 4) return null
            return Frame(size, samples, sampleRate, if (channelMode == 3) 1 else 2, layer)
        }
    }
}
