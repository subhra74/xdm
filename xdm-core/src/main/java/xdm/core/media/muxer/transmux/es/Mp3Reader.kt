package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Sample
import xdm.core.media.muxer.transmux.sample.Track

/**
 * MPEG-1/2 Layer III (MP3) reader. Splits the stream into MP3 frames using the frame header,
 * each becoming one MP4 sample (1152 samples for MPEG-1, 576 for MPEG-2/2.5). Frames are copied
 * verbatim; the mp4a/.mp3 sample entry needs no decoder-specific config.
 */
class Mp3Reader(private val sink: SampleSink) : ElementaryStreamReader {
    override val track = Track(Codec.MP3)
    private var nextPtsTicks = Long.MIN_VALUE
    private var samplesPerFrame = 1152L

    override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) {
        val end = offset + length
        var i = offset
        var pesAnchor = pts

        while (i + 4 <= end) {
            if ((data[i].toInt() and 0xFF) != 0xFF || (data[i + 1].toInt() and 0xE0) != 0xE0) {
                i++
                continue
            }
            val versionBits = (data[i + 1].toInt() and 0x18) shr 3
            val layerBits = (data[i + 1].toInt() and 0x06) shr 1
            if (layerBits != 0x01) { i++; continue } // only Layer III
            val bitrateIndex = (data[i + 2].toInt() and 0xF0) shr 4
            val sampleRateIndex = (data[i + 2].toInt() and 0x0C) shr 2
            val padding = (data[i + 2].toInt() and 0x02) shr 1
            if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3 || versionBits == 1) { i++; continue }

            val mpeg1 = versionBits == 3
            val bitrate = (if (mpeg1) BITRATE_V1_L3 else BITRATE_V2_L3)[bitrateIndex] * 1000
            val sampleRate = when (versionBits) {
                3 -> SR_V1[sampleRateIndex]
                2 -> SR_V2[sampleRateIndex]
                else -> SR_V25[sampleRateIndex]
            }
            samplesPerFrame = if (mpeg1) 1152L else 576L
            val frameSize = if (mpeg1) {
                144 * bitrate / sampleRate + padding
            } else {
                72 * bitrate / sampleRate + padding
            }
            if (frameSize < 4 || i + frameSize > end) break

            if (track.sampleRate == 0) {
                track.sampleRate = sampleRate
                track.channelCount = if ((data[i + 3].toInt() and 0xC0) shr 6 == 3) 1 else 2
                track.timescale = sampleRate
            }
            if (pesAnchor >= 0 && nextPtsTicks == Long.MIN_VALUE) {
                nextPtsTicks = pesAnchor * track.sampleRate / 90000L
            } else if (pesAnchor >= 0) {
                val expected = pesAnchor * track.sampleRate / 90000L
                if (kotlin.math.abs(expected - nextPtsTicks) > 2L * samplesPerFrame) nextPtsTicks = expected
                pesAnchor = -1
            }
            if (nextPtsTicks == Long.MIN_VALUE) nextPtsTicks = 0

            val fileOffset = sink.writeSampleData(data, i, frameSize)
            track.samples.add(Sample(fileOffset, frameSize, nextPtsTicks, nextPtsTicks, true))
            nextPtsTicks += samplesPerFrame
            i += frameSize
        }
    }

    override fun finish() {}

    companion object {
        val BITRATE_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        val BITRATE_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        val SR_V1 = intArrayOf(44100, 48000, 32000, 0)
        val SR_V2 = intArrayOf(22050, 24000, 16000, 0)
        val SR_V25 = intArrayOf(11025, 12000, 8000, 0)
    }
}
