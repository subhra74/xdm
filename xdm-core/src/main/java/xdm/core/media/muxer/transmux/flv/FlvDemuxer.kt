// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.flv

import xdm.core.media.muxer.transmux.es.H264Reader
import xdm.core.media.muxer.transmux.es.H265Reader
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.mp4.SampleEntries
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.util.Logger
import java.io.RandomAccessFile

/**
 * FLV demuxer for the transmux path: FLV files or segments (an HLS playlist of FLV parts, a
 * multi-part FLV download), each with its own header. Reads the codecs MP4 and Matroska carry as
 * they are:
 *  - video: H.264 and HEVC, as legacy codec ids 7 and 12 or enhanced FLV (`avc1`/`hvc1` FourCC).
 *    The sequence header is the `avcC`/`hvcC` record and frames are length-prefixed NAL units, as
 *    in MP4: the record goes verbatim into an `avc1`/`hvc1` sample entry and frames are copied;
 *  - audio: AAC (AudioSpecificConfig + raw frames) and MP3 (whole frames).
 * Other codecs (Sorenson H.263, VP6, Speex, Nellymoser, enhanced FLV's other FourCCs) are left out
 * with a log line; a file with nothing readable is then published as it is by the caller.
 *
 * Timestamps are FLV's millisecond decode times plus the composition offset, so tracks use a
 * 1000 Hz timescale. Each part may start again at 0: the shared timeline step places it after the
 * previous one. A changed sequence header becomes another sample entry.
 */
class FlvDemuxer(private val sink: SampleSink) {

    private var video: Track? = null
    private var audio: Track? = null
    /** Sample entry index the next video / audio frames use. */
    private var videoEntry = 1
    private var audioEntry = 1
    /** Codecs already reported as unsupported. */
    private val reported = HashSet<String>()

    /** Audio/video codecs seen that this demuxer leaves out: the caller falls back rather than lose them. */
    val lostStreams = LinkedHashSet<String>()

    val tracks: List<Track> get() = listOfNotNull(video, audio)

    /** [from] is where the FLV header starts when the file has other bytes in front of it. */
    fun parseSegment(path: String, from: Long = 0) {
        RandomAccessFile(path, "r").use { raf ->
            val length = raf.length()
            raf.seek(from + 5)
            if (from + 9 > length) return
            val headerSize = raf.readInt().toLong() and 0xFFFFFFFFL
            var pos = from + headerSize + 4 // past PreviousTagSize0
            val header = ByteArray(11)
            while (pos + 11 <= length) {
                raf.seek(pos)
                raf.readFully(header)
                val type = header[0].toInt() and 0x1F
                val encrypted = header[0].toInt() and 0x20 != 0
                val size = u24(header, 1)
                // Timestamp: 24 bits plus an extension byte holding bits 24-31, signed.
                val dts = (u24(header, 4) or ((header[7].toInt() and 0xFF) shl 24)).toLong()
                val dataStart = pos + 11
                if (dataStart + size > length) {
                    Logger.info("XDM", "FLV: segment cut short inside a tag, the rest dropped: $path")
                    break
                }
                if (!encrypted && size > 0 && (type == 8 || type == 9)) {
                    val data = ByteArray(size)
                    raf.readFully(data)
                    if (type == 9) videoTag(data, dts) else audioTag(data, dts)
                }
                pos = dataStart + size + 4 // PreviousTagSize
            }
        }
    }

    fun finish() {}

    // ---- video ----

    private fun videoTag(d: ByteArray, dts: Long) {
        val b0 = d[0].toInt() and 0xFF
        if (b0 and 0x80 != 0) {
            // Enhanced FLV: frame type, packet type, FourCC.
            if (d.size < 5) return
            val frameType = (b0 shr 4) and 0x07
            val packetType = b0 and 0x0F
            val fourcc = String(d, 1, 4, Charsets.US_ASCII)
            val hevc = when (fourcc) { "avc1" -> false; "hvc1" -> true; else -> { unsupported("video $fourcc"); return } }
            when (packetType) {
                0 -> videoConfig(d.copyOfRange(5, d.size), hevc)                       // SequenceStart
                1 -> if (d.size > 8) videoFrame(d, 8, dts, si24(d, 5), frameType == 1) // CodedFrames
                3 -> videoFrame(d, 5, dts, 0, frameType == 1)                           // CodedFramesX: no CTS
                2, 4 -> {}                                                              // end, metadata
                else -> unsupported("enhanced FLV packet type $packetType")
            }
            return
        }
        val frameType = b0 shr 4
        val hevc = when (b0 and 0x0F) {
            7 -> false
            12 -> true // HEVC under codec id 12, as Chinese CDNs and ffmpeg write it
            else -> { unsupported("video codec id ${b0 and 0x0F}"); return }
        }
        if (frameType == 5 || d.size < 5) return // video info / command frame
        when (d[1].toInt()) {
            0 -> videoConfig(d.copyOfRange(5, d.size), hevc)
            1 -> videoFrame(d, 5, dts, si24(d, 2), frameType == 1)
        }
    }

    private fun videoConfig(record: ByteArray, hevc: Boolean) {
        if (record.size < (if (hevc) 23 else 7)) return
        // The picture size (and colour, aspect ratio) from the SPS, through the stream readers' parser.
        val reader = if (hevc) H265Reader(NO_SINK) else H264Reader(NO_SINK)
        for (nal in parameterSets(record, hevc)) reader.readParameterSet(nal)
        val entry = SampleEntries.visualEntry(if (hevc) "hvc1" else "avc1", reader.track, if (hevc) "hvcC" else "avcC", record)
        val t = video
        if (t == null) {
            video = Track(Codec.OTHER_VIDEO).also {
                it.timescale = 1000
                it.width = reader.track.width
                it.height = reader.track.height
                it.sampleEntryBox = entry
            }
            videoEntry = 1
        } else {
            videoEntry = t.descriptionIndexFor(entry)
        }
    }

    /** A frame at [from] in [d]: decode time [dts] ms, presented [cts] ms later. */
    private fun videoFrame(d: ByteArray, from: Int, dts: Long, cts: Long, key: Boolean) {
        val t = video ?: return // frames before the sequence header can't be decoded
        if (from >= d.size) return
        val offset = sink.writeSampleData(d, from, d.size - from)
        t.samples.add(offset, d.size - from, dts + cts, dts, key, videoEntry)
    }

    /** The NAL units of an `avcC` / `hvcC` record's parameter set arrays. */
    private fun parameterSets(r: ByteArray, hevc: Boolean): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        fun u16(o: Int) = ((r[o].toInt() and 0xFF) shl 8) or (r[o + 1].toInt() and 0xFF)
        try {
            if (hevc) {
                var p = 23
                repeat(r[22].toInt() and 0xFF) {
                    val n = u16(p + 1); p += 3
                    repeat(n) { val len = u16(p); out.add(r.copyOfRange(p + 2, p + 2 + len)); p += 2 + len }
                }
            } else {
                var p = 6
                repeat(r[5].toInt() and 0x1F) { val len = u16(p); out.add(r.copyOfRange(p + 2, p + 2 + len)); p += 2 + len }
                repeat(r[p++].toInt() and 0xFF) { val len = u16(p); out.add(r.copyOfRange(p + 2, p + 2 + len)); p += 2 + len }
            }
        } catch (_: IndexOutOfBoundsException) { /* a short record: use what was read */ }
        return out
    }

    // ---- audio ----

    private fun audioTag(d: ByteArray, dts: Long) {
        val format = (d[0].toInt() and 0xFF) shr 4
        when (format) {
            10 -> { // AAC
                if (d.size < 3) return
                if (d[1].toInt() == 0) aacConfig(d.copyOfRange(2, d.size))
                else audioFrame(d, 2, dts)
            }
            2, 14 -> { // MP3
                if (d.size < 5) return
                if (audio == null) mp3Track(d, 1) ?: return
                audioFrame(d, 1, dts)
            }
            else -> unsupported("audio format $format")
        }
    }

    private fun aacConfig(asc: ByteArray) {
        if (asc.size < 2) return
        val t = audio
        if (t == null) {
            audio = Track(Codec.AAC).also { it.timescale = 1000; configureAac(it, asc) }
            audioEntry = 1
            return
        }
        if (t.audioSpecificConfig?.contentEquals(asc) == true) return
        // A new config: the old one is frozen as a sample entry, the new one becomes the next.
        if (t.sampleEntryBox == null) t.sampleEntryBox = SampleEntries.build(t)
        configureAac(t, asc)
        audioEntry = SampleEntries.build(t)?.let { t.descriptionIndexFor(it) } ?: audioEntry
    }

    /** AudioSpecificConfig, rate and channels from [asc] (ISO/IEC 14496-3 1.6.2.1). */
    private fun configureAac(t: Track, asc: ByteArray) {
        val bits = (((asc[0].toInt() and 0xFF) shl 8) or (asc[1].toInt() and 0xFF)).toLong() shl 16 or
            (if (asc.size > 2) (asc[2].toLong() and 0xFF) shl 8 else 0L) or (if (asc.size > 3) asc[3].toLong() and 0xFF else 0L)
        var shift = 32
        fun take(n: Int): Int { shift -= n; return ((bits ushr shift) and ((1L shl n) - 1)).toInt() }
        if (take(5) == 31) take(6)
        val freqIndex = take(4)
        val rate = if (freqIndex == 15) take(24) else AAC_RATES.getOrElse(freqIndex) { 0 }
        t.audioSpecificConfig = asc
        t.sampleRate = rate
        t.channelCount = take(4).let { if (it == 7) 8 else it }
    }

    /** An MP3 track from the frame header at [from]; null when it isn't one. */
    private fun mp3Track(d: ByteArray, from: Int): Track? {
        val h1 = d[from + 1].toInt() and 0xFF
        val h2 = d[from + 2].toInt() and 0xFF
        val h3 = d[from + 3].toInt() and 0xFF
        if ((d[from].toInt() and 0xFF) != 0xFF || h1 and 0xE0 != 0xE0) return null
        val version = (h1 shr 3) and 0x03 // 3: MPEG-1, 2: MPEG-2, 0: MPEG-2.5
        val layer = when ((h1 shr 1) and 0x03) { 3 -> 1; 2 -> 2; 1 -> 3; else -> return null }
        val base = MP3_RATES.getOrElse((h2 shr 2) and 0x03) { return null }
        val rate = when (version) { 3 -> base; 2 -> base / 2; 0 -> base / 4; else -> return null }
        return Track(Codec.MP3).also {
            it.timescale = 1000
            it.sampleRate = rate
            it.channelCount = if ((h3 shr 6) == 3) 1 else 2
            it.mpegAudioLayer = layer
            audio = it
        }
    }

    private fun audioFrame(d: ByteArray, from: Int, dts: Long) {
        val t = audio ?: return
        if (from >= d.size) return
        val offset = sink.writeSampleData(d, from, d.size - from)
        t.samples.add(offset, d.size - from, dts, dts, true, audioEntry)
    }

    // ---- helpers ----

    private fun unsupported(what: String) {
        if (reported.add(what)) {
            Logger.info("XDM", "FLV: $what isn't supported, left out")
            if (what.startsWith("video") || what.startsWith("audio")) lostStreams += "FLV $what"
        }
    }

    private fun u24(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)

    /** Signed 24-bit composition offset. */
    private fun si24(b: ByteArray, o: Int): Long = (u24(b, o).let { if (it and 0x800000 != 0) it - 0x1000000 else it }).toLong()

    private companion object {
        val NO_SINK = object : SampleSink {
            override fun writeSampleData(data: ByteArray, offset: Int, length: Int): Long = 0
        }
        val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val MP3_RATES = intArrayOf(44100, 48000, 32000)
    }
}
