package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.io.ParsableBitArray
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Sample
import xdm.core.media.muxer.transmux.sample.Track

/**
 * (Enhanced) AC-3 reader. Frames are delimited by the 0x0B77 syncword. For AC-3 the frame size
 * comes from the frmsizecod table; for E-AC-3 it is the explicit `frmsiz` field. The dac3/dec3
 * config box payload is synthesized from the first frame header. One frame = 1536 audio samples.
 */
class Ac3Reader(private val sink: SampleSink, eac3: Boolean) : ElementaryStreamReader {
    override val track = Track(if (eac3) Codec.EAC3 else Codec.AC3)
    private val eac3 = eac3
    private var nextPtsTicks = Long.MIN_VALUE
    private var configured = false

    override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) {
        val end = offset + length
        var i = offset
        var pesAnchor = pts

        while (i + 6 <= end) {
            if ((data[i].toInt() and 0xFF) != 0x0B || (data[i + 1].toInt() and 0xFF) != 0x77) {
                i++
                continue
            }
            val frameSize = if (eac3) eac3FrameSize(data, i) else ac3FrameSize(data, i)
            if (frameSize <= 0 || i + frameSize > end) break

            if (!configured) {
                if (eac3) configureEac3(data, i) else configureAc3(data, i)
                configured = true
            }
            val sr = track.sampleRate
            if (sr <= 0) break

            if (pesAnchor >= 0 && nextPtsTicks == Long.MIN_VALUE) {
                nextPtsTicks = pesAnchor * sr / 90000L
            } else if (pesAnchor >= 0) {
                val expected = pesAnchor * sr / 90000L
                if (kotlin.math.abs(expected - nextPtsTicks) > 2L * SAMPLES_PER_FRAME) nextPtsTicks = expected
                pesAnchor = -1
            }
            if (nextPtsTicks == Long.MIN_VALUE) nextPtsTicks = 0

            val fileOffset = sink.writeSampleData(data, i, frameSize)
            track.samples.add(Sample(fileOffset, frameSize, nextPtsTicks, nextPtsTicks, true))
            nextPtsTicks += SAMPLES_PER_FRAME
            i += frameSize
        }
    }

    override fun finish() {}

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

    private fun configureAc3(data: ByteArray, i: Int) {
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
        track.sampleRate = AC3_SAMPLE_RATES[fscod]
        track.channelCount = AC3_CHANNELS[acmod] + lfeon
        track.timescale = track.sampleRate
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

    private fun configureEac3(data: ByteArray, i: Int) {
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
        track.timescale = track.sampleRate
        val numBlocks = NUM_BLOCKS[numblkscod]
        // dec3: data_rate(13) num_ind_sub(3=0) then per-substream:
        // fscod(2) bsid(5) reserved(1) asvc(1) bsmod(3) acmod(3) lfeon(1) reserved(3) num_dep_sub(4) ...
        // We emit a single independent substream descriptor.
        val dataRate = 0 // unknown; players tolerate 0
        val b0 = (dataRate shr 5) and 0xFF
        val b1 = ((dataRate and 0x1F) shl 3) or 0 // num_ind_sub - 1 = 0
        var s = 0
        s = (s shl 2) or fscod
        s = (s shl 5) or bsid
        s = (s shl 1) or 0        // reserved
        s = (s shl 1) or 0        // asvc
        s = (s shl 3) or 0        // bsmod
        s = (s shl 3) or acmod
        s = (s shl 1) or lfeon
        s = (s shl 3) or 0        // reserved
        // s is 19 bits; pad to 24 with num_dep_sub(4)+reserved(1)=0 -> but spec layout: append num_dep_sub(4), chan_loc absent
        s = (s shl 5) or 0
        track.decoderConfigRecord = byteArrayOf(
            b0.toByte(), b1.toByte(),
            (s shr 16).toByte(), (s shr 8).toByte(), s.toByte()
        )
        // numBlocks influences samples/frame, but E-AC-3 frames are still 1536 samples here.
        @Suppress("UNUSED_EXPRESSION") numBlocks
    }

    companion object {
        const val SAMPLES_PER_FRAME = 1536L
        val AC3_SAMPLE_RATES = intArrayOf(48000, 44100, 32000, 0)
        val AC3_SAMPLE_RATES_HALF = intArrayOf(24000, 22050, 16000, 0)
        val NUM_BLOCKS = intArrayOf(1, 2, 3, 6)
        val AC3_CHANNELS = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5) // by acmod
        // frmsizecod -> 16-bit words, columns [48k, 44.1k, 32k]
        val FRAME_SIZE_TAB = arrayOf(
            intArrayOf(64, 69, 64), intArrayOf(64, 70, 64), intArrayOf(80, 87, 80), intArrayOf(80, 88, 80),
            intArrayOf(96, 104, 96), intArrayOf(96, 105, 96), intArrayOf(112, 121, 112), intArrayOf(112, 122, 112),
            intArrayOf(128, 139, 128), intArrayOf(128, 140, 128), intArrayOf(160, 174, 160), intArrayOf(160, 175, 160),
            intArrayOf(192, 208, 192), intArrayOf(192, 209, 192), intArrayOf(224, 243, 224), intArrayOf(224, 244, 224),
            intArrayOf(256, 278, 256), intArrayOf(256, 279, 256), intArrayOf(320, 348, 320), intArrayOf(320, 349, 320),
            intArrayOf(384, 417, 384), intArrayOf(384, 418, 384), intArrayOf(448, 487, 448), intArrayOf(448, 488, 448),
            intArrayOf(512, 557, 512), intArrayOf(512, 558, 512), intArrayOf(640, 696, 640), intArrayOf(640, 697, 640),
            intArrayOf(768, 835, 768), intArrayOf(768, 836, 768), intArrayOf(896, 975, 896), intArrayOf(896, 976, 896),
            intArrayOf(1024, 1114, 1024), intArrayOf(1024, 1115, 1024), intArrayOf(1152, 1253, 1152), intArrayOf(1152, 1254, 1152),
            intArrayOf(1280, 1393, 1280), intArrayOf(1280, 1394, 1280)
        )
    }
}
