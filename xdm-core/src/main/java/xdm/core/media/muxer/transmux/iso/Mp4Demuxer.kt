package xdm.core.media.muxer.transmux.iso

import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.io.ParsableByteArray
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Sample
import xdm.core.media.muxer.transmux.sample.Track
import java.io.RandomAccessFile

/**
 * ISO-BMFF (MP4) demuxer for the non-TS transmux path. Handles both:
 *  - fragmented MP4 / CMAF: an init segment (`moov` with `mvex` defaults, no samples) followed by
 *    media segments (`moof`/`trun` + `mdat`), as used by HLS-fMP4 and DASH; and
 *  - progressive MP4: a single file whose `moov` carries the full `stbl` sample tables + `mdat`.
 *
 * Sample media and the codec config (the whole stsd sample entry) are already in MP4 form, so they
 * are copied verbatim — no codec parsing/decoding. Samples stream through [sink] into the output
 * mdat; track timelines (tfdt/baseMediaDecodeTime or stts) carry across segments.
 */
class Mp4Demuxer(private val sink: SampleSink) {

    private class TrackState(
        val track: Track,
        var defaultSampleDuration: Long = 0,
        var defaultSampleSize: Long = 0,
        var defaultSampleFlags: Long = 0
    )

    private val byTrackId = LinkedHashMap<Int, TrackState>()
    private val trexDefaults = HashMap<Int, LongArray>() // trackId -> [dur, size, flags]

    val tracks: List<Track> get() = byTrackId.values.map { it.track }

    /** Parses one segment file (init, media, or a self-contained progressive MP4). */
    fun parseSegment(path: String) {
        RandomAccessFile(path, "r").use { raf ->
            walkTopLevel(raf, 0, raf.length())
        }
    }

    fun finish() {}

    // ---- top-level box walk over the file ----

    private fun walkTopLevel(raf: RandomAccessFile, from: Long, end: Long) {
        var pos = from
        while (pos + 8 <= end) {
            raf.seek(pos)
            var size = readU32(raf)
            val type = read4cc(raf)
            var headerLen = 8L
            if (size == 1L) { size = readU64(raf); headerLen = 16 }
            else if (size == 0L) { size = end - pos }
            if (size < headerLen) break
            val contentLen = (size - headerLen).toInt()
            when (type) {
                "moov" -> parseMoov(readBox(raf, pos + headerLen, contentLen), raf)
                "moof" -> parseMoof(readBox(raf, pos + headerLen, contentLen), pos, raf)
                // ftyp/styp/sidx/free/mdat etc: nothing to do (mdat is read on demand by offset).
            }
            pos += size
        }
    }

    // ---- moov (init or progressive) ----

    private fun parseMoov(data: ByteArray, raf: RandomAccessFile) {
        forEachBox(data, 0, data.size) { type, s, e ->
            when (type) {
                "mvex" -> forEachBox(data, s, e) { t2, s2, _ -> if (t2 == "trex") parseTrex(data, s2) }
                "trak" -> parseTrak(data, s, e, raf)
            }
        }
    }

    private fun parseTrex(data: ByteArray, start: Int) {
        val p = ParsableByteArray(data); p.seek(start + 4) // version/flags
        val trackId = p.readInt()
        p.readInt() // default_sample_description_index
        val dur = p.readUnsignedInt()
        val size = p.readUnsignedInt()
        val flags = p.readUnsignedInt()
        trexDefaults[trackId] = longArrayOf(dur, size, flags)
    }

    private fun parseTrak(data: ByteArray, start: Int, end: Int, raf: RandomAccessFile) {
        var trackId = 0
        var width = 0
        var height = 0
        var timescale = 0
        var isVideo = false
        var sampleEntry: ByteArray? = null
        var stblStart = -1; var stblEnd = -1

        forEachBox(data, start, end) { type, s, e ->
            when (type) {
                "tkhd" -> {
                    val v = data[s].toInt() and 0xFF
                    val p = ParsableByteArray(data)
                    // track_id sits after version/flags + (2 or 3) time fields.
                    p.seek(s + 4 + if (v == 1) 16 else 8)
                    trackId = p.readInt()
                    // width/height are the last two 32-bit fields (16.16) of tkhd.
                    val wOff = e - 8
                    width = ((data[wOff].toInt() and 0xFF) shl 8) or (data[wOff + 1].toInt() and 0xFF)
                    height = ((data[wOff + 4].toInt() and 0xFF) shl 8) or (data[wOff + 5].toInt() and 0xFF)
                }
                "mdia" -> forEachBox(data, s, e) { t2, s2, e2 ->
                    when (t2) {
                        "mdhd" -> {
                            val v = data[s2].toInt() and 0xFF
                            val p = ParsableByteArray(data)
                            p.seek(s2 + 4 + if (v == 1) 16 else 8)
                            timescale = p.readInt()
                        }
                        "hdlr" -> {
                            val handler = String(data, s2 + 8, 4, Charsets.US_ASCII)
                            isVideo = handler == "vide"
                        }
                        "minf" -> forEachBox(data, s2, e2) { t3, s3, e3 ->
                            if (t3 == "stbl") {
                                stblStart = s3; stblEnd = e3
                                forEachBox(data, s3, e3) { t4, s4, e4 ->
                                    if (t4 == "stsd") sampleEntry = firstSampleEntry(data, s4, e4)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (trackId == 0 || sampleEntry == null) return

        val track = Track(if (isVideo) Codec.OTHER_VIDEO else Codec.OTHER_AUDIO)
        track.timescale = if (timescale > 0) timescale else 90000
        track.width = width
        track.height = height
        track.sampleEntryBox = sampleEntry
        val state = TrackState(track)
        trexDefaults[trackId]?.let {
            state.defaultSampleDuration = it[0]; state.defaultSampleSize = it[1]; state.defaultSampleFlags = it[2]
        }
        byTrackId[trackId] = state

        // Progressive MP4: samples live in this file's stbl + mdat.
        if (stblStart >= 0) extractProgressiveSamples(data, stblStart, stblEnd, track, raf)
    }

    /** Returns the first sample entry box (with header) inside an stsd. */
    private fun firstSampleEntry(data: ByteArray, stsdStart: Int, stsdEnd: Int): ByteArray? {
        // stsd: version/flags(4) + entry_count(4), then sample entry boxes.
        var p = stsdStart + 8
        if (p + 8 > stsdEnd) return null
        val size = readU32(data, p).toInt()
        if (size < 8 || p + size > stsdEnd) return null
        return data.copyOfRange(p, p + size)
    }

    // ---- moof / traf / trun (fragmented) ----

    private fun parseMoof(data: ByteArray, moofFileStart: Long, raf: RandomAccessFile) {
        forEachBox(data, 0, data.size) { type, s, e ->
            if (type == "traf") parseTraf(data, s, e, moofFileStart, raf)
        }
    }

    private fun parseTraf(data: ByteArray, start: Int, end: Int, moofFileStart: Long, raf: RandomAccessFile) {
        var trackId = 0
        var baseDataOffset = -1L
        var defDur = -1L; var defSize = -1L; var defFlags = -1L
        var baseMediaDecodeTime = 0L
        val truns = ArrayList<IntArray>() // [contentStart, contentEnd]

        forEachBox(data, start, end) { type, s, e ->
            when (type) {
                "tfhd" -> {
                    val flags = readU24(data, s + 1)
                    val p = ParsableByteArray(data); p.seek(s + 4)
                    trackId = p.readInt()
                    if (flags and 0x000001 != 0) baseDataOffset = p.readUnsignedInt() shl 32 or p.readUnsignedInt()
                    if (flags and 0x000002 != 0) p.readInt() // sample_description_index
                    if (flags and 0x000008 != 0) defDur = p.readUnsignedInt()
                    if (flags and 0x000010 != 0) defSize = p.readUnsignedInt()
                    if (flags and 0x000020 != 0) defFlags = p.readUnsignedInt()
                }
                "tfdt" -> {
                    val v = data[s].toInt() and 0xFF
                    val p = ParsableByteArray(data); p.seek(s + 4)
                    baseMediaDecodeTime = if (v == 1) (p.readUnsignedInt() shl 32) or p.readUnsignedInt() else p.readUnsignedInt()
                }
                "trun" -> truns.add(intArrayOf(s, e))
            }
        }
        val state = byTrackId[trackId] ?: return
        val dDur = if (defDur >= 0) defDur else state.defaultSampleDuration
        val dSize = if (defSize >= 0) defSize else state.defaultSampleSize
        val dFlags = if (defFlags >= 0) defFlags else state.defaultSampleFlags
        val base = if (baseDataOffset >= 0) baseDataOffset else moofFileStart

        var runningDts = baseMediaDecodeTime
        for (trun in truns) {
            runningDts = parseTrun(data, trun[0], trun[1], base, dDur, dSize, dFlags, runningDts, state.track, raf)
        }
    }

    private fun parseTrun(
        data: ByteArray, start: Int, end: Int, base: Long,
        defDur: Long, defSize: Long, defFlags: Long,
        startDts: Long, track: Track, raf: RandomAccessFile
    ): Long {
        val version = data[start].toInt() and 0xFF
        val flags = readU24(data, start + 1)
        val p = ParsableByteArray(data); p.seek(start + 4)
        val sampleCount = p.readInt()
        var dataOffset = 0L
        if (flags and 0x000001 != 0) dataOffset = p.readInt().toLong() // signed
        var firstSampleFlags = -1L
        if (flags and 0x000004 != 0) firstSampleFlags = p.readUnsignedInt()

        var filePos = base + dataOffset
        var dts = startDts
        val buf = ByteArray(64 * 1024)
        raf.seek(filePos)

        for (i in 0 until sampleCount) {
            val dur = if (flags and 0x000100 != 0) p.readUnsignedInt() else defDur
            val size = if (flags and 0x000200 != 0) p.readUnsignedInt() else defSize
            val sFlags = when {
                flags and 0x000400 != 0 -> p.readUnsignedInt()
                i == 0 && firstSampleFlags >= 0 -> firstSampleFlags
                else -> defFlags
            }
            val cto = if (flags and 0x000800 != 0) {
                if (version == 1) p.readInt().toLong() else p.readUnsignedInt()
            } else 0L

            val sz = size.toInt()
            val isKey = (sFlags and 0x00010000L) == 0L
            val outOffset = copySample(raf, sz, buf)
            track.samples.add(Sample(outOffset, sz, dts + cto, dts, isKey))
            dts += dur
            filePos += sz
        }
        return dts
    }

    // ---- progressive stbl sample tables ----

    private fun extractProgressiveSamples(data: ByteArray, stblStart: Int, stblEnd: Int, track: Track, raf: RandomAccessFile) {
        var sttsOff = -1; var cttsOff = -1; var stscOff = -1; var stszOff = -1; var stcoOff = -1; var co64Off = -1; var stssOff = -1
        forEachBox(data, stblStart, stblEnd) { type, s, _ ->
            when (type) {
                "stts" -> sttsOff = s; "ctts" -> cttsOff = s; "stsc" -> stscOff = s
                "stsz" -> stszOff = s; "stco" -> stcoOff = s; "co64" -> co64Off = s; "stss" -> stssOff = s
            }
        }
        if (stszOff < 0 || stscOff < 0 || (stcoOff < 0 && co64Off < 0) || sttsOff < 0) return

        // Sample sizes.
        var p = ParsableByteArray(data); p.seek(stszOff + 4)
        val sampleSizeDefault = p.readUnsignedInt()
        val sampleCount = p.readInt()
        if (sampleCount == 0) return
        val sizes = IntArray(sampleCount) { if (sampleSizeDefault != 0L) sampleSizeDefault.toInt() else p.readInt() }

        // Chunk offsets.
        val chunkOffsets: LongArray = if (co64Off >= 0) {
            p = ParsableByteArray(data); p.seek(co64Off + 4); val n = p.readInt(); LongArray(n) { p.readUnsignedInt() shl 32 or p.readUnsignedInt() }
        } else {
            p = ParsableByteArray(data); p.seek(stcoOff + 4); val n = p.readInt(); LongArray(n) { p.readUnsignedInt() }
        }

        // stsc: samples per chunk runs.
        p = ParsableByteArray(data); p.seek(stscOff + 4)
        val stscCount = p.readInt()
        val firstChunk = IntArray(stscCount); val samplesPerChunk = IntArray(stscCount)
        for (i in 0 until stscCount) { firstChunk[i] = p.readInt(); samplesPerChunk[i] = p.readInt(); p.readInt() }

        // stts: durations.
        p = ParsableByteArray(data); p.seek(sttsOff + 4)
        val sttsCount = p.readInt()
        val sttsSampleN = IntArray(sttsCount); val sttsDelta = LongArray(sttsCount)
        for (i in 0 until sttsCount) { sttsSampleN[i] = p.readInt(); sttsDelta[i] = p.readUnsignedInt() }

        // ctts: composition offsets (optional).
        var cttsSampleN: IntArray? = null; var cttsOffset: IntArray? = null; var cttsVer = 0
        if (cttsOff >= 0) {
            cttsVer = data[cttsOff].toInt() and 0xFF
            p = ParsableByteArray(data); p.seek(cttsOff + 4)
            val c = p.readInt(); cttsSampleN = IntArray(c); cttsOffset = IntArray(c)
            for (i in 0 until c) { cttsSampleN!![i] = p.readInt(); cttsOffset!![i] = p.readInt() }
        }

        // stss: sync samples (1-based). If absent, all samples are sync.
        val syncSet: HashSet<Int>? = if (stssOff >= 0) {
            p = ParsableByteArray(data); p.seek(stssOff + 4); val c = p.readInt(); HashSet<Int>(c * 2).apply { repeat(c) { add(p.readInt()) } }
        } else null

        // Reconstruct per-sample file offsets from chunk table.
        val sampleFileOffsets = LongArray(sampleCount)
        var sampleIdx = 0
        for (chunkIdx in chunkOffsets.indices) {
            val spc = samplesPerChunkForChunk(chunkIdx + 1, firstChunk, samplesPerChunk)
            var off = chunkOffsets[chunkIdx]
            for (j in 0 until spc) {
                if (sampleIdx >= sampleCount) break
                sampleFileOffsets[sampleIdx] = off
                off += sizes[sampleIdx]
                sampleIdx++
            }
            if (sampleIdx >= sampleCount) break
        }

        // Iterate samples, expanding stts/ctts run-length tables.
        var dts = 0L
        var sttsi = 0; var sttsRem = if (sttsCount > 0) sttsSampleN[0] else 0
        var cttsi = 0; var cttsRem = cttsSampleN?.getOrElse(0) { 0 } ?: 0
        val buf = ByteArray(64 * 1024)
        for (i in 0 until sampleCount) {
            while (sttsRem == 0 && sttsi < sttsCount - 1) { sttsi++; sttsRem = sttsSampleN[sttsi] }
            val dur = sttsDelta.getOrElse(sttsi) { 0 }
            var cto = 0L
            if (cttsSampleN != null) {
                while (cttsRem == 0 && cttsi < cttsSampleN.size - 1) { cttsi++; cttsRem = cttsSampleN[cttsi] }
                cto = if (cttsVer == 1) cttsOffset!![cttsi].toLong() else (cttsOffset!![cttsi].toLong() and 0xFFFFFFFFL)
                cttsRem--
            }
            val isKey = syncSet?.contains(i + 1) ?: true
            raf.seek(sampleFileOffsets[i])
            val outOffset = copySample(raf, sizes[i], buf)
            track.samples.add(Sample(outOffset, sizes[i], dts + cto, dts, isKey))
            dts += dur
            sttsRem--
        }
    }

    private fun samplesPerChunkForChunk(chunk1Based: Int, firstChunk: IntArray, samplesPerChunk: IntArray): Int {
        var spc = 0
        for (i in firstChunk.indices) {
            if (chunk1Based >= firstChunk[i]) spc = samplesPerChunk[i] else break
        }
        return spc
    }

    // ---- io helpers ----

    /** Reads [size] bytes from the current raf position and stages them into the output mdat. */
    private fun copySample(raf: RandomAccessFile, size: Int, scratch: ByteArray): Long {
        val data = if (size <= scratch.size) scratch else ByteArray(size)
        raf.readFully(data, 0, size)
        return sink.writeSampleData(data, 0, size)
    }

    private fun readBox(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
        raf.seek(offset)
        val b = ByteArray(length)
        raf.readFully(b)
        return b
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val b = ByteArray(4); raf.readFully(b)
        return ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
    }

    private fun readU64(raf: RandomAccessFile): Long {
        val b = ByteArray(8); raf.readFully(b)
        var v = 0L; for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF); return v
    }

    private fun read4cc(raf: RandomAccessFile): String {
        val b = ByteArray(4); raf.readFully(b); return String(b, Charsets.US_ASCII)
    }

    private fun readU32(data: ByteArray, off: Int): Long =
        ((data[off].toLong() and 0xFF) shl 24) or ((data[off + 1].toLong() and 0xFF) shl 16) or
            ((data[off + 2].toLong() and 0xFF) shl 8) or (data[off + 3].toLong() and 0xFF)

    private fun readU24(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 16) or ((data[off + 1].toInt() and 0xFF) shl 8) or (data[off + 2].toInt() and 0xFF)

    /** Iterates child boxes in [data] between [start] and [end], invoking [body] with content bounds. */
    private inline fun forEachBox(data: ByteArray, start: Int, end: Int, body: (type: String, contentStart: Int, contentEnd: Int) -> Unit) {
        var p = start
        while (p + 8 <= end) {
            var size = readU32(data, p)
            val type = String(data, p + 4, 4, Charsets.US_ASCII)
            var headerLen = 8
            if (size == 1L) {
                // 64-bit size.
                var v = 0L; for (i in 0 until 8) v = (v shl 8) or (data[p + 8 + i].toLong() and 0xFF)
                size = v; headerLen = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < headerLen || p + size > end) break
            body(type, p + headerLen, (p + size).toInt())
            p += size.toInt()
        }
    }
}
