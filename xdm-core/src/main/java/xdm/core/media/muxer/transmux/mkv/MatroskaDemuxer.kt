// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.mkv

import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.DisplayMatrix
import xdm.core.media.muxer.transmux.sample.Sample
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.util.Logger
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

    private class TrackState(val track: Track, var defaultDurationNs: Long = 0) {
        /** Content encodings undone on every frame, in the order to undo them (see [ContentEncoding]). */
        var frameEncodings: List<ContentEncoding> = emptyList()
        /** The track's frames can't be decoded (encrypted, or an unsupported compression): skipped. */
        var dropped = false
    }

    /**
     * One `ContentEncoding` of a track: a compression its frames (scope 1) or CodecPrivate (scope 2)
     * were stored with. Only zlib (algo 0) and header stripping (algo 3) are undone; the output is
     * written without encodings, as ffmpeg does.
     */
    private class ContentEncoding(val order: Long, val scope: Long, val type: Long, val algo: Long, val settings: ByteArray?) {
        val supported get() = type == 0L && (algo == 0L || algo == 3L)

        fun decode(data: ByteArray): ByteArray = when (algo) {
            3L -> settings?.let { it + data } ?: data
            else -> inflate(data)
        }

        private fun inflate(data: ByteArray): ByteArray {
            val inflater = java.util.zip.Inflater()
            try {
                inflater.setInput(data)
                val out = java.io.ByteArrayOutputStream(data.size * 2)
                val buf = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }

    /** Track number -> the track its blocks go to now. */
    private val byNumber = LinkedHashMap<Long, TrackState>()
    /** Every track in first-seen order, including ones a later `Tracks` replaced with another codec. */
    private val ordered = ArrayList<TrackState>()
    private var timecodeScaleNs = 1_000_000L
    private lateinit var reader: EbmlReader

    /** Length of the segment being parsed: a block past it was cut off (a truncated download). */
    private var fileLength = 0L

    val tracks: List<Track> get() = ordered.map { it.track }

    /** [from] is where the Matroska data starts when the file has other bytes in front of it. */
    fun parseSegment(path: String, from: Long = 0) {
        RandomAccessFile(path, "r").use { raf ->
            reader = EbmlReader(raf)
            reader.seek(from)
            fileLength = raf.length()
            walk(fileLength)
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
            // An element running past the end of the file (a cut download) is read as far as it goes.
            val dataEnd = if (unknown) end else minOf(dataStart + size, end)
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
            if (id == Ebml.TRACK_ENTRY && de <= end) parseTrackEntry(ds, de)
            reader.seek(de)
        }
    }

    private fun parseTrackEntry(start: Long, end: Long) {
        var number = -1L
        var type = 0L
        var codecId = ""
        var codecPrivate: ByteArray? = null
        var width = 0; var height = 0
        var matrix: IntArray? = null
        var sampleRate = 0.0; var channels = 0; var bitDepth = 0
        var defaultDuration = 0L
        var codecDelay = 0L; var seekPreRoll = 0L
        var encodings: List<ContentEncoding> = emptyList()

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
                Ebml.CODEC_DELAY -> codecDelay = reader.readUInt(size.toInt())
                Ebml.SEEK_PRE_ROLL -> seekPreRoll = reader.readUInt(size.toInt())
                Ebml.VIDEO -> parseVideo(ds, de) { w, h, m -> width = w; height = h; matrix = m }
                Ebml.AUDIO -> parseAudio(ds, de) { sr, ch, bd -> sampleRate = sr; channels = ch; bitDepth = bd }
                Ebml.CONTENT_ENCODINGS -> encodings = parseContentEncodings(ds, de)
            }
            reader.seek(de)
        }
        if (number < 0 || codecId.isEmpty()) return

        // Undone last-applied first: the highest ContentEncodingOrder was applied last.
        val undo = encodings.sortedByDescending { it.order }
        if (undo.any { !it.supported }) {
            Logger.info("XDM", "Matroska: track $number ($codecId) is encrypted or uses an unsupported compression, dropped")
            byNumber[number] = TrackState(Track(codecFor(codecId, type == Ebml.TRACK_TYPE_VIDEO))).also { it.dropped = true }
            return
        }
        if (codecPrivate != null) {
            for (e in undo) if (e.scope and 2L != 0L) codecPrivate = e.decode(codecPrivate!!)
        }
        val frameEncodings = undo.filter { it.scope and 1L != 0L }

        // `Tracks` again (a repeated init segment, or a new EBML header + Segment in the same stream):
        // the same track number and codec continue the existing track, so its earlier frames are kept.
        val existing = byNumber[number]
        if (existing != null && !existing.dropped && existing.track.matroskaCodecId == codecId) {
            existing.defaultDurationNs = defaultDuration
            existing.frameEncodings = frameEncodings
            val old = existing.track.decoderConfigRecord
            if (!(old == null && codecPrivate == null) && !(old != null && codecPrivate != null && old.contentEquals(codecPrivate))) {
                // Matroska has one CodecPrivate per track: the first one is kept.
                Logger.info("XDM", "Matroska: track $number repeated with a different CodecPrivate, keeping the first")
            }
            return
        }
        if (existing != null && !existing.dropped) {
            Logger.info("XDM", "Matroska: track $number changed codec ${existing.track.matroskaCodecId} -> $codecId, starting a new track")
        }

        val isVideo = type == Ebml.TRACK_TYPE_VIDEO
        val track = Track(codecFor(codecId, isVideo))
        if (codecId.startsWith("A_MPEG/L")) track.mpegAudioLayer = codecId.last() - '0'
        track.matroskaCodecId = codecId
        track.decoderConfigRecord = codecPrivate
        track.timescale = 1000 // milliseconds; block timecodes are converted to ms
        track.width = width
        track.height = height
        if (isVideo) track.matrix = matrix
        track.ptsOnly = true
        track.codecDelayNs = codecDelay
        track.seekPreRollNs = seekPreRoll
        track.sampleRate = sampleRate.toInt()
        track.channelCount = channels
        track.audioBitDepth = bitDepth
        val state = TrackState(track, defaultDuration)
        state.frameEncodings = frameEncodings
        byNumber[number] = state
        ordered.add(state)
    }

    private fun parseContentEncodings(start: Long, end: Long): List<ContentEncoding> {
        val out = ArrayList<ContentEncoding>()
        reader.seek(start)
        while (reader.position() < end) {
            val id = reader.readId(); if (id < 0) break
            val size = reader.readSize(); val ds = reader.position(); val de = ds + size
            if (id == Ebml.CONTENT_ENCODING) {
                // Defaults from the spec: order 0, scope 1 (frames), type 0 (compression), algo 0 (zlib).
                var order = 0L; var scope = 1L; var type = 0L; var algo = 0L; var settings: ByteArray? = null
                while (reader.position() < de) {
                    val cid = reader.readId(); if (cid < 0) break
                    val csize = reader.readSize(); val cds = reader.position(); val cde = cds + csize
                    when (cid) {
                        Ebml.CONTENT_ENCODING_ORDER -> order = reader.readUInt(csize.toInt())
                        Ebml.CONTENT_ENCODING_SCOPE -> scope = reader.readUInt(csize.toInt())
                        Ebml.CONTENT_ENCODING_TYPE -> type = reader.readUInt(csize.toInt())
                        Ebml.CONTENT_COMPRESSION -> while (reader.position() < cde) {
                            val pid = reader.readId(); if (pid < 0) break
                            val psize = reader.readSize(); val pde = reader.position() + psize
                            when (pid) {
                                Ebml.CONTENT_COMP_ALGO -> algo = reader.readUInt(psize.toInt())
                                Ebml.CONTENT_COMP_SETTINGS -> settings = reader.readBytes(psize.toInt())
                            }
                            reader.seek(pde)
                        }
                    }
                    reader.seek(cde)
                }
                out.add(ContentEncoding(order, scope, type, algo, settings))
            }
            reader.seek(de)
        }
        return out
    }

    private inline fun parseVideo(start: Long, end: Long, out: (Int, Int, IntArray?) -> Unit) {
        reader.seek(start)
        var w = 0; var h = 0
        var matrix: IntArray? = null
        while (reader.position() < end) {
            val id = reader.readId(); if (id < 0) break
            val size = reader.readSize(); val ds = reader.position(); val de = ds + size
            when (id) {
                Ebml.PIXEL_WIDTH -> w = reader.readUInt(size.toInt()).toInt()
                Ebml.PIXEL_HEIGHT -> h = reader.readUInt(size.toInt()).toInt()
                Ebml.PROJECTION -> matrix = parseProjection(ds, de)
            }
            reader.seek(de)
        }
        out(w, h, matrix)
    }

    /**
     * The display matrix of a rectangular projection's pose (rotation, and yaw 180 for a mirrored
     * picture), as ffmpeg writes it; null for none, or for a 360° projection (not carried).
     */
    private fun parseProjection(start: Long, end: Long): IntArray? {
        reader.seek(start)
        var type = 0L
        var yaw = 0.0; var pitch = 0.0; var roll = 0.0
        while (reader.position() < end) {
            val id = reader.readId(); if (id < 0) break
            val size = reader.readSize(); val de = reader.position() + size
            when (id) {
                Ebml.PROJECTION_TYPE -> type = reader.readUInt(size.toInt())
                Ebml.PROJECTION_POSE_YAW -> yaw = reader.readFloat(size.toInt())
                Ebml.PROJECTION_POSE_PITCH -> pitch = reader.readFloat(size.toInt())
                Ebml.PROJECTION_POSE_ROLL -> roll = reader.readFloat(size.toInt())
            }
            reader.seek(de)
        }
        if (type != 0L) return null
        return DisplayMatrix.fromPose(yaw, pitch, roll).also {
            if (it == null && (yaw != 0.0 || pitch != 0.0 || roll != 0.0)) {
                Logger.info("XDM", "Matroska: projection pose yaw=$yaw pitch=$pitch roll=$roll is not a plain rotation, not carried")
            }
        }
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
            // A block cut off by the end of the file: the segment ends before it.
            if ((id == Ebml.SIMPLE_BLOCK || id == Ebml.BLOCK_GROUP) && (size < 0 || de > fileLength)) {
                Logger.info("XDM", "Matroska: segment cut short inside a block, the rest dropped")
                reader.seek(fileLength)
                return
            }
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
        val state = byNumber[trackNumber]?.takeIf { !it.dropped } ?: run { reader.seek(end); return }

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
            // A short read (lace sizes past the block): drop this and the following frames.
            val outOffset = if (state.frameEncodings.isEmpty()) copyFrame(offset, sz, scratch)
            else decodeFrame(offset, sz, state.frameEncodings)
            if (outOffset < 0) {
                Logger.info("XDM", "Matroska: frame data cut short, dropped")
                break
            }
            val pts = basePtsMs + i * frameDurMs
            state.track.samples.add(outOffset, if (state.frameEncodings.isEmpty()) sz else decodedSize, pts, pts, keyframe)
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

    /** Size of the frame [decodeFrame] wrote last. */
    private var decodedSize = 0

    /** Reads a frame stored with [encodings], undoes them and writes the result; -1 on a short read. */
    private fun decodeFrame(offset: Long, size: Int, encodings: List<ContentEncoding>): Long {
        if (offset + size > fileLength) return -1
        reader.seek(offset)
        var data = reader.readBytes(size)
        for (e in encodings) data = e.decode(data)
        decodedSize = data.size
        return sink.writeSampleData(data, 0, data.size)
    }

    /** Copies a frame into the sink; -1 (nothing written) when the file has fewer than [size] bytes there. */
    private fun copyFrame(offset: Long, size: Int, scratch: ByteArray): Long {
        if (offset + size > fileLength) return -1
        reader.seek(offset)
        val data = if (size <= scratch.size) scratch else ByteArray(size)
        var read = 0
        while (read < size) {
            val n = reader.readBytesInto(data, read, size - read)
            if (n <= 0) return -1
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
        "A_MPEG/L1", "A_MPEG/L2", "A_MPEG/L3" -> Codec.MP3
        else -> if (isVideo) Codec.OTHER_VIDEO else Codec.OTHER_AUDIO
    }
}
