// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.iso

import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.io.ParsableByteArray
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.DisplayMatrix
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.util.Logger
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
        var inputTrackId: Int,
        /** Timescale of the current init segment; samples are rescaled to [Track.timescale]. */
        var inputTimescale: Int,
        /** Output stsd index (1-based) of each input sample entry of the current init segment, in stsd order. */
        var entryMap: IntArray = intArrayOf(1),
        /** Input sample description index from `trex`, used when a `tfhd` doesn't give one. */
        var defaultDescriptionIndex: Int = 1,
        var defaultSampleDuration: Long = 0,
        var defaultSampleSize: Long = 0,
        var defaultSampleFlags: Long = 0,
        /** Input ticks added to every DTS/PTS so the track sits on the movie (presentation) timeline. */
        var editShift: Long = 0,
        /** Decode time just past the last fragment's samples: where a traf without tfdt continues. */
        var fragmentEnd: Long = 0
    ) {
        fun scale(v: Long): Long =
            if (inputTimescale == track.timescale || inputTimescale <= 0) v else v * track.timescale / inputTimescale

        /** Output stsd index for a 1-based input index; an index out of range falls back to the first entry. */
        fun outputIndex(inputIndex: Int): Int = entryMap.getOrElse(inputIndex - 1) { entryMap[0] }
    }

    /** Every output track, in first-seen order. */
    private val states = ArrayList<TrackState>()
    /** Input track_ID -> output track, for the most recent init segment (moov). */
    private val current = HashMap<Int, TrackState>()
    private val trexDefaults = HashMap<Int, LongArray>() // trackId -> [dur, size, flags, description index]
    /** mvhd timescale of the most recent moov (edit list segment durations are in it). */
    private var movieTimescale = 0L

    val tracks: List<Track> get() = states.map { it.track }

    /**
     * Bytes in front of the MP4 data in the segment being parsed. Absolute offsets written into the
     * segment (an explicit `base_data_offset`, `stco`/`co64` chunk offsets) count from where the MP4
     * data starts, so they are moved by this much.
     */
    private var fileShift = 0L

    /** Length of the segment being parsed: sample data past it was cut off (a truncated download). */
    private var fileLength = 0L

    /** Samples of the current segment dropped because their data runs past [fileLength], for one log line. */
    private var cutSamples = 0

    /**
     * Parses one segment file (init, media, or a self-contained progressive MP4). [from] is where the
     * MP4 data starts when the file has other bytes in front of it (see `SegmentProbe`).
     */
    fun parseSegment(path: String, from: Long = 0) {
        RandomAccessFile(path, "r").use { raf ->
            fileShift = from
            fileLength = raf.length()
            cutSamples = 0
            walkTopLevel(raf, from, fileLength)
            if (cutSamples > 0) Logger.info("XDM", "MP4: segment cut short, $cutSamples samples past its end dropped: $path")
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
            // Some writers leave a `free` box with a size smaller than its header: skip just the header.
            if (size < headerLen && type == "free" && headerLen == 8L) size = 8
            if (size < headerLen) break
            // A box running past the end of the file (a cut download) is read as far as it goes: its
            // complete child boxes still count, and samples whose data is missing are dropped.
            val contentLen = minOf(size, end - pos) - headerLen
            when (type) {
                "moov" -> if (contentLen >= 0) parseMoov(readBox(raf, pos + headerLen, contentLen.toInt()), raf)
                "moof" -> if (contentLen >= 0) parseMoof(readBox(raf, pos + headerLen, contentLen.toInt()), pos, raf)
                // ftyp/styp/sidx/free/mdat etc: nothing to do (mdat is read on demand by offset).
            }
            pos += size
        }
    }

    // ---- moov (init or progressive) ----

    /**
     * A moov may arrive more than once (HLS repeats `EXT-X-MAP` across discontinuities): later ones
     * continue the existing tracks rather than replacing them, so earlier samples are kept.
     */
    private fun parseMoov(data: ByteArray, raf: RandomAccessFile) {
        current.clear()
        trexDefaults.clear()
        movieTimescale = 0
        // mvex (trex defaults) follows the traks, but parseTrak needs it: read it first.
        forEachBox(data, 0, data.size) { type, s, e ->
            if (type == "mvex") forEachBox(data, s, e) { t2, s2, _ -> if (t2 == "trex") parseTrex(data, s2) }
            if (type == "mvhd") {
                val p = ParsableByteArray(data); p.seek(s + 4 + if ((data[s].toInt() and 0xFF) == 1) 16 else 8)
                movieTimescale = p.readUnsignedInt()
            }
        }
        forEachBox(data, 0, data.size) { type, s, e ->
            if (type == "trak") parseTrak(data, s, e, raf)
        }
    }

    private fun parseTrex(data: ByteArray, start: Int) {
        val p = ParsableByteArray(data); p.seek(start + 4) // version/flags
        val trackId = p.readInt()
        val descriptionIndex = p.readUnsignedInt()
        val dur = p.readUnsignedInt()
        val size = p.readUnsignedInt()
        val flags = p.readUnsignedInt()
        trexDefaults[trackId] = longArrayOf(dur, size, flags, descriptionIndex)
    }

    private fun parseTrak(data: ByteArray, start: Int, end: Int, raf: RandomAccessFile) {
        var trackId = 0
        var width = 0
        var height = 0
        var matrix: IntArray? = null
        var timescale = 0
        var handler = ""
        var sampleEntries: List<ByteArray> = emptyList()
        var stblStart = -1; var stblEnd = -1
        var elst: List<LongArray>? = null // [segment_duration (movie ts), media_time (media ts)]

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
                    // The display matrix (rotation / flip): the nine values just before width and height.
                    if (wOff - 36 >= s) {
                        val m = IntArray(9) { readU32(data, wOff - 36 + it * 4).toInt() }
                        if (!DisplayMatrix.isUnity(m)) matrix = m
                    }
                }
                "edts" -> forEachBox(data, s, e) { t2, s2, _ -> if (t2 == "elst") elst = parseElst(data, s2) }
                "mdia" -> forEachBox(data, s, e) { t2, s2, e2 ->
                    when (t2) {
                        "mdhd" -> {
                            val v = data[s2].toInt() and 0xFF
                            val p = ParsableByteArray(data)
                            p.seek(s2 + 4 + if (v == 1) 16 else 8)
                            timescale = p.readInt()
                        }
                        "hdlr" -> {
                            handler = String(data, s2 + 8, 4, Charsets.US_ASCII)
                        }
                        "minf" -> forEachBox(data, s2, e2) { t3, s3, e3 ->
                            if (t3 == "stbl") {
                                stblStart = s3; stblEnd = e3
                                forEachBox(data, s3, e3) { t4, s4, e4 ->
                                    if (t4 == "stsd") sampleEntries = sampleEntries(data, s4, e4)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (trackId == 0 || sampleEntries.isEmpty()) return
        // Only audio and video are muxed; subtitle (subt/text/sbtl), timed-metadata (meta) and hint
        // tracks are skipped, so their fragments are ignored too (they never enter [current]).
        if (handler != "vide" && handler != "soun") {
            Logger.info("XDM", "MP4: skipping non-A/V track $trackId (handler '$handler')")
            return
        }
        val isVideo = handler == "vide"

        val inputTimescale = if (timescale > 0) timescale else 90000
        val state = continuingTrack(trackId, isVideo)?.also { st ->
            st.inputTrackId = trackId
            st.inputTimescale = inputTimescale
            // One matrix per output track: the first init segment's is kept.
            if (isVideo && !(st.track.matrix ?: DisplayMatrix.UNITY).contentEquals(matrix ?: DisplayMatrix.UNITY)) {
                Logger.info("XDM", "MP4: track $trackId changes its display matrix in a later init segment, keeping the first")
            }
        } ?: run {
            val track = Track(if (isVideo) Codec.OTHER_VIDEO else Codec.OTHER_AUDIO)
            track.timescale = inputTimescale
            track.width = width
            track.height = height
            if (isVideo) track.matrix = matrix
            track.sampleEntryBox = sampleEntries[0]
            TrackState(track, trackId, inputTimescale).also { states.add(it) }
        }
        // Every entry of the stsd, not just the first: samples name theirs by index (tfhd, trex, stsc).
        state.entryMap = IntArray(sampleEntries.size) { state.track.descriptionIndexFor(sampleEntries[it]) }
        val trex = trexDefaults[trackId]
        state.defaultSampleDuration = trex?.get(0) ?: 0
        state.defaultSampleSize = trex?.get(1) ?: 0
        state.defaultSampleFlags = trex?.get(2) ?: 0
        state.defaultDescriptionIndex = trex?.get(3)?.toInt()?.takeIf { it > 0 } ?: 1
        state.editShift = editShift(elst, inputTimescale)
        current[trackId] = state

        // Progressive MP4: samples live in this file's stbl + mdat.
        if (stblStart >= 0) extractProgressiveSamples(data, stblStart, stblEnd, state, raf)
    }

    private fun parseElst(data: ByteArray, start: Int): List<LongArray> {
        val v = data[start].toInt() and 0xFF
        val p = ParsableByteArray(data); p.seek(start + 4)
        return List(p.readInt()) {
            val entry = if (v == 1) longArrayOf(p.readUnsignedInt() shl 32 or p.readUnsignedInt(), p.readUnsignedInt() shl 32 or p.readUnsignedInt())
            else longArrayOf(p.readUnsignedInt(), p.readInt().toLong())
            p.skipBytes(4) // media_rate
            entry
        }
    }

    /**
     * Maps media time onto the movie timeline per the edit list: leading empty edits delay the
     * track (A/V start offset), and the first real edit's media_time is where presentation begins
     * (B-frame composition delay, AAC priming). Without it the tracks lose their relative alignment.
     */
    private fun editShift(elst: List<LongArray>?, mediaTimescale: Int): Long {
        if (elst.isNullOrEmpty()) return 0
        var emptyMovie = 0L
        for (e in elst) {
            if (e[1] == -1L) { emptyMovie += e[0]; continue }
            val empty = if (movieTimescale > 0) emptyMovie * mediaTimescale / movieTimescale else 0
            return empty - e[1]
        }
        return 0
    }

    /**
     * The existing output track a trak from a repeated init segment continues: same track_ID and
     * kind, else the only not-yet-claimed track of that kind (an ad/period that renumbered its
     * tracks). Null for the first init segment, or when the trak is genuinely new.
     */
    private fun continuingTrack(trackId: Int, isVideo: Boolean): TrackState? {
        val free = states.filter { it.track.codec.isVideo == isVideo && it !in current.values }
        return free.firstOrNull { it.inputTrackId == trackId } ?: free.singleOrNull()
    }

    /** The sample entry boxes (with headers) inside an stsd, in order; stops at the first malformed one. */
    private fun sampleEntries(data: ByteArray, stsdStart: Int, stsdEnd: Int): List<ByteArray> {
        // stsd: version/flags(4) + entry_count(4), then sample entry boxes.
        if (stsdStart + 8 > stsdEnd) return emptyList()
        val count = readU32(data, stsdStart + 4)
        val out = ArrayList<ByteArray>()
        var p = stsdStart + 8
        while (out.size < count && p + 8 <= stsdEnd) {
            val size = readU32(data, p)
            if (size < 8 || p + size > stsdEnd) break
            out.add(data.copyOfRange(p, p + size.toInt()))
            p += size.toInt()
        }
        return out
    }

    // ---- moof / traf / trun (fragmented) ----

    private fun parseMoof(data: ByteArray, moofFileStart: Long, raf: RandomAccessFile) {
        // Without base_data_offset or default-base-is-moof, a traf's data starts where the previous
        // traf's ended; the first traf's at the moof (ISO/IEC 14496-12 8.8.7.1).
        var previousDataEnd = -1L
        forEachBox(data, 0, data.size) { type, s, e ->
            if (type == "traf") previousDataEnd = parseTraf(data, s, e, moofFileStart, previousDataEnd, raf)
        }
    }

    /** Returns the file position just past this traf's sample data (or [previousDataEnd] if it had none). */
    private fun parseTraf(
        data: ByteArray, start: Int, end: Int, moofFileStart: Long, previousDataEnd: Long, raf: RandomAccessFile
    ): Long {
        var trackId = 0
        var baseDataOffset = -1L
        var defaultBaseIsMoof = false
        var defDur = -1L; var defSize = -1L; var defFlags = -1L
        var descriptionIndex = -1
        var baseMediaDecodeTime = -1L
        val truns = ArrayList<IntArray>() // [contentStart, contentEnd]

        forEachBox(data, start, end) { type, s, e ->
            when (type) {
                "tfhd" -> {
                    val flags = readU24(data, s + 1)
                    val p = ParsableByteArray(data); p.seek(s + 4)
                    trackId = p.readInt()
                    defaultBaseIsMoof = flags and 0x020000 != 0
                    if (flags and 0x000001 != 0) baseDataOffset = p.readUnsignedInt() shl 32 or p.readUnsignedInt()
                    if (flags and 0x000002 != 0) descriptionIndex = p.readInt()
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
        val base = when {
            baseDataOffset >= 0 -> baseDataOffset + fileShift
            defaultBaseIsMoof || previousDataEnd < 0 -> moofFileStart
            else -> previousDataEnd
        }
        val state = current[trackId]
        // A track we don't mux still occupies data that the next traf may follow.
        if (state == null) return skipTrafData(data, truns, base, defSize, trexDefaults[trackId]?.get(1) ?: 0)
        val dDur = if (defDur >= 0) defDur else state.defaultSampleDuration
        val dSize = if (defSize >= 0) defSize else state.defaultSampleSize
        val dFlags = if (defFlags >= 0) defFlags else state.defaultSampleFlags
        val outIndex = state.outputIndex(if (descriptionIndex > 0) descriptionIndex else state.defaultDescriptionIndex)

        // Without a tfdt the fragment continues where the track's previous one ended, as ffmpeg does.
        var runningDts = if (baseMediaDecodeTime >= 0) baseMediaDecodeTime else state.fragmentEnd
        // A trun without data_offset continues right after the previous run's data (8.8.8.3).
        var dataPos = base
        for (trun in truns) {
            val r = parseTrun(data, trun[0], trun[1], base, dataPos, dDur, dSize, dFlags, runningDts, state, outIndex, raf)
            runningDts = r[0]
            dataPos = r[1]
        }
        state.fragmentEnd = runningDts
        return if (truns.isEmpty()) previousDataEnd else dataPos
    }

    /** End of a skipped traf's data: the sizes are summed without reading samples. */
    private fun skipTrafData(data: ByteArray, truns: List<IntArray>, base: Long, tfhdSize: Long, trexSize: Long): Long {
        var pos = base
        for ((start, _) in truns) {
            val flags = readU24(data, start + 1)
            val p = ParsableByteArray(data); p.seek(start + 4)
            val n = p.readInt()
            if (flags and 0x000001 != 0) pos = base + p.readInt()
            if (flags and 0x000004 != 0) p.readInt()
            val defSize = if (tfhdSize >= 0) tfhdSize else trexSize
            for (i in 0 until n) {
                if (flags and 0x000100 != 0) p.readInt()
                pos += if (flags and 0x000200 != 0) p.readUnsignedInt() else defSize
                if (flags and 0x000400 != 0) p.readInt()
                if (flags and 0x000800 != 0) p.readInt()
            }
        }
        return pos
    }

    /** Returns [next DTS, file position just past this run's data]. */
    private fun parseTrun(
        data: ByteArray, start: Int, end: Int, base: Long, continueAt: Long,
        defDur: Long, defSize: Long, defFlags: Long,
        startDts: Long, state: TrackState, descriptionIndex: Int, raf: RandomAccessFile
    ): LongArray {
        val version = data[start].toInt() and 0xFF
        val flags = readU24(data, start + 1)
        val p = ParsableByteArray(data); p.seek(start + 4)
        val sampleCount = p.readInt()
        var filePos = continueAt
        if (flags and 0x000001 != 0) filePos = base + p.readInt().toLong() // signed, relative to base
        var firstSampleFlags = -1L
        if (flags and 0x000004 != 0) firstSampleFlags = p.readUnsignedInt()

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
            // Version 0 offsets are unsigned by the spec, but packagers write negative ones there too;
            // read signed in both versions, as ffmpeg and Media3 do.
            val cto = if (flags and 0x000800 != 0) p.readInt().toLong() else 0L

            val sz = size.toInt()
            // The rest of the run's data was cut off: drop it (the next run or segment may still be whole).
            if (filePos + sz > fileLength) {
                cutSamples += sampleCount - i
                break
            }
            val isKey = (sFlags and 0x00010000L) == 0L
            val outOffset = copySample(raf, sz, buf)
            state.track.samples.add(
                outOffset, sz, state.scale(dts + cto + state.editShift), state.scale(dts + state.editShift), isKey, descriptionIndex
            )
            dts += dur
            filePos += sz
        }
        return longArrayOf(dts, filePos)
    }

    // ---- progressive stbl sample tables ----

    private fun extractProgressiveSamples(data: ByteArray, stblStart: Int, stblEnd: Int, state: TrackState, raf: RandomAccessFile) {
        val track = state.track
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
        val firstChunk = IntArray(stscCount); val samplesPerChunk = IntArray(stscCount); val chunkEntry = IntArray(stscCount)
        for (i in 0 until stscCount) { firstChunk[i] = p.readInt(); samplesPerChunk[i] = p.readInt(); chunkEntry[i] = p.readInt() }

        // stts: durations.
        p = ParsableByteArray(data); p.seek(sttsOff + 4)
        val sttsCount = p.readInt()
        val sttsSampleN = IntArray(sttsCount); val sttsDelta = LongArray(sttsCount)
        for (i in 0 until sttsCount) { sttsSampleN[i] = p.readInt(); sttsDelta[i] = p.readUnsignedInt() }

        // ctts: composition offsets (optional).
        var cttsSampleN: IntArray? = null; var cttsOffset: IntArray? = null
        if (cttsOff >= 0) {
            p = ParsableByteArray(data); p.seek(cttsOff + 4)
            val c = p.readInt(); cttsSampleN = IntArray(c); cttsOffset = IntArray(c)
            for (i in 0 until c) { cttsSampleN!![i] = p.readInt(); cttsOffset!![i] = p.readInt() }
        }

        // stss: sync samples (1-based). If absent, all samples are sync.
        val syncSet: HashSet<Int>? = if (stssOff >= 0) {
            p = ParsableByteArray(data); p.seek(stssOff + 4); val c = p.readInt(); HashSet<Int>(c * 2).apply { repeat(c) { add(p.readInt()) } }
        } else null

        // Reconstruct per-sample file offsets (and sample entries) from the chunk table.
        val sampleFileOffsets = LongArray(sampleCount)
        val sampleEntryIndex = IntArray(sampleCount)
        var sampleIdx = 0
        for (chunkIdx in chunkOffsets.indices) {
            val run = stscRunForChunk(chunkIdx + 1, firstChunk)
            val spc = if (run >= 0) samplesPerChunk[run] else 0
            val entry = state.outputIndex(if (run >= 0) chunkEntry[run] else 1)
            var off = chunkOffsets[chunkIdx] + fileShift
            for (j in 0 until spc) {
                if (sampleIdx >= sampleCount) break
                sampleFileOffsets[sampleIdx] = off
                sampleEntryIndex[sampleIdx] = entry
                off += sizes[sampleIdx]
                sampleIdx++
            }
            if (sampleIdx >= sampleCount) break
        }
        // The chunks ran out before the sample count: the track ends at the last sample with data.
        if (sampleIdx < sampleCount) {
            Logger.info("XDM", "MP4: chunk table covers $sampleIdx of $sampleCount samples, track ends there")
        }
        val withData = sampleIdx

        // Iterate samples, expanding stts/ctts run-length tables.
        var dts = 0L
        var sttsi = 0; var sttsRem = if (sttsCount > 0) sttsSampleN[0] else 0
        var cttsi = 0; var cttsRem = cttsSampleN?.getOrElse(0) { 0 } ?: 0
        val buf = ByteArray(64 * 1024)
        for (i in 0 until withData) {
            while (sttsRem == 0 && sttsi < sttsCount - 1) { sttsi++; sttsRem = sttsSampleN[sttsi] }
            val dur = sttsDelta.getOrElse(sttsi) { 0 }
            var cto = 0L
            if (cttsSampleN != null) {
                while (cttsRem == 0 && cttsi < cttsSampleN.size - 1) { cttsi++; cttsRem = cttsSampleN[cttsi] }
                // Signed in both versions (see parseTrun).
                cto = cttsOffset!![cttsi].toLong()
                cttsRem--
            }
            val isKey = syncSet?.contains(i + 1) ?: true
            // A cut download: the track ends at the first sample whose data isn't all in the file.
            if (sampleFileOffsets[i] + sizes[i] > fileLength) {
                cutSamples += withData - i
                break
            }
            raf.seek(sampleFileOffsets[i])
            val outOffset = copySample(raf, sizes[i], buf)
            track.samples.add(
                outOffset, sizes[i], state.scale(dts + cto + state.editShift), state.scale(dts + state.editShift), isKey, sampleEntryIndex[i]
            )
            dts += dur
            sttsRem--
        }
    }

    /** The stsc run (index into its tables) that [chunk1Based] falls in; -1 before the first run. */
    private fun stscRunForChunk(chunk1Based: Int, firstChunk: IntArray): Int {
        var run = -1
        for (i in firstChunk.indices) {
            if (chunk1Based >= firstChunk[i]) run = i else break
        }
        return run
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
