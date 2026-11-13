// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.mkv

import xdm.core.media.muxer.transmux.ContainerWriter
import xdm.core.media.muxer.transmux.mp4.BoxBuf
import xdm.core.media.muxer.transmux.mp4.CodecBoxes
import xdm.core.media.muxer.transmux.mp4.SampleEntries
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.DisplayMatrix
import xdm.core.media.muxer.transmux.sample.Sample
import xdm.core.media.muxer.transmux.sample.Timeline
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.media.muxer.transmux.sample.upperMedian
import xdm.core.util.Logger
import xdm.core.storage.LocalStorage
import xdm.core.storage.RandomAccessOutput
import xdm.core.storage.StorageFile
import xdm.core.storage.asOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Writes a Matroska/WebM (`.mkv`) file from demuxed [Track]s — the counterpart of
 * [xdm.core.media.muxer.transmux.mp4.Mp4Writer] for codecs that MP4 can't (cleanly) carry, such as
 * the VP8/VP9/Opus/Vorbis streams that DASH delivers in WebM segments.
 *
 * Sample bytes are streamed into a scratch spool file as the demuxer produces them (this class is
 * the sample sink); only lightweight [Sample] records stay in RAM. [finish] then lays out the
 * EBML header, Info, Tracks and the Clusters, copying each frame's payload back out of the spool
 * into a `SimpleBlock`. Element sizes for the streamed masters (Segment, Cluster) are written as
 * fixed 8-byte placeholders and back-patched in a single pass at the end, mirroring how
 * [Mp4Writer][xdm.core.media.muxer.transmux.mp4.Mp4Writer] patches its `mdat` size.
 *
 * References: Matroska element spec (https://www.matroska.org/technical/elements.html) and the
 * conventions used by libavformat's `matroskaenc` and the JS `webm-muxer`.
 */
class MkvWriter(
    private val output: StorageFile,
    spoolDir: String? = null,
    /** The EBML DocType: `webm` for a `.webm` output (VP8/VP9/AV1 with Vorbis/Opus only), else `matroska`. */
    private val docType: String = "matroska",
    /** Checked between clusters while [finish] copies the spool out: true drops the output. */
    private val cancelled: () -> Boolean = { false },
) : ContainerWriter {
    constructor(outputPath: String, spoolDir: String? = null) : this(LocalStorage.file(outputPath), spoolDir)

    override var repairTimeline: Boolean = false

    /**
     * Scratch copy of the sample data, read back at random: in [spoolDir] when given, otherwise next to
     * the output, which needs a local output.
     */
    private val spoolFile = File(
        spoolDir ?: output.folder.localPath ?: throw IllegalArgumentException("No spool folder for $output"),
        "${output.name}.spool"
    )
    private val spool = BufferedOutputStream(FileOutputStream(spoolFile), 1 shl 20)
    private var spoolPos = 0L

    private lateinit var out: BufferedOutputStream
    private var position = 0L
    /** (placeholder file position, size value) for reserved 8-byte size fields to patch at close. */
    private val sizePatches = ArrayList<LongArray>()

    /** Byte patches (file position, bytes) applied with [sizePatches] at close: the SeekHead. */
    private val bytePatches = ArrayList<Pair<Long, ByteArray>>()

    /**
     * Cue points as (time ms, track number, cluster position from the segment data) triples: a seek
     * index, written as `Cues` after the clusters. Media3 treats a Matroska file without Cues as
     * unseekable, and VLC then seeks by reading every cluster up to the seek point.
     */
    private var cues = LongArray(3 * 1024)
    private var cueCount = 0

    /** Matroska default: 1 ms tick. Cluster/block timecodes are expressed in these units. */
    private val timecodeScaleNs = 1_000_000L
    private val maxClusterRelMs = 30_000L

    override fun writeSampleData(data: ByteArray, offset: Int, length: Int): Long {
        val at = spoolPos
        spool.write(data, offset, length)
        spoolPos += length
        return at
    }

    override fun finish(tracks: List<Track>): Boolean {
        spool.flush(); spool.close()
        val usable = tracks.filter { it.samples.isNotEmpty() && it.isReady() }
        if (usable.isEmpty()) { spoolFile.delete(); return false }

        val plans = usable.mapIndexed { idx, t ->
            // A track Matroska can't name is not left out: the caller falls back (MP4 can copy it).
            val resolved = resolveCodec(t) ?: run {
                spoolFile.delete()
                throw TrackLostException("Matroska has no codec ID for the ${t.codec} track${t.sampleEntryBox?.let { " (" + String(it, 4, 4, Charsets.US_ASCII) + ")" } ?: ""}")
            }
            TrackPlan(t, idx + 1, resolved.first, resolved.second, dolbyVisionConfig(t))
        }
        if (plans.isEmpty()) { spoolFile.delete(); return false }

        // The shared timeline step (as in the MP4 writer): sections back to back, jumps repaired.
        Timeline.prepare(plans.map { it.track }, repairTimeline)

        // Normalize all tracks to a common zero so no absolute Matroska timecode is negative.
        var globalMinMs = Long.MAX_VALUE
        var endMs = Long.MIN_VALUE
        for (p in plans) {
            val s = p.track.samples
            for (i in 0 until s.size) {
                globalMinMs = minOf(globalMinMs, msOf(s.pts(i), p.track.timescale))
                endMs = maxOf(endMs, msOf(s.pts(i) + s.duration(i).coerceAtLeast(0), p.track.timescale))
            }
        }
        val durationMs = endMs - globalMinMs

        var handle: RandomAccessOutput? = null
        try {
            handle = output.open(create = true)
            handle.setLength(0)
            out =BufferedOutputStream(handle.asOutputStream(), 1 shl 20)
            writeEbmlHeader(docTypeVersion = if (plans.any { it.dolbyVision != null }) 4 else 2)
            // Segment: reserved size, back-patched once all children are written.
            writeRaw(Ebml.idBytes(Ebml.SEGMENT))
            val segPatch = position
            writeRaw(Ebml.reservedSizeBytes(0))
            val segContentStart = position
            this.segContentStart = segContentStart

            // Room for the SeekHead, written at the end once the Cues position is known.
            val seekHeadPos = position
            writeRaw(voidElement(SEEK_HEAD_SPACE))

            val infoPos = position - segContentStart
            writeRaw(buildInfo(durationMs))
            val tracksPos = position - segContentStart
            writeRaw(buildTracks(plans))
            writeClusters(plans, globalMinMs)
            val cuesPos = position - segContentStart
            if (cueCount > 0) writeCues()
            bytePatches.add(seekHeadPos to buildSeekHead(
                listOf(Ebml.INFO to infoPos, Ebml.TRACKS to tracksPos) + (if (cueCount > 0) listOf(Ebml.CUES to cuesPos) else emptyList())
            ))

            sizePatches.add(longArrayOf(segPatch, position - segContentStart))
            out.flush()

            // Patched through the same handle; closing it closes [out] too. Forced before the publish
            // rename: the segments are deleted once the download succeeds, so after a power loss nothing
            // could rebuild an unflushed output.
            handle.use { raf ->
                for (patch in sizePatches) {
                    raf.seek(patch[0] + 1) // skip the 0x01 leading byte of the 8-byte vint
                    val v = patch[1]
                    val b = ByteArray(7) { i -> (v ushr (8 * (6 - i))).toByte() }
                    raf.write(b, 0, b.size)
                }
                for ((at, bytes) in bytePatches) {
                    raf.seek(at)
                    raf.write(bytes, 0, bytes.size)
                }
                raf.force()
            }
            spoolFile.delete()
            return true
        } catch (ex: Throwable) {
            // The handle itself, not [out]: it may have failed before [out] wrapped it, and an open
            // handle would keep Windows from deleting the output below. The output is dropped, so
            // [out]'s buffer needs no flush.
            try { handle?.close() } catch (_: Exception) {}
            output.delete(); spoolFile.delete()
            if (ex is Cancelled) return false
            // Rethrown so the caller can tell a full disk from a muxing problem.
            throw ex
        }
    }

    /** File position where the Segment's data starts: SeekHead and Cues positions count from here. */
    private var segContentStart = 0L

    /** A Void element of exactly [total] bytes (header included; at least 2). */
    private fun voidElement(total: Int): ByteArray {
        val b = ByteArray(total)
        b[0] = Ebml.VOID.toByte()
        b[1] = (0x80 or (total - 2)).toByte() // 1-byte size: total stays under 128
        return b
    }

    /**
     * The SeekHead for [entries] (element ID, position from the segment data), padded with a Void
     * to the [SEEK_HEAD_SPACE] reserved for it. Positions take 8 bytes so the size is fixed.
     */
    private fun buildSeekHead(entries: List<Pair<Long, Long>>): ByteArray {
        val b = EbmlBuf()
        b.master(Ebml.SEEK_HEAD) {
            for ((id, pos) in entries) {
                master(Ebml.SEEK) {
                    bin(Ebml.SEEK_ID, Ebml.idBytes(id))
                    bin(Ebml.SEEK_POSITION, ByteArray(8) { i -> (pos ushr (8 * (7 - i))).toByte() })
                }
            }
        }
        val head = b.toByteArray()
        val rest = SEEK_HEAD_SPACE - head.size
        check(rest >= 2) { "SeekHead of ${head.size} bytes doesn't fit its reserved space" }
        return head + voidElement(rest)
    }

    private fun addCue(timeMs: Long, trackNumber: Int, clusterPos: Long) {
        if (3 * (cueCount + 1) > cues.size) cues = cues.copyOf(cues.size * 2)
        cues[3 * cueCount] = timeMs
        cues[3 * cueCount + 1] = trackNumber.toLong()
        cues[3 * cueCount + 2] = clusterPos
        cueCount++
    }

    /** The Cues element, written in pieces so a long stream's index isn't built twice in memory. */
    private fun writeCues() {
        val points = ArrayList<ByteArray>(cueCount)
        var total = 0L
        for (i in 0 until cueCount) {
            val b = EbmlBuf()
            b.master(Ebml.CUE_POINT) {
                uint(Ebml.CUE_TIME, cues[3 * i])
                master(Ebml.CUE_TRACK_POSITIONS) {
                    uint(Ebml.CUE_TRACK, cues[3 * i + 1])
                    uint(Ebml.CUE_CLUSTER_POSITION, cues[3 * i + 2])
                }
            }
            val p = b.toByteArray()
            points.add(p)
            total += p.size
        }
        writeRaw(Ebml.idBytes(Ebml.CUES))
        writeRaw(Ebml.sizeBytes(total))
        for (p in points) writeRaw(p)
    }

    /** Thrown out of the cluster loop when [cancelled] says stop. */
    private class Cancelled : Exception()

    override fun abort() {
        try { spool.close() } catch (_: Exception) {}
        try { if (this::out.isInitialized) out.close() } catch (_: Exception) {}
        output.delete(); spoolFile.delete()
    }

    // ---- header / info / tracks ----

    /** [docTypeVersion] 4 when BlockAdditionMapping (Matroska v4) is used; readers only need v2. */
    private fun writeEbmlHeader(docTypeVersion: Long) {
        val b = EbmlBuf()
        b.master(Ebml.EBML) {
            uint(Ebml.EBML_VERSION, 1)
            uint(Ebml.EBML_READ_VERSION, 1)
            uint(Ebml.EBML_MAX_ID_LENGTH, 4)
            uint(Ebml.EBML_MAX_SIZE_LENGTH, 8)
            str(Ebml.DOC_TYPE, docType)
            uint(Ebml.DOC_TYPE_VERSION, docTypeVersion)
            uint(Ebml.DOC_TYPE_READ_VERSION, 2)
        }
        writeRaw(b.toByteArray())
    }

    private fun buildInfo(durationMs: Long): ByteArray {
        val b = EbmlBuf()
        b.master(Ebml.INFO) {
            uint(Ebml.TIMECODE_SCALE, timecodeScaleNs)
            if (durationMs > 0) float(Ebml.DURATION, durationMs.toDouble())
            str(Ebml.MUXING_APP, "xdm-transmuxer")
            str(Ebml.WRITING_APP, "xdm-transmuxer")
        }
        return b.toByteArray()
    }

    private fun buildTracks(plans: List<TrackPlan>): ByteArray {
        val b = EbmlBuf()
        b.master(Ebml.TRACKS) {
            for (p in plans) {
                val t = p.track
                master(Ebml.TRACK_ENTRY) {
                    uint(Ebml.TRACK_NUMBER, p.trackNumber.toLong())
                    uint(Ebml.TRACK_UID, p.trackNumber.toLong())
                    uint(Ebml.TRACK_TYPE, if (t.codec.isVideo) Ebml.TRACK_TYPE_VIDEO else Ebml.TRACK_TYPE_AUDIO)
                    uint(Ebml.FLAG_LACING, 0)
                    str(Ebml.LANGUAGE, "und")
                    str(Ebml.CODEC_ID, p.codecId)
                    // The frame duration, as ffmpeg writes it: players use it for the last frame and
                    // for frames without a following one (VLC buffers a second longer without it).
                    frameDurationNs(t).takeIf { it > 0 }?.let { uint(Ebml.DEFAULT_DURATION, it) }
                    p.codecPrivate?.let { if (it.isNotEmpty()) bin(Ebml.CODEC_PRIVATE, it) }
                    // Opus: a Matroska input's CodecDelay goes back as it was (its block times include
                    // it). Other inputs already start at the first presented sample (the MP4 edit list,
                    // the TS timestamps), so no CodecDelay; SeekPreRoll 80 ms as ffmpeg writes it.
                    if (t.codecDelayNs > 0) uint(Ebml.CODEC_DELAY, t.codecDelayNs)
                    val preRoll = if (t.seekPreRollNs > 0) t.seekPreRollNs else if (p.codecId == "A_OPUS") 80_000_000L else 0L
                    if (preRoll > 0) uint(Ebml.SEEK_PRE_ROLL, preRoll)
                    p.dolbyVision?.let { dv ->
                        master(Ebml.BLOCK_ADDITION_MAPPING) {
                            str(Ebml.BLOCK_ADD_ID_NAME, "Dolby Vision configuration ${dv.type}")
                            uint(Ebml.BLOCK_ADD_ID_TYPE, fourccValue(dv.type))
                            bin(Ebml.BLOCK_ADD_ID_EXTRA_DATA, dv.record)
                        }
                    }
                    if (t.codec.isVideo) {
                        master(Ebml.VIDEO) {
                            uint(Ebml.PIXEL_WIDTH, t.width.toLong().coerceAtLeast(1))
                            uint(Ebml.PIXEL_HEIGHT, t.height.toLong().coerceAtLeast(1))
                            // Rotation / flip as ffmpeg writes it: a rectangular projection's pose.
                            t.matrix?.let { DisplayMatrix.toPose(it) }?.let { (yaw, roll) ->
                                master(Ebml.PROJECTION) {
                                    if (yaw != 0.0) float(Ebml.PROJECTION_POSE_YAW, yaw)
                                    if (roll != 0.0) float(Ebml.PROJECTION_POSE_ROLL, roll)
                                }
                            }
                        }
                    } else {
                        master(Ebml.AUDIO) {
                            if (t.sampleRate > 0) float(Ebml.SAMPLING_FREQUENCY, t.sampleRate.toDouble())
                            uint(Ebml.CHANNELS, t.channelCount.toLong().coerceAtLeast(1))
                            if (t.audioBitDepth > 0) uint(Ebml.BIT_DEPTH, t.audioBitDepth.toLong())
                        }
                    }
                }
            }
        }
        return b.toByteArray()
    }

    // ---- clusters ----

    /** One block to write: a sample, or for PCM a run of samples next to each other in the spool. */
    private class Entry(val plan: TrackPlan, val fileOffset: Long, val size: Int, val keyframe: Boolean, val absMs: Long)

    /**
     * Merges all tracks' samples into one stream. Blocks must be stored in *decode* order (a
     * B-frame's reference frames come first) while each block's timestamp is its *presentation*
     * time, so each track keeps its own (decode) order and tracks are interleaved by DTS.
     */
    private fun interleave(plans: List<TrackPlan>, globalMinMs: Long): Iterator<Entry> = iterator {
        // Produced one at a time: a long stream's entries are not all held at once.
        val cursors = IntArray(plans.size)
        while (true) {
            var best = -1
            var bestDtsMs = Long.MAX_VALUE
            for ((i, p) in plans.withIndex()) {
                val samples = p.track.samples
                if (cursors[i] >= samples.size) continue
                val dtsMs = msOf(samples.dts(cursors[i]), p.track.timescale)
                if (dtsMs < bestDtsMs) { best = i; bestDtsMs = dtsMs }
            }
            if (best < 0) return@iterator
            val p = plans[best]
            val samples = p.track.samples
            val first = cursors[best]++
            var size = samples.sampleSize(first)
            if (p.pcm) {
                // MP4 stores PCM one audio frame per sample: join the frames that follow each other in
                // the spool into blocks of about [PCM_BLOCK_MS], as ffmpeg's chunk-sized packets.
                val limit = samples.pts(first) + PCM_BLOCK_MS * p.track.timescale / 1000
                while (cursors[best] < samples.size) {
                    val next = cursors[best]
                    if (samples.fileOffset(next) != samples.fileOffset(first) + size || samples.pts(next) >= limit) break
                    size += samples.sampleSize(next)
                    cursors[best]++
                }
            }
            yield(Entry(p, samples.fileOffset(first), size, samples.isKeyframe(first),
                msOf(samples.pts(first), p.track.timescale) - globalMinMs))
        }
    }

    private fun writeClusters(plans: List<TrackPlan>, globalMinMs: Long) {
        val entries = interleave(plans, globalMinMs)

        val anyVideo = plans.any { it.track.codec.isVideo }
        val spoolIn = RandomAccessFile(spoolFile, "r")
        val copyBuf = ByteArray(64 * 1024)
        try {
            var clusterTimeMs = -1L
            var clusterHasContent = false
            /** The cluster holds a video block: audio ahead of the first keyframe shares its cluster. */
            var clusterHasVideo = false
            var clusterPatchPos = -1L
            var clusterContentStart = -1L
            var clusterPos = 0L

            fun closeCluster() {
                if (clusterPatchPos >= 0) sizePatches.add(longArrayOf(clusterPatchPos, position - clusterContentStart))
            }

            for (e in entries) {
                val isVideoKey = e.plan.track.codec.isVideo && e.keyframe
                val rel = if (clusterTimeMs < 0) 0 else e.absMs - clusterTimeMs
                // A block's time is a signed 16-bit offset from its cluster's: a frame presented
                // much earlier than the cluster start (a timestamp jump back) needs a new cluster.
                val needNewCluster = clusterTimeMs < 0 || rel > maxClusterRelMs || rel < Short.MIN_VALUE ||
                    (anyVideo && isVideoKey && clusterHasVideo) ||
                    (!anyVideo && rel > 2_000)
                if (needNewCluster) {
                    if (cancelled()) throw Cancelled()
                    closeCluster()
                    clusterTimeMs = e.absMs
                    clusterPos = position - segContentStart
                    // Audio-only: every cluster is a seek point (video: its keyframes, below).
                    if (!anyVideo) addCue(clusterTimeMs, e.plan.trackNumber, clusterPos)
                    writeRaw(Ebml.idBytes(Ebml.CLUSTER))
                    clusterPatchPos = position
                    writeRaw(Ebml.reservedSizeBytes(0))
                    clusterContentStart = position
                    // Timecode (absolute cluster time, in TimecodeScale ticks).
                    val tc = Ebml.uintBytes(clusterTimeMs)
                    writeRaw(Ebml.idBytes(Ebml.TIMECODE)); writeRaw(Ebml.sizeBytes(tc.size.toLong())); writeRaw(tc)
                    clusterHasContent = false
                    clusterHasVideo = false
                }
                if (isVideoKey) addCue(e.absMs, e.plan.trackNumber, clusterPos)
                writeSimpleBlock(e, (e.absMs - clusterTimeMs).toInt(), spoolIn, copyBuf)
                clusterHasContent = true
                if (e.plan.track.codec.isVideo) clusterHasVideo = true
            }
            closeCluster()
        } finally {
            spoolIn.close()
        }
    }

    private fun writeSimpleBlock(e: Entry, relMs: Int, spoolIn: RandomAccessFile, copyBuf: ByteArray) {
        val trackNum = Ebml.sizeBytes(e.plan.trackNumber.toLong()) // track number is an EBML vint
        val keyframe = e.keyframe || !e.plan.track.codec.isVideo
        val flags = if (keyframe) 0x80 else 0x00
        val blockHeaderLen = trackNum.size + 2 + 1 // track vint + int16 timecode + flags
        val blockSize = blockHeaderLen + e.size

        writeRaw(Ebml.idBytes(Ebml.SIMPLE_BLOCK))
        writeRaw(Ebml.sizeBytes(blockSize.toLong()))
        writeRaw(trackNum)
        writeRaw(byteArrayOf((relMs ushr 8).toByte(), relMs.toByte()))
        writeRaw(byteArrayOf(flags.toByte()))

        // Stream the frame payload straight from the spool.
        spoolIn.seek(e.fileOffset)
        var remaining = e.size
        while (remaining > 0) {
            val n = spoolIn.read(copyBuf, 0, minOf(remaining, copyBuf.size))
            if (n <= 0) break
            out.write(copyBuf, 0, n)
            position += n
            remaining -= n
        }
    }

    // ---- codec mapping ----

    private class TrackPlan(
        val track: Track, val trackNumber: Int, val codecId: String, val codecPrivate: ByteArray?,
        val dolbyVision: DolbyVisionConfig?
    ) {
        val pcm = codecId.startsWith("A_PCM/")
    }

    /** A DOVIDecoderConfigurationRecord and the box type (dvcC/dvvC/dvwC) it came in. */
    private class DolbyVisionConfig(val type: String, val record: ByteArray)

    /** Resolves the Matroska CodecID + CodecPrivate for [t], or null if it can't be mapped. */
    private fun resolveCodec(t: Track): Pair<String, ByteArray?>? {
        if (t.additionalSampleEntries.isNotEmpty()) {
            // Matroska has one CodecPrivate per track; samples in later configs keep the first one.
            Logger.error("XDM", "MKV: track changes codec config mid-stream; using the first one")
        }
        // 1) Verbatim passthrough from a Matroska/WebM input.
        t.matroskaCodecId?.let { return it to t.decoderConfigRecord }

        // 2) Elementary-stream codecs produced by the TS/ES readers.
        when (t.codec) {
            Codec.H264 -> return "V_MPEG4/ISO/AVC" to stripBoxHeader { CodecBoxes.writeAvcC(it, t) }
            Codec.H265 -> return "V_MPEGH/ISO/HEVC" to stripBoxHeader { CodecBoxes.writeHvcC(it, t) }
            Codec.AV1 -> return "V_AV1" to t.decoderConfigRecord
            Codec.VP8 -> return "V_VP8" to null
            Codec.VP9 -> return "V_VP9" to t.decoderConfigRecord
            Codec.AAC -> return "A_AAC" to t.audioSpecificConfig
            Codec.MP3 -> return "A_MPEG/L${t.mpegAudioLayer}" to null
            Codec.AC3 -> return "A_AC3" to null
            Codec.EAC3 -> return "A_EAC3" to null
            Codec.OPUS -> return "A_OPUS" to t.decoderConfigRecord
            Codec.VORBIS -> return "A_VORBIS" to t.decoderConfigRecord
            Codec.MPEG_VIDEO ->
                return (if (t.objectTypeIndication == CodecBoxes.OTI_MPEG1_VIDEO) "V_MPEG1" else "V_MPEG2") to t.decoderConfigRecord
            Codec.MPEG4_VISUAL -> return "V_MPEG4/ISO/ASP" to t.decoderConfigRecord
            Codec.DTS -> return "A_DTS" to null
            else -> {}
        }

        // 3) MP4 sample entry copied verbatim (OTHER_VIDEO/OTHER_AUDIO): map by its fourcc.
        t.sampleEntryBox?.let { return fromSampleEntry(t, it) }
        return null
    }

    /** Builds a codec box via [body] and returns its payload (box contents, without size+type). */
    private inline fun stripBoxHeader(body: (BoxBuf) -> Unit): ByteArray {
        val b = BoxBuf(); body(b)
        val full = b.toByteArray()
        return if (full.size > 8) full.copyOfRange(8, full.size) else ByteArray(0)
    }

    /** Maps an MP4 stsd sample entry to a Matroska CodecID + CodecPrivate. */
    private fun fromSampleEntry(t: Track, entry: ByteArray): Pair<String, ByteArray?>? {
        if (entry.size < 8) return null
        val fourcc = String(entry, 4, 4, Charsets.US_ASCII)
        val video = t.codec.isVideo || fourcc in VIDEO_FOURCCS
        val audio = if (video) null else SampleEntries.audioInfo(entry)
        // Child config boxes begin after the sample-entry header: visual 78 bytes, audio 28 (v0) and
        // more for QuickTime v1/v2 entries.
        val childStart = if (video) 8 + 78 else audio?.childStart ?: (8 + 28)
        return when (fourcc) {
            // Dolby Vision sample entries carry the base layer's config; the DV record goes to
            // BlockAdditionMapping (see dolbyVisionConfig).
            "avc1", "avc3", "dva1", "dvav" -> "V_MPEG4/ISO/AVC" to childBox(entry, childStart, "avcC")
            "hev1", "hvc1", "dvh1", "dvhe" -> "V_MPEGH/ISO/HEVC" to childBox(entry, childStart, "hvcC")
            // VVC: `vvcC` is a full box; Matroska's CodecPrivate is the record after its version and flags.
            "vvc1", "vvi1" -> "V_MPEGI/ISO/VVC" to childBox(entry, childStart, "vvcC")?.takeIf { it.size > 4 }?.let { it.copyOfRange(4, it.size) }
            // Motion JPEG: every frame is a whole JPEG; no CodecPrivate.
            "jpeg", "mjpa", "mjpb" -> "V_MJPEG" to null
            "vp08" -> "V_VP8" to null
            "vp09" -> "V_VP9" to childBox(entry, childStart, "vpcC")
            "av01", "dav1" -> "V_AV1" to childBox(entry, childStart, "av1C")
            // ProRes: the fourcc tells the profile; Matroska keeps it as CodecPrivate.
            "apch", "apcn", "apcs", "apco", "ap4h", "ap4x" -> "V_PRORES" to entry.copyOfRange(4, 8)
            // MPEG-4 Part 2 / MPEG-2 / MPEG-1 video by the esds object type; the DecoderSpecificInfo
            // (VOL or sequence header) as CodecPrivate.
            "mp4v" -> childBox(entry, childStart, "esds")?.let { esds ->
                val dsi = extractAsc(esds)
                when (esdsObjectType(esds)) {
                    0x20 -> "V_MPEG4/ISO/ASP" to dsi
                    0x60, 0x61, 0x62, 0x63, 0x64, 0x65 -> "V_MPEG2" to dsi
                    0x6A -> "V_MPEG1" to dsi
                    else -> null
                }
            } ?: quickTime(entry)
            "mp4a" -> {
                // QuickTime puts the esds inside a `wave` atom.
                val esds = childBox(entry, childStart, "esds") ?: waveChild(entry, childStart, "esds")
                when (esds?.let { esdsObjectType(it) }) {
                    0x40, 0x66, 0x67, 0x68 -> "A_AAC" to extractAsc(esds)
                    // MPEG-1 / MPEG-2 LSF audio: the object type doesn't say the layer, the frames do.
                    0x69, 0x6B -> "A_MPEG/L${mpegAudioLayer(t)}" to null
                    // Registered MP4 audio object types for Dolby/DTS streams stored as mp4a.
                    0xA5 -> "A_AC3" to null
                    0xA6 -> "A_EAC3" to null
                    0xA9 -> "A_DTS" to null
                    else -> null
                }
            }
            ".mp3" -> "A_MPEG/L3" to null
            "ac-3" -> "A_AC3" to null
            "ec-3" -> "A_EAC3" to null
            // Matroska wants an OpusHead, not the MP4 dOps payload.
            "Opus" -> "A_OPUS" to childBox(entry, childStart, "dOps")?.let { CodecBoxes.opusHeadFromDOps(it) }
            // FLAC: "fLaC" + metadata blocks; dfLa is a full box holding those blocks.
            "fLaC" -> childBox(entry, childStart, "dfLa")?.takeIf { it.size > 4 }?.let {
                "A_FLAC" to ("fLaC".toByteArray(Charsets.US_ASCII) + it.copyOfRange(4, it.size))
            }
            // ALAC magic cookie: the ALACSpecificConfig inside the (full box) alac child.
            "alac" -> childBox(entry, childStart, "alac")?.takeIf { it.size > 4 }?.let {
                "A_ALAC" to it.copyOfRange(4, it.size)
            }
            "dtsc", "dtsh", "dtsl" -> "A_DTS" to null
            "dtse" -> "A_DTS/EXPRESS" to null
            "mlpa" -> "A_TRUEHD" to null
            // PCM: QuickTime sowt (little-endian) / twos (big-endian) / lpcm (v2 flags), ISO
            // ipcm/fpcm (pcmC). Matroska has little-endian floats only, and 8-bit means unsigned.
            "sowt" -> pcm(t, littleEndian = true, float = false, bits = audio?.bits ?: 16)
            "twos" -> pcm(t, littleEndian = false, float = false, bits = audio?.bits ?: 16)
            // QuickTime `raw `: unsigned 8-bit, which is what Matroska's 8-bit A_PCM/INT/LIT means.
            "raw " -> { t.audioBitDepth = 8; "A_PCM/INT/LIT" to null }
            "lpcm" -> audio?.let { a ->
                pcm(t, littleEndian = a.lpcmFlags and 0x2 == 0, float = a.lpcmFlags and 0x1 != 0, bits = a.bits)
            }
            // QuickTime fixed-size PCM: big-endian unless `wave > enda` says little.
            "in24", "in32", "fl32", "fl64" -> pcm(
                t, littleEndian = waveChild(entry, childStart, "enda")?.let { e -> e.any { it.toInt() != 0 } } ?: false,
                float = fourcc.startsWith("fl"), bits = fourcc.substring(2).toInt()
            )
            "ipcm", "fpcm" -> childBox(entry, childStart, "pcmC")?.takeIf { it.size >= 6 }?.let { c ->
                pcm(t, littleEndian = c[4].toInt() and 1 != 0, float = fourcc == "fpcm", bits = c[5].toInt() and 0xFF)
            }
            // No Matroska codec ID exists for e.g. AC-4 (ac-4), MPEG-H (mha1/mhm1) or APAC (apac).
            else -> if (video) quickTime(entry) else null
        }.also { if (it == null) Logger.error("XDM", "MKV: no Matroska mapping for MP4 sample entry '$fourcc'") }
    }

    /**
     * Any other video sample entry: Matroska's V_QUICKTIME, with the whole sample entry (the
     * QuickTime ImageDescription) as CodecPrivate; readers take the fourcc from it.
     */
    private fun quickTime(entry: ByteArray): Pair<String, ByteArray?> = "V_QUICKTIME" to entry

    /** The Matroska PCM codec for these properties, or null (big-endian float, signed 8-bit). */
    private fun pcm(t: Track, littleEndian: Boolean, float: Boolean, bits: Int): Pair<String, ByteArray?>? {
        if (bits <= 0) return null
        t.audioBitDepth = bits
        return when {
            float -> if (littleEndian) "A_PCM/FLOAT/IEEE" to null else null
            bits == 8 -> null // Matroska 8-bit PCM is unsigned; MP4's is signed
            littleEndian -> "A_PCM/INT/LIT" to null
            else -> "A_PCM/INT/BIG" to null
        }
    }

    /** MPEG audio layer (1-3) from the first frame's header in the spool; 3 when it can't be read. */
    private fun mpegAudioLayer(t: Track): Int {
        if (t.samples.isEmpty()) return 3
        val header = ByteArray(4)
        RandomAccessFile(spoolFile, "r").use { raf ->
            raf.seek(t.samples.fileOffset(0))
            if (raf.read(header) < 4) return 3
        }
        if ((header[0].toInt() and 0xFF) != 0xFF || (header[1].toInt() and 0xE0) != 0xE0) return 3
        return when ((header[1].toInt() ushr 1) and 0x03) { 3 -> 1; 2 -> 2; else -> 3 }
    }

    /** The Dolby Vision config record of a video track copied from MP4, if its sample entry has one. */
    private fun dolbyVisionConfig(t: Track): DolbyVisionConfig? {
        val entry = t.sampleEntryBox ?: return null
        if (!t.codec.isVideo || entry.size < 8 + 78) return null
        for (type in DOLBY_VISION_BOXES) {
            childBox(entry, 8 + 78, type)?.let { return DolbyVisionConfig(type, it) }
        }
        return null
    }

    private fun fourccValue(s: String): Long =
        s.toByteArray(Charsets.US_ASCII).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    /** objectTypeIndication of the DecoderConfigDescriptor (tag 0x04) in an esds payload. */
    private fun esdsObjectType(esds: ByteArray): Int? {
        var p = 4 // skip version/flags
        fun readLen(): Int { var v = 0; while (p < esds.size) { val b = esds[p++].toInt() and 0xFF; v = (v shl 7) or (b and 0x7F); if (b and 0x80 == 0) break }; return v }
        while (p < esds.size) {
            val tag = esds[p++].toInt() and 0xFF
            val len = readLen()
            when (tag) {
                0x03 -> p = esDescriptorBody(esds, p) // nested descriptors follow
                0x04 -> return if (p < esds.size) esds[p].toInt() and 0xFF else null
                else -> p += len
            }
        }
        return null
    }

    /** Contents of the box [name] inside the QuickTime `wave` atom of a sample entry, if any. */
    private fun waveChild(entry: ByteArray, childStart: Int, name: String): ByteArray? =
        childBox(entry, childStart, "wave")?.let { childBox(it, 0, name) }

    /** Returns the *contents* (payload without size+type) of the first child box named [name]. */
    private fun childBox(data: ByteArray, start: Int, name: String): ByteArray? {
        var p = start
        while (p + 8 <= data.size) {
            val size = ((data[p].toInt() and 0xFF) shl 24) or ((data[p + 1].toInt() and 0xFF) shl 16) or
                ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
            if (size < 8 || p + size > data.size) break
            val type = String(data, p + 4, 4, Charsets.US_ASCII)
            if (type == name) return data.copyOfRange(p + 8, p + size)
            p += size
        }
        return null
    }

    /**
     * Position of the first nested descriptor of an ES_Descriptor whose fields start at [p]: ES_ID and
     * flags, then dependsOn_ES_ID, the URL and OCR_ES_Id when their flags are set.
     */
    private fun esDescriptorBody(esds: ByteArray, start: Int): Int {
        if (start + 3 > esds.size) return esds.size
        val flags = esds[start + 2].toInt() and 0xFF
        var p = start + 3
        if (flags and 0x80 != 0) p += 2
        if (flags and 0x40 != 0) p += 1 + (if (p < esds.size) esds[p].toInt() and 0xFF else 0)
        if (flags and 0x20 != 0) p += 2
        return p
    }

    /** Extracts the AudioSpecificConfig bytes (descriptor tag 0x05) from an esds payload. */
    private fun extractAsc(esds: ByteArray): ByteArray? {
        var p = 4 // skip version/flags
        fun readLen(): Int { var v = 0; while (p < esds.size) { val b = esds[p++].toInt() and 0xFF; v = (v shl 7) or (b and 0x7F); if (b and 0x80 == 0) break }; return v }
        while (p < esds.size) {
            val tag = esds[p++].toInt() and 0xFF
            val len = readLen()
            when (tag) {
                0x03 -> p = esDescriptorBody(esds, p) // then nested descriptors follow
                0x04 -> { p += 13 } // DecoderConfigDescriptor fixed fields, then DecoderSpecificInfo follows
                0x05 -> return if (p + len <= esds.size) esds.copyOfRange(p, p + len) else null
                else -> p += len
            }
        }
        return null
    }

    // ---- time helpers ----

    /**
     * The track's typical frame duration in ns: the upper median of the steps between its first
     * presentation times in order (sorted, so B-frames and tracks that carry only PTS give one frame); 0 if unknown.
     */
    private fun frameDurationNs(t: Track): Long {
        val s = t.samples
        if (s.size < 2 || t.timescale <= 0) return 0
        val pts = LongArray(minOf(s.size, 2048)) { s.pts(it) }.also { it.sort() }
        val step = upperMedian { sink ->
            for (i in 1 until pts.size) {
                val d = pts[i] - pts[i - 1]
                if (d > 0) sink.accept(d)
            }
        }
        return if (step > 0) step * 1_000_000_000L / t.timescale else 0
    }

    private fun msOf(ticks: Long, timescale: Int): Long {
        val ts = if (timescale > 0) timescale else 1000
        return (ticks * 1000 + ts / 2) / ts
    }

    private fun writeRaw(bytes: ByteArray) { out.write(bytes); position += bytes.size }

    private companion object {
        val VIDEO_FOURCCS = setOf(
            "avc1", "avc3", "hev1", "hvc1", "vp08", "vp09", "av01", "dva1", "dvav", "dvh1", "dvhe", "dav1", "vvc1", "vvi1", "jpeg", "mjpa", "mjpb"
        )
        val DOLBY_VISION_BOXES = listOf("dvcC", "dvvC", "dvwC")

        /** PCM block length: MP4's one-frame samples are joined into blocks of this many ms. */
        const val PCM_BLOCK_MS = 20L

        /** Bytes reserved after the Segment header for the SeekHead (three entries) and its Void padding. */
        const val SEEK_HEAD_SPACE = 96
    }
}

/** A track the writer can't carry: writing on would leave it out. */
class TrackLostException(message: String) : java.io.IOException(message)
