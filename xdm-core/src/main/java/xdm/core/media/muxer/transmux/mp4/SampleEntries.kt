// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.mp4

import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.es.H264Reader
import xdm.core.media.muxer.transmux.es.H265Reader
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.util.Logger

/**
 * Builds the stsd sample entry (avc1/hvc1/mp4a/ac-3/ec-3/Opus...) for a track demuxed from an
 * elementary stream, from its decoder config. Readers also use it to freeze the entry of a config
 * that is about to change mid-stream (Track.additionalSampleEntries).
 */
object SampleEntries {

    /**
     * A visual sample entry copied from an MP4 input (avc1/avc3/hvc1/hev1; av01 `pasp` only), with `colr` and `pasp` added
     * where the entry has none, as ffmpeg's stream copy writes them (movenc.c mov_write_video_tag: `colr`
     * when primaries, transfer and matrix are all specified, `pasp` when there is a SAR):
     * - colours from the entry's SPS (its own avcC/hvcC), which ffmpeg reads when the container has none;
     * - the SAR as ffmpeg's MP4 reader and fftools pick it: the container's (`pasp`, else the track's
     *   display size [displayWidth] x [displayHeight] from `tkhd` against the entry's coded size when they
     *   differ: mov.c mov_read_trak), else the SPS's.
     * Any other entry is returned as is.
     */
    fun withVuiBoxes(entry: ByteArray, displayWidth: Int, displayHeight: Int): ByteArray {
        if (entry.size < VISUAL_CHILDREN) return entry
        val type = String(entry, 4, 4, Charsets.ISO_8859_1)
        val hevc = type == "hvc1" || type == "hev1"
        val av1 = type == "av01"
        if (!hevc && !av1 && type != "avc1" && type != "avc3") return entry
        var config: Pair<Int, Int>? = null
        var hasColr = false
        var hasPasp = false
        var o = VISUAL_CHILDREN
        while (o + 8 <= entry.size) {
            val size = u32(entry, o).toInt()
            if (size < 8 || o + size > entry.size) break
            when (String(entry, o + 4, 4, Charsets.ISO_8859_1)) {
                "avcC", "hvcC" -> config = o + 8 to o + size
                "colr" -> hasColr = true
                "pasp" -> hasPasp = true
            }
            o += size
        }
        if (hasColr && hasPasp) return entry
        if (entry.size < 36) return entry
        if (av1) return if (hasPasp) entry else appendBoxes(entry, o, paspBox(entry, displayWidth, displayHeight, 1, 1))
        val (from, to) = config ?: return entry
        val sps = (if (hevc) firstHvccSps(entry, from, to) else firstAvccSps(entry, from, to)) ?: return entry
        val t = Track(if (hevc) Codec.H265 else Codec.H264)
        try {
            if (hevc) H265Reader.parseSps(sps, t) else H264Reader.parseSps(sps, t)
        } catch (e: Exception) {
            Logger.info("XDM", "MP4: the $type entry's SPS doesn't parse ($e), copied as is")
            return entry
        }
        val extra = BoxBuf()
        if (!hasColr && t.colourPrimaries !in intArrayOf(-1, 2) && t.transferCharacteristics !in intArrayOf(-1, 2) &&
            t.matrixCoefficients !in intArrayOf(-1, 2)
        ) {
            extra.box("colr") {
                fourcc("nclx")
                u16(t.colourPrimaries); u16(t.transferCharacteristics); u16(t.matrixCoefficients)
                u8(if (t.fullRange) 0x80 else 0)
            }
        }
        val add = extra.toByteArray() + if (hasPasp) ByteArray(0) else paspBox(entry, displayWidth, displayHeight, t.sarNum, t.sarDen)
        return appendBoxes(entry, o, add)
    }

    /**
     * `pasp` for a copied visual entry: the container's SAR (track display size against the entry's coded
     * size, when they differ), else [num]:[den] from the bitstream; empty when there is none. For AV1
     * ffmpeg's decoders take render size against frame size from the first frame header (libdav1d.c,
     * av1dec.c), which is 1:1 unless render_and_frame_size_different is set: we don't parse frame headers
     * and use 1:1.
     */
    private fun paspBox(entry: ByteArray, displayWidth: Int, displayHeight: Int, num0: Int, den0: Int): ByteArray {
        val codedWidth = u16(entry, 32)
        val codedHeight = u16(entry, 34)
        var num = num0.toLong(); var den = den0.toLong()
        if (codedWidth > 0 && codedHeight > 0 && displayWidth > 0 && displayHeight > 0 &&
            (codedWidth != displayWidth || codedHeight != displayHeight)
        ) {
            num = codedHeight.toLong() * displayWidth; den = codedWidth.toLong() * displayHeight
        }
        if (num <= 0 || den <= 0) return ByteArray(0)
        val g = gcdL(num, den)
        return BoxBuf().apply { box("pasp") { u32(num / g); u32(den / g) } }.toByteArray()
    }

    /** [entry] cut after its last whole child box ([end]) with [add] appended and its size updated. */
    private fun appendBoxes(entry: ByteArray, end: Int, add: ByteArray): ByteArray {
        if (add.isEmpty()) return entry
        // Child boxes past the last whole one (none in practice) are dropped with the new size.
        val out = entry.copyOf(end + add.size)
        System.arraycopy(add, 0, out, end, add.size)
        val n = out.size
        out[0] = (n ushr 24).toByte(); out[1] = (n ushr 16).toByte(); out[2] = (n ushr 8).toByte(); out[3] = n.toByte()
        return out
    }

    /** First SPS of an avcC record (content [from, to)). */
    private fun firstAvccSps(e: ByteArray, from: Int, to: Int): ByteArray? {
        if (to - from < 8 || (e[from + 5].toInt() and 0x1F) == 0) return null
        val len = u16(e, from + 6)
        return if (from + 8 + len <= to && len > 0) e.copyOfRange(from + 8, from + 8 + len) else null
    }

    /** First SPS (NAL type 33) of an hvcC record (content [from, to)). */
    private fun firstHvccSps(e: ByteArray, from: Int, to: Int): ByteArray? {
        if (to - from < 23) return null
        var p = from + 23
        repeat(e[from + 22].toInt() and 0xFF) {
            if (p + 3 > to) return null
            val nalType = e[p].toInt() and 0x3F
            val count = u16(e, p + 1)
            p += 3
            repeat(count) {
                if (p + 2 > to) return null
                val len = u16(e, p)
                if (p + 2 + len > to) return null
                if (nalType == 33 && len > 0) return e.copyOfRange(p + 2, p + 2 + len)
                p += 2 + len
            }
        }
        return null
    }

    private tailrec fun gcdL(a: Long, b: Long): Long = if (b == 0L) a else gcdL(b, a % b)

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

    /** Header (8) + VisualSampleEntry fields (78): where the child boxes start. */
    private const val VISUAL_CHILDREN = 86

    /**
     * The fields of an audio sample entry (with its header): ISO/QuickTime version 0, QuickTime v1
     * (16 more bytes) and v2 (36 more; rate as a double, LPCM flags). An ISO `AudioSampleEntryV1` has
     * version 1 without the 16 bytes, so v1 takes whichever start has a box (or nothing) there.
     * [childStart] is where the child boxes (esds, dOps, pcmC...) begin.
     */
    class AudioInfo(val channels: Int, val sampleRate: Int, val bits: Int, val childStart: Int, val lpcmFlags: Int)

    fun audioInfo(entry: ByteArray): AudioInfo? {
        if (entry.size < 36) return null
        fun u16(o: Int) = ((entry[o].toInt() and 0xFF) shl 8) or (entry[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()
        return when (u16(16)) {
            2 -> {
                if (entry.size < 72) return null
                val rate = java.lang.Double.longBitsToDouble((u32(40) shl 32) or u32(44))
                AudioInfo(u32(48).toInt(), rate.toInt(), u32(56).toInt(), 72, u32(60).toInt())
            }
            1 -> AudioInfo(u16(24), u16(32), u16(26), if (boxAt(entry, 52)) 52 else 36, 0)
            else -> AudioInfo(u16(24), u16(32), u16(26), 36, 0)
        }
    }

    /** A plausible box header at [p] (or the end of the entry, where no children follow). */
    private fun boxAt(e: ByteArray, p: Int): Boolean {
        if (p == e.size) return true
        if (p + 8 > e.size) return false
        val size = ((e[p].toLong() and 0xFF) shl 24) or ((e[p + 1].toLong() and 0xFF) shl 16) or
            ((e[p + 2].toLong() and 0xFF) shl 8) or (e[p + 3].toLong() and 0xFF)
        return size >= 8 && p + size <= e.size && (4 until 8).all { e[p + it] in 0x20..0x7E }
    }

    /** The complete sample entry box for [t]'s current config, or null if its codec has none. */
    fun build(t: Track): ByteArray? {
        val b = BoxBuf()
        write(b, t)
        return if (b.size() == 0) null else b.toByteArray()
    }

    fun write(buf: BoxBuf, t: Track) {
        when (t.codec) {
            Codec.H264 -> visualSampleEntry(buf, "avc1", t) { CodecBoxes.writeAvcC(this, t) }
            // hvc1, not hev1: AVFoundation (QuickTime, Safari, iOS) refuses hev1 ("Cannot Decode");
            // Media Foundation and GStreamer take either. Parameter sets also stay in-band, as with
            // ffmpeg's -tag:v hvc1, which those players accept.
            Codec.H265 -> visualSampleEntry(buf, "hvc1", t) { CodecBoxes.writeHvcC(this, t) }
            Codec.AAC -> audioSampleEntry(buf, "mp4a", t) {
                CodecBoxes.writeEsds(this, CodecBoxes.OTI_AAC, t.audioSpecificConfig)
            }
            Codec.MP3 -> audioSampleEntry(buf, "mp4a", t) {
                val oti = if (t.sampleRate >= 32000) CodecBoxes.OTI_MPEG1_AUDIO else CodecBoxes.OTI_MPEG2_AUDIO
                CodecBoxes.writeEsds(this, oti, null)
            }
            Codec.AC3 -> audioSampleEntry(buf, "ac-3", t) {
                box("dac3") { bytes(t.decoderConfigRecord ?: ByteArray(3)) }
            }
            Codec.EAC3 -> audioSampleEntry(buf, "ec-3", t) {
                box("dec3") { bytes(t.decoderConfigRecord ?: ByteArray(5)) }
            }
            Codec.OPUS -> t.decoderConfigRecord?.let { head ->
                audioSampleEntry(buf, "Opus", t) { CodecBoxes.writeDOps(this, head) }
            }
            // MPEG-1/2 and MPEG-4 Part 2 video: mp4v, the sequence header / VOL as DecoderSpecificInfo.
            Codec.MPEG_VIDEO -> visualSampleEntry(buf, "mp4v", t) {
                CodecBoxes.writeEsds(this, t.objectTypeIndication, t.decoderConfigRecord, video = true)
            }
            Codec.MPEG4_VISUAL -> visualSampleEntry(buf, "mp4v", t) {
                CodecBoxes.writeEsds(this, CodecBoxes.OTI_MPEG4_VISUAL, t.decoderConfigRecord, video = true)
            }
            Codec.DTS -> audioSampleEntry(buf, "mp4a", t) { CodecBoxes.writeEsds(this, CodecBoxes.OTI_DTS, null) }
            else -> {}
        }
    }

    /** A visual sample entry [type] (`avc1`, `hvc1`...) for [t]'s size, holding [configType] with [config] as its payload. */
    fun visualEntry(type: String, t: Track, configType: String, config: ByteArray): ByteArray {
        val b = BoxBuf()
        visualSampleEntry(b, type, t) { box(configType) { bytes(config) } }
        return b.toByteArray()
    }

    private inline fun visualSampleEntry(buf: BoxBuf, type: String, t: Track, config: BoxBuf.() -> Unit) {
        buf.box(type) {
            repeat(6) { u8(0) }             // reserved
            u16(1)                          // data_reference_index
            u16(0); u16(0)                  // pre_defined, reserved
            u32(0); u32(0); u32(0)          // pre_defined
            u16(t.width); u16(t.height)
            u32(0x00480000); u32(0x00480000) // resolution 72dpi
            u32(0)
            u16(1)                          // frame_count
            repeat(32) { u8(0) }            // compressorname
            u16(0x0018)                     // depth
            u16(0xFFFF)                     // pre_defined
            config()
            // As ffmpeg: colr when the bitstream describes its colours (HDR10 needs it to be shown as
            // HDR), pasp when it gives a sample aspect ratio (anamorphic video).
            if (t.colourPrimaries !in intArrayOf(-1, 2) && t.transferCharacteristics !in intArrayOf(-1, 2) &&
                t.matrixCoefficients !in intArrayOf(-1, 2)
            ) {
                box("colr") {
                    fourcc("nclx")
                    u16(t.colourPrimaries); u16(t.transferCharacteristics); u16(t.matrixCoefficients)
                    u8(if (t.fullRange) 0x80 else 0)
                }
            }
            if (t.sarNum > 0 && t.sarDen > 0) {
                // Reduced, as ffmpeg writes it (1024:1024 -> 1:1).
                val g = gcd(t.sarNum, t.sarDen)
                box("pasp") { u32(t.sarNum / g); u32(t.sarDen / g) }
            }
        }
    }

    private inline fun audioSampleEntry(buf: BoxBuf, type: String, t: Track, config: BoxBuf.() -> Unit) {
        buf.box(type) {
            repeat(6) { u8(0) }             // reserved
            u16(1)                          // data_reference_index
            u32(0); u32(0)                  // version/revision/vendor
            u16(if (t.channelCount > 0) t.channelCount else 2)
            u16(16)                         // samplesize
            u16(0); u16(0)                  // pre_defined, reserved
            // 16.16 fixed point: a rate above 65535 is halved until it fits (96000 -> 48000), as ffmpeg
            // writes it; the decoder takes the real rate from its config (ASC, dac3...).
            var rate = t.sampleRate.toLong()
            while (rate > 0xFFFF) rate /= 2
            u32(rate shl 16)
            config()
        }
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
