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
 * AAC in LOAS/LATM (ISO/IEC 14496-3 1.7; TS stream type 0x11, common in DVB broadcasts) turned
 * into plain AAC: MP4 has no LATM, so each AudioMuxElement's payload becomes a raw AAC sample and
 * the StreamMuxConfig's AudioSpecificConfig goes into the esds, as for ADTS input.
 *
 * Supported, like most decoders and broadcast streams: one program and layer, all streams with
 * the same time framing, one subframe per AudioMuxElement, frameLengthType 0. Anything else is
 * logged once and its frames dropped.
 */
class LatmReader(private val sink: SampleSink) : ElementaryStreamReader {
    override val track = Track(Codec.AAC)

    private val clock = FrameClock()
    private var rate = 0
    private var samplesPerFrame = 1024
    private var descriptionIndex = 1
    private var asc: ByteArray? = null
    private var unsupportedLogged = false

    // StreamMuxConfig state carried between AudioMuxElements (useSameStreamMux).
    private var configRead = false
    private var audioMuxVersionA = 0
    private var otherDataPresent = false
    private var otherDataLenBits = 0L

    private val pending = ByteArrayBuilder(MAX_FRAME)
    private var carriedPts = -1L

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

        while (i + 3 <= end) {
            // LOAS AudioSyncStream: syncword 0x2B7 (11 bits), audioMuxLengthBytes (13 bits).
            if ((buf[i].toInt() and 0xFF) != 0x56 || (buf[i + 1].toInt() and 0xE0) != 0xE0) { i++; continue }
            val len = ((buf[i + 1].toInt() and 0x1F) shl 8) or (buf[i + 2].toInt() and 0xFF)
            if (i + 3 + len > end) break // incomplete: carried over below
            // A sync found by scanning must be followed by another one (or the end of the data).
            val next = i + 3 + len
            if (next + 2 <= end && ((buf[next].toInt() and 0xFF) != 0x56 || (buf[next + 1].toInt() and 0xE0) != 0xE0)) {
                i++; continue
            }

            val payload = try {
                audioMuxElement(ParsableBitArray(buf.copyOfRange(i + 3, next)))
            } catch (e: Exception) {
                logUnsupported("malformed AudioMuxElement (${e.message})"); null
            }
            if (payload != null && rate > 0) {
                val timescale = track.timescale
                val anchor = if (i < pesStart) carryAnchor else pesAnchor
                if (anchor >= 0) {
                    clock.anchor(anchor, timescale, samplesPerFrame.toLong() * timescale / rate)
                    if (i < pesStart) carryAnchor = -1 else pesAnchor = -1
                }
                val at = sink.writeSampleData(payload, 0, payload.size)
                val ts = clock.current()
                track.samples.add(at, payload.size, ts, ts, true, descriptionIndex)
                clock.advance(samplesPerFrame, rate, timescale)
            }
            i = next
        }
        val tail = end - i
        if (tail in 1..MAX_FRAME) {
            pending.write(buf, i, tail)
            carriedPts = if (i < pesStart) carryAnchor else pesAnchor
        }
    }

    override fun finish() {}

    /** AudioMuxElement(muxConfigPresent = 1): the raw AAC payload, or null if not readable. */
    private fun audioMuxElement(b: ParsableBitArray): ByteArray? {
        val useSameStreamMux = b.readBit()
        if (!useSameStreamMux) {
            if (!streamMuxConfig(b)) return null
        } else if (!configRead) {
            return null // no config yet: wait for one
        }
        if (audioMuxVersionA != 0) return null
        // PayloadLengthInfo (frameLengthType 0): bytes as a run of 255s ended by a smaller value.
        var size = 0
        while (true) {
            val tmp = b.readBits(8)
            size += tmp
            if (tmp != 255) break
        }
        if (size <= 0 || !b.canReadBits(size * 8)) return null
        val out = ByteArray(size)
        for (k in 0 until size) out[k] = b.readBits(8).toByte()
        if (otherDataPresent) b.skipBits(minOf(otherDataLenBits, b.bitsLeft().toLong()).toInt())
        return out
    }

    /** StreamMuxConfig (14496-3 table 1.42). */
    private fun streamMuxConfig(b: ParsableBitArray): Boolean {
        val audioMuxVersion = b.readBits(1)
        audioMuxVersionA = if (audioMuxVersion == 1) b.readBits(1) else 0
        if (audioMuxVersionA != 0) return logUnsupported("audioMuxVersionA 1")
        if (audioMuxVersion == 1) latmGetValue(b)                 // taraBufferFullness
        if (!b.readBit()) return logUnsupported("streams with different time framing")
        if (b.readBits(6) != 0) return logUnsupported("several subframes per AudioMuxElement")
        if (b.readBits(4) != 0 || b.readBits(3) != 0) return logUnsupported("several programs or layers")
        val ascStart = b.position()
        val parsed: AscInfo
        val config: ByteArray
        if (audioMuxVersion == 0) {
            parsed = parseAsc(b)
            config = bitsToBytes(b, ascStart, b.position() - ascStart)
        } else {
            val ascLen = latmGetValue(b).toInt()
            parsed = parseAsc(b)
            config = bitsToBytes(b, ascStart, ascLen)
            b.setPosition(ascStart + ascLen)
        }
        when (val frameLengthType = b.readBits(3)) {
            0 -> b.skipBits(8)                                    // latmBufferFullness
            else -> return logUnsupported("frameLengthType $frameLengthType")
        }
        otherDataPresent = b.readBit()
        otherDataLenBits = 0
        if (otherDataPresent) {
            if (audioMuxVersion == 1) {
                otherDataLenBits = latmGetValue(b)
            } else {
                do {
                    val escape = b.readBit()
                    otherDataLenBits = (otherDataLenBits shl 8) + b.readBits(8)
                } while (escape)
            }
        }
        if (b.readBit()) b.skipBits(8)                            // crcCheckPresent: crcCheckSum
        applyConfig(config, parsed)
        configRead = true
        return true
    }

    private class AscInfo(val sampleRate: Int, val channels: Int, val frameLength: Int)

    /** Enough of an AudioSpecificConfig to know its length, rate, channels and frame length. */
    private fun parseAsc(b: ParsableBitArray): AscInfo {
        var aot = audioObjectType(b)
        var rate = samplingFrequency(b)
        val channelConfig = b.readBits(4)
        if (aot == 5 || aot == 29) {                              // explicit SBR (and PS)
            samplingFrequency(b)                                  // extension rate: output only
            aot = audioObjectType(b)
            if (aot == 22) b.skipBits(4)
        }
        var frameLength = 1024
        if (aot in intArrayOf(1, 2, 3, 4, 6, 7, 17, 19, 20, 21, 22, 23)) {
            if (b.readBit()) frameLength = 960                    // frameLengthFlag
            if (b.readBit()) b.skipBits(14)                       // dependsOnCoreCoder: coreCoderDelay
            val extensionFlag = b.readBit()
            if (channelConfig == 0) throw IllegalStateException("program_config_element")
            if (aot == 6 || aot == 20) b.skipBits(3)              // layerNr
            if (extensionFlag) {
                if (aot == 22) b.skipBits(5 + 11)
                if (aot == 17 || aot == 19 || aot == 20 || aot == 23) b.skipBits(3)
                b.skipBits(1)                                     // extensionFlag3
            }
        } else {
            throw IllegalStateException("audio object type $aot")
        }
        if (aot in intArrayOf(17, 19, 20, 21, 22, 23, 24, 25, 26, 27)) {
            val epConfig = b.readBits(2)
            if (epConfig == 2 || epConfig == 3) throw IllegalStateException("epConfig $epConfig")
        }
        val channels = if (channelConfig in 1..7) AdtsReader.CHANNELS[channelConfig] else channelConfig
        return AscInfo(rate, channels, frameLength)
    }

    private fun audioObjectType(b: ParsableBitArray): Int {
        val t = b.readBits(5)
        return if (t == 31) 32 + b.readBits(6) else t
    }

    private fun samplingFrequency(b: ParsableBitArray): Int {
        val index = b.readBits(4)
        return if (index == 15) b.readBits(24) else AdtsReader.SAMPLE_RATES[index]
    }

    /** The first config configures the track; a changed one adds a sample entry. */
    private fun applyConfig(config: ByteArray, info: AscInfo) {
        if (asc?.contentEquals(config) == true) return
        if (info.sampleRate <= 0) return
        if (asc == null) {
            track.sampleRate = info.sampleRate
            track.channelCount = info.channels
            track.timescale = info.sampleRate
            track.audioSpecificConfig = config
        } else {
            if (track.sampleEntryBox == null) track.sampleEntryBox = SampleEntries.build(track)
            val next = Track(Codec.AAC).also {
                it.sampleRate = info.sampleRate; it.channelCount = info.channels; it.audioSpecificConfig = config
            }
            descriptionIndex = track.descriptionIndexFor(SampleEntries.build(next)!!)
            Logger.info("XDM", "LATM AAC config changed to ${info.sampleRate} Hz, ${info.channels} ch (sample entry $descriptionIndex)")
        }
        asc = config
        rate = info.sampleRate
        samplesPerFrame = info.frameLength
    }

    private fun latmGetValue(b: ParsableBitArray): Long {
        val bytes = b.readBits(2) + 1
        return b.readBitsLong(bytes * 8)
    }

    /** [bitLength] bits from [startBit] as bytes, the last one zero-padded. */
    private fun bitsToBytes(b: ParsableBitArray, startBit: Int, bitLength: Int): ByteArray {
        val resume = b.position()
        b.setPosition(startBit)
        val out = ByteArray((bitLength + 7) / 8)
        var left = bitLength
        var k = 0
        while (left > 0) {
            val n = minOf(8, left)
            out[k++] = (b.readBits(n) shl (8 - n)).toByte()
            left -= n
        }
        b.setPosition(resume)
        return out
    }

    private fun logUnsupported(what: String): Boolean {
        if (!unsupportedLogged) {
            Logger.error("XDM", "LATM: unsupported $what; its frames are dropped")
            unsupportedLogged = true
        }
        return false
    }

    private companion object {
        /** audioMuxLengthBytes is 13 bits. */
        const val MAX_FRAME = 3 + 8191
    }
}
