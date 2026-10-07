package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Sample
import xdm.core.media.muxer.transmux.sample.Track

/**
 * Opus reader for MPEG-TS (the opus-codec.org "Opus in MPEG-2 TS" carriage that ffmpeg writes:
 * stream_type 0x06 + "Opus" registration descriptor).
 * Each PES holds one or more access units, each prefixed by an `opus_control_header`: an 11-bit
 * 0x3FF prefix, trim/extension flags, a 0xFF-laced au_size, then optional start/end trim and
 * extension bytes. Every Opus packet becomes one sample at 48 kHz; its duration comes from the
 * packet's TOC byte. The channel layout comes from the PMT's Opus extension descriptor
 * ([channelConfigCode]); the first AU's start trim becomes the pre-skip.
 */
class OpusReader(private val sink: SampleSink, channelConfigCode: Int?) : ElementaryStreamReader {
    override val track = Track(Codec.OPUS)
    private var nextPtsTicks = Long.MIN_VALUE
    private var sawFirstAu = false

    private val channels: Int
    private val mappingFamily: Int
    private val streamCount: Int
    private val coupledCount: Int
    private val channelMapping: ByteArray

    init {
        // channel_config_code 0x00..0x08 (tables as in ffmpeg's mpegts demuxer); anything else or a
        // missing descriptor falls back to plain stereo.
        val code = channelConfigCode?.takeIf { it in 0..8 }
        channels = if (code == null || code == 0) 2 else code
        mappingFamily = when {
            code == null -> 0
            code == 0 -> 255
            channels > 2 -> 1
            else -> 0
        }
        streamCount = if (code == null) 1 else STREAM_COUNT[code]
        coupledCount = if (code == null) 1 else COUPLED_COUNT[code]
        channelMapping = CHANNEL_MAP[channels - 1]
        track.sampleRate = SAMPLE_RATE
        track.timescale = SAMPLE_RATE
        track.channelCount = channels
        track.decoderConfigRecord = opusHead(preSkip = 0)
    }

    override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) {
        val end = offset + length
        var i = offset
        var pesAnchor = pts

        while (i + 2 <= end) {
            // opus_control_header_prefix: 11 bits of 1 (0x7FE0 in the first 16 bits).
            if ((data[i].toInt() and 0xFF) != 0x7F || (data[i + 1].toInt() and 0xE0) != 0xE0) break
            val flags = data[i + 1].toInt()
            var p = i + 2
            var auSize = 0
            while (p < end) {
                val b = data[p++].toInt() and 0xFF
                auSize += b
                if (b != 0xFF) break
            }
            var startTrim = 0
            if (flags and 0x10 != 0) {
                if (p + 2 > end) break
                startTrim = (((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)) and 0x1FFF
                p += 2
            }
            if (flags and 0x08 != 0) p += 2 // end_trim: not applied
            if (flags and 0x04 != 0) {
                if (p >= end) break
                p += 1 + (data[p].toInt() and 0xFF)
            }
            if (auSize <= 0 || p + auSize > end) break

            if (!sawFirstAu) {
                sawFirstAu = true
                if (startTrim > 0) track.decoderConfigRecord = opusHead(preSkip = startTrim)
            }
            val samples = packetSamples(data, p, auSize)
            if (pesAnchor >= 0 && nextPtsTicks == Long.MIN_VALUE) {
                nextPtsTicks = pesAnchor * SAMPLE_RATE / 90000L
            } else if (pesAnchor >= 0) {
                val expected = pesAnchor * SAMPLE_RATE / 90000L
                if (kotlin.math.abs(expected - nextPtsTicks) > 2L * maxOf(samples, 960)) nextPtsTicks = expected
                pesAnchor = -1
            }
            if (nextPtsTicks == Long.MIN_VALUE) nextPtsTicks = 0

            val fileOffset = sink.writeSampleData(data, p, auSize)
            track.samples.add(Sample(fileOffset, auSize, nextPtsTicks, nextPtsTicks, true))
            nextPtsTicks += samples
            i = p + auSize
        }
    }

    override fun finish() {}

    /** Builds an `OpusHead` (RFC 7845 §5.1, little-endian): the Ogg/Matroska form of the Opus config. */
    private fun opusHead(preSkip: Int): ByteArray {
        val size = 19 + if (mappingFamily != 0) 2 + channels else 0
        val h = ByteArray(size)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(h)
        h[8] = 1                                   // version
        h[9] = channels.toByte()
        h[10] = preSkip.toByte(); h[11] = (preSkip shr 8).toByte()
        h[12] = SAMPLE_RATE.toByte(); h[13] = (SAMPLE_RATE shr 8).toByte()
        h[14] = (SAMPLE_RATE shr 16).toByte(); h[15] = (SAMPLE_RATE shr 24).toByte()
        // h[16..17] output gain = 0
        h[18] = mappingFamily.toByte()
        if (mappingFamily != 0) {
            h[19] = streamCount.toByte()
            h[20] = coupledCount.toByte()
            channelMapping.copyInto(h, 21, 0, channels)
        }
        return h
    }

    companion object {
        const val SAMPLE_RATE = 48000

        // Indexed by channel_config_code 0..8.
        private val STREAM_COUNT = intArrayOf(1, 1, 1, 2, 2, 3, 4, 4, 5)
        private val COUPLED_COUNT = intArrayOf(1, 0, 1, 1, 2, 2, 2, 3, 3)
        // Vorbis channel order mapping (family 1), indexed by channels - 1.
        private val CHANNEL_MAP = arrayOf(
            byteArrayOf(0), byteArrayOf(0, 1), byteArrayOf(0, 2, 1), byteArrayOf(0, 1, 2, 3),
            byteArrayOf(0, 4, 1, 2, 3), byteArrayOf(0, 4, 1, 2, 3, 5), byteArrayOf(0, 4, 1, 2, 3, 5, 6),
            byteArrayOf(0, 6, 1, 2, 3, 4, 5, 7)
        )

        /** Samples (at 48 kHz) in an Opus packet, from its TOC byte (RFC 6716 §3.1). */
        fun packetSamples(data: ByteArray, offset: Int, length: Int): Int {
            if (length < 1) return 0
            val toc = data[offset].toInt() and 0xFF
            val config = toc shr 3
            val frameSamples = when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]  // SILK: 10/20/40/60 ms
                config < 16 -> if (config and 1 == 0) 480 else 960             // Hybrid: 10/20 ms
                else -> intArrayOf(120, 240, 480, 960)[config and 3]           // CELT: 2.5/5/10/20 ms
            }
            val frames = when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> if (length >= 2) data[offset + 1].toInt() and 0x3F else 0
            }
            return frameSamples * frames
        }
    }
}
