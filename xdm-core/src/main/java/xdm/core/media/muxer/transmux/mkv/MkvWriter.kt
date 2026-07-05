package xdm.core.media.muxer.transmux.mkv

import xdm.core.media.muxer.transmux.ContainerWriter
import xdm.core.media.muxer.transmux.mp4.BoxBuf
import xdm.core.media.muxer.transmux.mp4.CodecBoxes
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Sample
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.util.Logger
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
class MkvWriter(outputPath: String) : ContainerWriter {
    private val file = File(outputPath)
    private val spoolFile = File("$outputPath.spool")
    private val spool = BufferedOutputStream(FileOutputStream(spoolFile), 1 shl 20)
    private var spoolPos = 0L

    private lateinit var out: BufferedOutputStream
    private var position = 0L
    /** (placeholder file position, size value) for reserved 8-byte size fields to patch at close. */
    private val sizePatches = ArrayList<LongArray>()

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

        val plans = usable.mapIndexedNotNull { idx, t ->
            val resolved = resolveCodec(t) ?: run {
                Logger.error("XDM", "MKV: skipping track with unmappable codec ${t.codec}")
                null
            }
            resolved?.let { TrackPlan(t, idx + 1, it.first, it.second) }
        }
        if (plans.isEmpty()) { spoolFile.delete(); return false }

        // Normalize all tracks to a common zero so no absolute Matroska timecode is negative.
        val globalMinMs = plans.minOf { p -> p.track.samples.minOf { msOf(it.pts, p.track.timescale) } }
        val durationMs = plans.maxOf { p -> p.track.samples.maxOf { endMsOf(it, p.track) } } - globalMinMs

        try {
            out = BufferedOutputStream(FileOutputStream(file), 1 shl 20)
            writeEbmlHeader()
            // Segment: reserved size, back-patched once all children are written.
            writeRaw(Ebml.idBytes(Ebml.SEGMENT))
            val segPatch = position
            writeRaw(Ebml.reservedSizeBytes(0))
            val segContentStart = position

            writeRaw(buildInfo(durationMs))
            writeRaw(buildTracks(plans))
            writeClusters(plans, globalMinMs)

            sizePatches.add(longArrayOf(segPatch, position - segContentStart))
            out.flush(); out.close()

            RandomAccessFile(file, "rw").use { raf ->
                for (patch in sizePatches) {
                    raf.seek(patch[0] + 1) // skip the 0x01 leading byte of the 8-byte vint
                    val v = patch[1]
                    val b = ByteArray(7) { i -> (v ushr (8 * (6 - i))).toByte() }
                    raf.write(b)
                }
            }
            spoolFile.delete()
            return true
        } catch (ex: Throwable) {
            Logger.error("XDM", "MKV write failed", ex)
            try { out.close() } catch (_: Exception) {}
            file.delete(); spoolFile.delete()
            return false
        }
    }

    override fun abort() {
        try { spool.close() } catch (_: Exception) {}
        try { if (this::out.isInitialized) out.close() } catch (_: Exception) {}
        file.delete(); spoolFile.delete()
    }

    // ---- header / info / tracks ----

    private fun writeEbmlHeader() {
        val b = EbmlBuf()
        b.master(Ebml.EBML) {
            uint(Ebml.EBML_VERSION, 1)
            uint(Ebml.EBML_READ_VERSION, 1)
            uint(Ebml.EBML_MAX_ID_LENGTH, 4)
            uint(Ebml.EBML_MAX_SIZE_LENGTH, 8)
            str(Ebml.DOC_TYPE, "matroska")
            uint(Ebml.DOC_TYPE_VERSION, 2)
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
                    p.codecPrivate?.let { if (it.isNotEmpty()) bin(Ebml.CODEC_PRIVATE, it) }
                    if (t.codec.isVideo) {
                        master(Ebml.VIDEO) {
                            uint(Ebml.PIXEL_WIDTH, t.width.toLong().coerceAtLeast(1))
                            uint(Ebml.PIXEL_HEIGHT, t.height.toLong().coerceAtLeast(1))
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

    private class Entry(val plan: TrackPlan, val sample: Sample, val absMs: Long)

    private fun writeClusters(plans: List<TrackPlan>, globalMinMs: Long) {
        // Merge all tracks' samples into one timeline, ordered by presentation time.
        val entries = ArrayList<Entry>()
        for (p in plans) for (s in p.track.samples) {
            entries.add(Entry(p, s, msOf(s.pts, p.track.timescale) - globalMinMs))
        }
        entries.sortBy { it.absMs }

        val anyVideo = plans.any { it.track.codec.isVideo }
        val spoolIn = RandomAccessFile(spoolFile, "r")
        val copyBuf = ByteArray(64 * 1024)
        try {
            var clusterTimeMs = -1L
            var clusterHasContent = false
            var clusterPatchPos = -1L
            var clusterContentStart = -1L

            fun closeCluster() {
                if (clusterPatchPos >= 0) sizePatches.add(longArrayOf(clusterPatchPos, position - clusterContentStart))
            }

            for (e in entries) {
                val isVideoKey = e.plan.track.codec.isVideo && e.sample.isKeyframe
                val rel = if (clusterTimeMs < 0) 0 else e.absMs - clusterTimeMs
                val needNewCluster = clusterTimeMs < 0 || rel > maxClusterRelMs ||
                    (anyVideo && isVideoKey && clusterHasContent) ||
                    (!anyVideo && rel > 2_000)
                if (needNewCluster) {
                    closeCluster()
                    clusterTimeMs = e.absMs
                    writeRaw(Ebml.idBytes(Ebml.CLUSTER))
                    clusterPatchPos = position
                    writeRaw(Ebml.reservedSizeBytes(0))
                    clusterContentStart = position
                    // Timecode (absolute cluster time, in TimecodeScale ticks).
                    val tc = Ebml.uintBytes(clusterTimeMs)
                    writeRaw(Ebml.idBytes(Ebml.TIMECODE)); writeRaw(Ebml.sizeBytes(tc.size.toLong())); writeRaw(tc)
                    clusterHasContent = false
                }
                writeSimpleBlock(e, (e.absMs - clusterTimeMs).toInt(), spoolIn, copyBuf)
                clusterHasContent = true
            }
            closeCluster()
        } finally {
            spoolIn.close()
        }
    }

    private fun writeSimpleBlock(e: Entry, relMs: Int, spoolIn: RandomAccessFile, copyBuf: ByteArray) {
        val trackNum = Ebml.sizeBytes(e.plan.trackNumber.toLong()) // track number is an EBML vint
        val keyframe = e.sample.isKeyframe || !e.plan.track.codec.isVideo
        val flags = if (keyframe) 0x80 else 0x00
        val blockHeaderLen = trackNum.size + 2 + 1 // track vint + int16 timecode + flags
        val blockSize = blockHeaderLen + e.sample.size

        writeRaw(Ebml.idBytes(Ebml.SIMPLE_BLOCK))
        writeRaw(Ebml.sizeBytes(blockSize.toLong()))
        writeRaw(trackNum)
        writeRaw(byteArrayOf((relMs ushr 8).toByte(), relMs.toByte()))
        writeRaw(byteArrayOf(flags.toByte()))

        // Stream the frame payload straight from the spool.
        spoolIn.seek(e.sample.fileOffset)
        var remaining = e.sample.size
        while (remaining > 0) {
            val n = spoolIn.read(copyBuf, 0, minOf(remaining, copyBuf.size))
            if (n <= 0) break
            out.write(copyBuf, 0, n)
            position += n
            remaining -= n
        }
    }

    // ---- codec mapping ----

    private class TrackPlan(val track: Track, val trackNumber: Int, val codecId: String, val codecPrivate: ByteArray?)

    /** Resolves the Matroska CodecID + CodecPrivate for [t], or null if it can't be mapped. */
    private fun resolveCodec(t: Track): Pair<String, ByteArray?>? {
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
            Codec.MP3 -> return "A_MPEG/L3" to null
            Codec.AC3 -> return "A_AC3" to null
            Codec.EAC3 -> return "A_EAC3" to null
            Codec.OPUS -> return "A_OPUS" to t.decoderConfigRecord
            Codec.VORBIS -> return "A_VORBIS" to t.decoderConfigRecord
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
        // Child config boxes begin after the sample-entry header (visual 78, audio 28 bytes).
        val childStart = if (t.codec.isVideo || fourcc in VIDEO_FOURCCS) 8 + 78 else 8 + 28
        return when (fourcc) {
            "avc1", "avc3" -> "V_MPEG4/ISO/AVC" to childBox(entry, childStart, "avcC")
            "hev1", "hvc1" -> "V_MPEGH/ISO/HEVC" to childBox(entry, childStart, "hvcC")
            "vp08" -> "V_VP8" to null
            "vp09" -> "V_VP9" to childBox(entry, childStart, "vpcC")
            "av01" -> "V_AV1" to childBox(entry, childStart, "av1C")
            "mp4a" -> "A_AAC" to (childBox(entry, childStart, "esds")?.let { extractAsc(it) })
            "ac-3" -> "A_AC3" to null
            "ec-3" -> "A_EAC3" to null
            "Opus" -> "A_OPUS" to childBox(entry, childStart, "dOps")
            else -> null
        }
    }

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

    /** Extracts the AudioSpecificConfig bytes (descriptor tag 0x05) from an esds payload. */
    private fun extractAsc(esds: ByteArray): ByteArray? {
        var p = 4 // skip version/flags
        fun readLen(): Int { var v = 0; while (p < esds.size) { val b = esds[p++].toInt() and 0xFF; v = (v shl 7) or (b and 0x7F); if (b and 0x80 == 0) break }; return v }
        while (p < esds.size) {
            val tag = esds[p++].toInt() and 0xFF
            val len = readLen()
            when (tag) {
                0x03 -> { p += 3 } // ES_Descriptor header (ES_ID + flags), then nested descriptors follow
                0x04 -> { p += 13 } // DecoderConfigDescriptor fixed fields, then DecoderSpecificInfo follows
                0x05 -> return if (p + len <= esds.size) esds.copyOfRange(p, p + len) else null
                else -> p += len
            }
        }
        return null
    }

    // ---- time helpers ----

    private fun msOf(ticks: Long, timescale: Int): Long {
        val ts = if (timescale > 0) timescale else 1000
        return (ticks * 1000 + ts / 2) / ts
    }

    private fun endMsOf(s: Sample, t: Track): Long = msOf(s.pts + s.durationTicks.coerceAtLeast(0), t.timescale)

    private fun writeRaw(bytes: ByteArray) { out.write(bytes); position += bytes.size }

    private companion object {
        val VIDEO_FOURCCS = setOf("avc1", "avc3", "hev1", "hvc1", "vp08", "vp09", "av01")
    }
}
