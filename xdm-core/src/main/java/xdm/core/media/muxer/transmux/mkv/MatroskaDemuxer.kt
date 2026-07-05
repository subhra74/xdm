package xdm.core.media.muxer.transmux.mkv

import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Sample
import xdm.core.media.muxer.transmux.sample.Track
import java.io.RandomAccessFile

/**
 * Matroska / WebM demuxer for the transmux path. Reads the EBML structure of WebM segments (as
 * delivered by DASH: an init segment carrying `Info`+`Tracks`, then media segments carrying bare
 * `Cluster`s) and turns each `SimpleBlock`/`BlockGroup` frame into a [Sample].
 *
 * Codec configuration is carried verbatim: the Matroska `CodecID` and `CodecPrivate` are stashed on
 * the [Track] ([Track.matroskaCodecId] / [Track.decoderConfigRecord]) so the writer can pass them
 * straight through (VP8/VP9/AV1/Opus/Vorbis need no reconstruction). Frame payloads stream through
 * [sink] into the output spool. Track state persists across [parseSegment] calls, since DASH's init
 * and media segments arrive as separate files.
 */
class MatroskaDemuxer(private val sink: SampleSink) {

    private class TrackState(val track: Track, var defaultDurationNs: Long = 0)

    private val byNumber = LinkedHashMap<Long, TrackState>()
    private var timecodeScaleNs = 1_000_000L
    private lateinit var reader: EbmlReader

    val tracks: List<Track> get() = byNumber.values.map { it.track }

    fun parseSegment(path: String) {
        RandomAccessFile(path, "r").use { raf ->
            reader = EbmlReader(raf)
            walk(raf.length())
        }
    }

    fun finish() {}

    // ---- level-1 walk (top level and inside Segment) ----

    private fun walk(end: Long) {
        while (reader.position() < end) {
            val id = reader.readId(); if (id < 0) break
            val size = reader.readSize()
            val dataStart = reader.position()
            val unknown = size < 0
            val dataEnd = if (unknown) end else dataStart + size
            when (id) {
                // Entering a Segment: its children are the next level-1 elements — just fall through.
                Ebml.SEGMENT -> { /* descend by continuing the loop */ }
                Ebml.INFO -> { parseInfo(dataStart, dataEnd); reader.seek(dataEnd) }
                Ebml.TRACKS -> { parseTracks(dataStart, dataEnd); reader.seek(dataEnd) }
                Ebml.CLUSTER -> parseCluster(dataStart, dataEnd, unknown, end)
                else -> { if (unknown) return else reader.seek(dataEnd) }
            }
        }
    }

    // ---- Info ----

    private fun parseInfo(start: Long, end: Long) {
        reader.seek(start)
        while (reader.position() < end) {
            val id = reader.readId(); if (id < 0) break
            val size = reader.readSize(); val ds = reader.position(); val de = ds + size
            if (id == Ebml.TIMECODE_SCALE) timecodeScaleNs = reader.readUInt(size.toInt())
            reader.seek(de)
        }
    }

    // ---- Tracks / TrackEntry ----

    private fun parseTracks(start: Long, end: Long) {
        reader.seek(start)
        while (reader.position() < end) {
            val id = reader.readId(); if (id < 0) break
            val size = reader.readSize(); val ds = reader.position(); val de = ds + size
            if (id == Ebml.TRACK_ENTRY) parseTrackEntry(ds, de)
            reader.seek(de)
        }
    }

    private fun parseTrackEntry(start: Long, end: Long) {
        var number = -1L
        var type = 0L
        var codecId = ""
        var codecPrivate: ByteArray? = null
        var width = 0; var height = 0
        var sampleRate = 0.0; var channels = 0; var bitDepth = 0
        var defaultDuration = 0L

        reader.seek(start)
        while (reader.position() < end) {
            val id = reader.readId(); if (id < 0) break
            val size = reader.readSize(); val ds = reader.position(); val de = ds + size
            when (id) {
                Ebml.TRACK_NUMBER -> number = reader.readUInt(size.toInt())
                Ebml.TRACK_TYPE -> type = reader.readUInt(size.toInt())
                Ebml.CODEC_ID -> codecId = reader.readString(size.toInt())
                Ebml.CODEC_PRIVATE -> codecPrivate = reader.readBytes(size.toInt())
                Ebml.DEFAULT_DURATION -> defaultDuration = reader.readUInt(size.toInt())
                Ebml.VIDEO -> parseVideo(ds, de) { w, h -> width = w; height = h }
                Ebml.AUDIO -> parseAudio(ds, de) { sr, ch, bd -> sampleRate = sr; channels = ch; bitDepth = bd }
            }
            reader.seek(de)
        }
        if (number < 0 || codecId.isEmpty()) return

        val isVideo = type == Ebml.TRACK_TYPE_VIDEO
        val track = Track(codecFor(codecId, isVideo))
        track.matroskaCodecId = codecId
        track.decoderConfigRecord = codecPrivate
        track.timescale = 1000 // milliseconds; block timecodes are converted to ms
        track.width = width
        track.height = height
        track.sampleRate = sampleRate.toInt()
        track.channelCount = channels
        track.audioBitDepth = bitDepth
        byNumber[number] = TrackState(track, defaultDuration)
    }

    private inline fun parseVideo(start: Long, end: Long, out: (Int, Int) -> Unit) {
        reader.seek(start)
        var w = 0; var h = 0
        while (reader.position() < end) {
            val id = reader.readId(); if (id < 0) break
            val size = reader.readSize(); val de = reader.position() + size
            when (id) {
                Ebml.PIXEL_WIDTH -> w = reader.readUInt(size.toInt()).toInt()
                Ebml.PIXEL_HEIGHT -> h = reader.readUInt(size.toInt()).toInt()
            }
            reader.seek(de)
        }
        out(w, h)
    }

    private inline fun parseAudio(start: Long, end: Long, out: (Double, Int, Int) -> Unit) {
        reader.seek(start)
        var sr = 0.0; var ch = 0; var bd = 0
        while (reader.position() < end) {
            val id = reader.readId(); if (id < 0) break
            val size = reader.readSize(); val de = reader.position() + size
            when (id) {
                Ebml.SAMPLING_FREQUENCY -> sr = reader.readFloat(size.toInt())
                Ebml.CHANNELS -> ch = reader.readUInt(size.toInt()).toInt()
                Ebml.BIT_DEPTH -> bd = reader.readUInt(size.toInt()).toInt()
            }
            reader.seek(de)
        }
        out(sr, ch, bd)
    }

    // ---- Cluster / blocks ----

    private fun parseCluster(start: Long, end: Long, unknown: Boolean, hardEnd: Long) {
        reader.seek(start)
        var clusterTc = 0L
        val limit = if (unknown) hardEnd else end
        while (reader.position() < limit) {
            val elemStart = reader.position()
            val id = reader.readId(); if (id < 0) break
            val size = reader.readSize(); val ds = reader.position(); val de = ds + size
            when (id) {
                Ebml.TIMECODE -> { clusterTc = reader.readUInt(size.toInt()); reader.seek(de) }
                Ebml.SIMPLE_BLOCK -> { parseBlock(ds, de, clusterTc, null); reader.seek(de) }
                Ebml.BLOCK_GROUP -> { parseBlockGroup(ds, de, clusterTc); reader.seek(de) }
                // Any other level-1 element ends an unknown-size cluster: rewind for the outer walk.
                Ebml.CLUSTER, Ebml.CUES, Ebml.SEEK_HEAD, Ebml.TAGS, Ebml.INFO, Ebml.TRACKS, Ebml.SEGMENT, Ebml.EBML -> {
                    reader.seek(elemStart); return
                }
                else -> reader.seek(de)
            }
        }
    }

    private fun parseBlockGroup(start: Long, end: Long, clusterTc: Long) {
        var blockStart = -1L; var blockEnd = -1L
        var hasReference = false
        reader.seek(start)
        while (reader.position() < end) {
            val id = reader.readId(); if (id < 0) break
            val size = reader.readSize(); val ds = reader.position(); val de = ds + size
            when (id) {
                Ebml.BLOCK -> { blockStart = ds; blockEnd = de }
                Ebml.REFERENCE_BLOCK -> hasReference = true
            }
            reader.seek(de)
        }
        if (blockStart >= 0) parseBlock(blockStart, blockEnd, clusterTc, keyframeOverride = !hasReference)
    }

    /** Parses a (Simple)Block: header + one or more (optionally laced) frames. */
    private fun parseBlock(start: Long, end: Long, clusterTc: Long, keyframeOverride: Boolean?) {
        reader.seek(start)
        val trackNumber = reader.readSize()
        val rel = reader.readS16()
        val flags = reader.readUInt(1).toInt()
        val state = byNumber[trackNumber] ?: run { reader.seek(end); return }

        val keyframe = keyframeOverride ?: ((flags and 0x80) != 0)
        val lacing = (flags ushr 1) and 0x03
        val absTicks = clusterTc + rel
        val basePtsMs = absTicks * timecodeScaleNs / 1_000_000L
        val frameDurMs = state.defaultDurationNs / 1_000_000L

        val payloadStart = reader.position()
        val frameSizes = if (lacing == 0) {
            longArrayOf(end - payloadStart)
        } else {
            readLaceSizes(lacing, payloadStart, end)
        }

        var offset = if (lacing == 0) payloadStart else reader.position()
        val scratch = ByteArray(64 * 1024)
        for ((i, fsize) in frameSizes.withIndex()) {
            val sz = fsize.toInt()
            if (sz <= 0) continue
            val outOffset = copyFrame(offset, sz, scratch)
            val pts = basePtsMs + i * frameDurMs
            state.track.samples.add(Sample(outOffset, sz, pts, pts, keyframe))
            offset += sz
        }
    }

    /** Reads lace sizes; leaves the reader positioned at the first frame's bytes. */
    private fun readLaceSizes(lacing: Int, payloadStart: Long, end: Long): LongArray {
        reader.seek(payloadStart)
        val frameCount = reader.readUInt(1).toInt() + 1
        val sizes = LongArray(frameCount)
        when (lacing) {
            0x01 -> { // fixed
                val each = (end - reader.position()) / frameCount
                for (i in 0 until frameCount) sizes[i] = each
            }
            0x02 -> { // Xiph
                var sum = 0L
                for (i in 0 until frameCount - 1) {
                    var s = 0L
                    var b: Int
                    do { b = reader.readUInt(1).toInt(); s += b } while (b == 255)
                    sizes[i] = s; sum += s
                }
                sizes[frameCount - 1] = (end - reader.position()) - sum
            }
            0x03 -> { // EBML
                var sum = 0L
                val first = reader.readSize(); sizes[0] = first; sum += first
                var prev = first
                for (i in 1 until frameCount - 1) {
                    val vl = reader.readVintWithLen() ?: break
                    val bias = (1L shl (7 * vl[1].toInt() - 1)) - 1
                    prev += vl[0] - bias
                    sizes[i] = prev; sum += prev
                }
                if (frameCount >= 2) sizes[frameCount - 1] = (end - reader.position()) - sum
            }
        }
        return sizes
    }

    private fun copyFrame(offset: Long, size: Int, scratch: ByteArray): Long {
        reader.seek(offset)
        val data = if (size <= scratch.size) scratch else ByteArray(size)
        var read = 0
        while (read < size) {
            val n = reader.readBytesInto(data, read, size - read)
            if (n <= 0) break
            read += n
        }
        return sink.writeSampleData(data, 0, size)
    }

    private fun codecFor(codecId: String, isVideo: Boolean): Codec = when (codecId) {
        "V_VP8" -> Codec.VP8
        "V_VP9" -> Codec.VP9
        "V_AV1" -> Codec.AV1
        "V_MPEG4/ISO/AVC" -> Codec.H264
        "V_MPEGH/ISO/HEVC" -> Codec.H265
        "A_OPUS" -> Codec.OPUS
        "A_VORBIS" -> Codec.VORBIS
        "A_AAC" -> Codec.AAC
        "A_AC3" -> Codec.AC3
        "A_EAC3" -> Codec.EAC3
        "A_MPEG/L3" -> Codec.MP3
        else -> if (isVideo) Codec.OTHER_VIDEO else Codec.OTHER_AUDIO
    }
}
