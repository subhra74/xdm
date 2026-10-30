// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.media.preview

import xdm.core.media.Mp4InitProbe

/**
 * Finds a handful of keyframes in an MP4 for a preview, without downloading the file.
 *
 * The caller reads the file in ranges ([Mp4PreviewReader]); this side only parses:
 * - the top-level box walk that finds `moov` (at the front for faststart files, after `mdat`
 *   otherwise) and, in a fragmented file, the `sidx` after it;
 * - a progressive file's video sample tables, which give every keyframe's offset and size;
 * - a fragmented file's `sidx`, which gives where each fragment starts, and a fragment's `moof`,
 *   which gives where its first sample - a keyframe - is.
 *
 * Nothing is decoded - the browser does that with WebCodecs, so the result also carries the codec
 * string and decoder config WebCodecs wants. A fragmented file without a `sidx` is not supported:
 * finding its fragments would take one read per fragment.
 */
object Mp4KeyframeLocator {

    /** A keyframe to fetch: its bytes are `[offset, offset + size)` in the file. */
    data class Keyframe(val offset: Long, val size: Int, val timeUs: Long)

    class Plan(
        /** WebCodecs codec string, e.g. `avc1.64001f`. */
        val codec: String,
        /** The `avcC`/`hvcC`/`av1C` body WebCodecs takes as `description`; null when the codec needs none (VP9). */
        val description: ByteArray?,
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val keyframes: List<Keyframe>,
    )

    /** The video track of a `moov`: what WebCodecs needs, and what reading its samples needs. */
    class VideoTrack(
        val codec: String,
        val description: ByteArray?,
        val width: Int,
        val height: Int,
        val timescale: Long,
        val trackId: Long,
        /** Samples live in `moof`s (`mvex` in the `moov`), not in the `stbl` tables. */
        val fragmented: Boolean,
        /** The `trex` default sample size, for fragments that give none of their own. */
        val defaultSampleSize: Long,
        internal val stblStart: Int,
        internal val stblEnd: Int,
    )

    /** A fragment of a fragmented file: its `moof` starts at [offset], and it is [size] bytes with its `mdat`. */
    data class Fragment(val offset: Long, val size: Long, val timeUs: Long)

    /** Result of walking top-level boxes in a buffer that starts at a box boundary. */
    sealed class Walk {
        /** The box looked for starts at [offset] and is [size] bytes long, header included. */
        data class Found(val offset: Long, val size: Long) : Walk()

        /** The buffer ended first; the next box header is at [offset]. */
        data class Next(val offset: Long) : Walk()

        /** Not a file this can preview: not MP4, or the box is not there. */
        data class Unsupported(val reason: String) : Walk()
    }

    /**
     * Walks the top-level boxes in [bytes], which were read from file offset [at] and start at a
     * box header, looking for a [target] box. Box headers alone are enough to hop over a large
     * `mdat` to a `moov` after it. Reaching a `moof` first means [target] is not where it should be.
     */
    fun walk(bytes: ByteArray, at: Long, target: String = "moov"): Walk {
        var p = 0
        while (p + 8 <= bytes.size) {
            var size = u32(bytes, p)
            val type = fourcc(bytes, p + 4)
            if (at == 0L && p == 0 && type != "ftyp") return Walk.Unsupported("not an MP4 file (starts with '$type')")
            if (size == 1L) {
                if (p + 16 > bytes.size) return Walk.Next(at + p)
                size = u64(bytes, p + 8)
            } else if (size == 0L) {
                // Runs to the end of the file, so nothing follows it.
                return if (type == target) Walk.Unsupported("$target without a size") else Walk.Unsupported("no $target")
            }
            if (size < 8) return Walk.Unsupported("bad box size $size for '$type'")
            when (type) {
                target -> return Walk.Found(at + p, size)
                "moof" -> return Walk.Unsupported(if (target == "sidx") "fragmented MP4 without a sidx" else "no $target before the fragments")
            }
            val next = p.toLong() + size
            if (next > bytes.size) return Walk.Next(at + next)
            p = next.toInt()
        }
        return Walk.Next(at + p)
    }

    /**
     * The first video track of [moov] (the whole box, header included). Null when there is none, it
     * is encrypted, or its codec has no WebCodecs mapping.
     */
    fun videoTrack(moov: ByteArray): VideoTrack? {
        if (moov.size < 8 || fourcc(moov, 4) != "moov") return null
        var track: VideoTrack? = null
        var mvex = -1; var mvexEnd = -1
        try {
            forEachBox(moov, 8, moov.size) { type, s, e ->
                when (type) {
                    "mvex" -> { mvex = s; mvexEnd = e }
                    "trak" -> if (track == null) track = videoTrak(moov, s, e)
                }
            }
            val found = track ?: return null
            if (mvex < 0) return found
            var defaultSize = 0L
            forEachBox(moov, mvex, mvexEnd) { type, s, _ ->
                // trex: version/flags, track_ID, sample_description_index, duration, size, flags.
                if (type == "trex" && u32(moov, s + 4) == found.trackId) defaultSize = u32(moov, s + 16)
            }
            return VideoTrack(
                found.codec, found.description, found.width, found.height, found.timescale, found.trackId,
                fragmented = true, defaultSampleSize = defaultSize, stblStart = found.stblStart, stblEnd = found.stblEnd,
            )
        } catch (_: IndexOutOfBoundsException) {
            // A box whose fields run past its end: a damaged or truncated moov.
            return null
        }
    }

    private fun videoTrak(b: ByteArray, start: Int, end: Int): VideoTrack? {
        var handler = ""
        var timescale = 0L
        var trackId = 0L
        var stbl: IntArray? = null
        forEachBox(b, start, end) { type, s, e ->
            when (type) {
                "tkhd" -> trackId = u32(b, s + 4 + if (b[s].toInt() == 1) 16 else 8)
                "mdia" -> forEachBox(b, s, e) { t2, s2, e2 ->
                    when (t2) {
                        "mdhd" -> timescale = u32(b, s2 + 4 + if (b[s2].toInt() == 1) 16 else 8)
                        "hdlr" -> handler = fourcc(b, s2 + 8)
                        "minf" -> forEachBox(b, s2, e2) { t3, s3, e3 -> if (t3 == "stbl") stbl = intArrayOf(s3, e3) }
                    }
                }
            }
        }
        val table = stbl ?: return null
        if (handler != "vide" || timescale <= 0) return null
        var stsd = -1; var stsdEnd = -1
        forEachBox(b, table[0], table[1]) { type, s, e -> if (type == "stsd") { stsd = s; stsdEnd = e } }
        if (stsd < 0) return null
        val entry = sampleEntry(b, stsd, stsdEnd) ?: return null
        return VideoTrack(
            entry.codec, entry.description, entry.width, entry.height, timescale, trackId,
            fragmented = false, defaultSampleSize = 0, stblStart = table[0], stblEnd = table[1],
        )
    }

    /**
     * Picks up to [count] keyframes spread over [track], a progressive track of [moov]. Null when
     * the track is fragmented or its tables are unusable.
     */
    fun plan(moov: ByteArray, track: VideoTrack, count: Int): Plan? {
        if (track.fragmented) return null
        return try {
            planSamples(moov, track, count)
        } catch (_: IndexOutOfBoundsException) {
            // A table whose counts run past its box: a damaged or truncated moov.
            null
        }
    }

    /** [plan] for the first video track of [moov]. */
    fun plan(moov: ByteArray, count: Int): Plan? = videoTrack(moov)?.let { plan(moov, it, count) }

    /**
     * Up to [count] fragments spread over a fragmented file, by its [sidx]: the subsegment nearest
     * each evenly spread point, never the same one twice. Each starts with a keyframe.
     */
    fun fragments(sidx: Mp4InitProbe.Sidx, count: Int): List<Fragment> {
        if (sidx.refs.isEmpty() || sidx.timescale <= 0) return emptyList()
        val starts = ArrayList<Fragment>(sidx.refs.size)
        var offset = sidx.firstOffset
        var time = 0L
        for (ref in sidx.refs) {
            starts.add(Fragment(offset, ref.size, time * 1_000_000 / sidx.timescale))
            offset += ref.size
            time += ref.duration
        }
        val totalUs = time * 1_000_000 / sidx.timescale
        val chosen = sortedSetOf<Int>()
        for (target in targets(totalUs, count)) {
            starts.indices.filter { it !in chosen }.minByOrNull { Math.abs(starts[it].timeUs - target) }?.let { chosen.add(it) }
        }
        return chosen.map { starts[it] }
    }

    /**
     * Where the first sample of [track] in a fragment is: [moof] is the whole `moof` box, read from
     * file offset [moofOffset]. Null when the fragment has no sample of that track.
     */
    fun firstSample(moof: ByteArray, moofOffset: Long, track: VideoTrack, timeUs: Long): Keyframe? = try {
        var result: Keyframe? = null
        forEachBox(moof, 8, moof.size) { type, s, e ->
            if (type != "traf" || result != null) return@forEachBox
            var trackId = -1L
            var base = moofOffset
            var defaultSize = track.defaultSampleSize
            var dataOffset: Long? = null
            var size: Long? = null
            forEachBox(moof, s, e) { t2, s2, _ ->
                when (t2) {
                    "tfhd" -> {
                        val flags = u24(moof, s2 + 1)
                        trackId = u32(moof, s2 + 4)
                        var p = s2 + 8
                        if (flags and 0x01 != 0) { base = u64(moof, p); p += 8 }
                        if (flags and 0x02 != 0) p += 4
                        if (flags and 0x08 != 0) p += 4
                        if (flags and 0x10 != 0) defaultSize = u32(moof, p)
                    }
                    "trun" -> if (size == null) {
                        val flags = u24(moof, s2 + 1)
                        if (u32(moof, s2 + 4) == 0L) return@forEachBox
                        var p = s2 + 8
                        if (flags and 0x01 != 0) { dataOffset = u32(moof, p).toInt().toLong(); p += 4 }
                        if (flags and 0x04 != 0) p += 4
                        if (flags and 0x100 != 0) p += 4
                        size = if (flags and 0x200 != 0) u32(moof, p) else defaultSize
                    }
                }
            }
            val first = size
            if (trackId == track.trackId && first != null && first > 0 && first <= Int.MAX_VALUE) {
                // Without a data offset the samples start right after the moof, in the mdat's body.
                val offset = dataOffset?.let { base + it } ?: (moofOffset + moof.size + 8)
                result = Keyframe(offset, first.toInt(), timeUs)
            }
        }
        result
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun planSamples(b: ByteArray, track: VideoTrack, count: Int): Plan? {
        val timescale = track.timescale
        var stts = -1; var stss = -1; var stsc = -1; var stsz = -1; var stco = -1; var co64 = -1
        forEachBox(b, track.stblStart, track.stblEnd) { type, s, _ ->
            when (type) {
                "stts" -> stts = s
                "stss" -> stss = s
                "stsc" -> stsc = s
                "stsz" -> stsz = s
                "stco" -> stco = s
                "co64" -> co64 = s
            }
        }
        if (stts < 0 || stsc < 0 || stsz < 0 || (stco < 0 && co64 < 0)) return null

        val sampleCount = u32(b, stsz + 8).toInt()
        if (sampleCount <= 0) return null
        val fixedSize = u32(b, stsz + 4)
        fun sizeOf(i: Int): Long = if (fixedSize != 0L) fixedSize else u32(b, stsz + 12 + 4 * i)

        // Decode times of every sample, from the run-length stts.
        val dts = LongArray(sampleCount)
        var t = 0L
        var i = 0
        for (run in 0 until u32(b, stts + 4).toInt()) {
            val n = u32(b, stts + 8 + 8 * run)
            val delta = u32(b, stts + 12 + 8 * run)
            var k = 0L
            while (k < n && i < sampleCount) { dts[i++] = t; t += delta; k++ }
        }
        while (i < sampleCount) dts[i++] = t
        val durationUs = t * 1_000_000 / timescale

        // Sync samples (1-based); a track without stss has only keyframes.
        val sync: IntArray = if (stss >= 0) {
            IntArray(u32(b, stss + 4).toInt()) { u32(b, stss + 8 + 4 * it).toInt() - 1 }
                .filter { it in 0 until sampleCount }.toIntArray()
        } else IntArray(sampleCount) { it }
        if (sync.isEmpty()) return null

        // Each target point takes the nearest keyframe not taken yet, so sparse keyframes still give
        // min(count, keyframes).
        val chosen = sortedSetOf<Int>()
        for (target in targets(t, count)) {
            sync.filter { it !in chosen }.minByOrNull { Math.abs(dts[it] - target) }?.let { chosen.add(it) }
        }

        val chunkOffsets = chunkOffsets(b, stco, co64)
        val keyframes = chosen.mapNotNull { sample ->
            val offset = sampleOffset(b, stsc, chunkOffsets, sample, ::sizeOf) ?: return@mapNotNull null
            val size = sizeOf(sample)
            if (size <= 0 || size > Int.MAX_VALUE) return@mapNotNull null
            Keyframe(offset, size.toInt(), dts[sample] * 1_000_000 / timescale)
        }
        if (keyframes.isEmpty()) return null
        return Plan(track.codec, track.description, track.width, track.height, durationUs, keyframes)
    }

    /**
     * Points in time, out of [total], to take frames near: the start for a single frame (social feed
     * videos open on real content, and the first keyframe sits right after the head), else evenly
     * spread, e.g. 10/30/50/70/90% for five.
     */
    private fun targets(total: Long, count: Int): List<Long> =
        if (count == 1) listOf(0L) else (0 until count).map { k -> total * (2 * k + 1) / (2 * count) }

    private fun chunkOffsets(b: ByteArray, stco: Int, co64: Int): LongArray =
        if (co64 >= 0) LongArray(u32(b, co64 + 4).toInt()) { u64(b, co64 + 8 + 8 * it) }
        else LongArray(u32(b, stco + 4).toInt()) { u32(b, stco + 8 + 4 * it) }

    /** File offset of [sample] (0-based): its chunk's offset plus the sizes of the samples before it in that chunk. */
    private inline fun sampleOffset(
        b: ByteArray, stsc: Int, chunkOffsets: LongArray, sample: Int, sizeOf: (Int) -> Long,
    ): Long? {
        val runs = u32(b, stsc + 4).toInt()
        var first = 0 // first sample of the current chunk
        for (r in 0 until runs) {
            val firstChunk = u32(b, stsc + 8 + 12 * r).toInt() - 1
            val perChunk = u32(b, stsc + 12 + 12 * r).toInt()
            val nextFirstChunk = if (r + 1 < runs) u32(b, stsc + 8 + 12 * (r + 1)).toInt() - 1 else chunkOffsets.size
            if (perChunk <= 0 || firstChunk < 0 || nextFirstChunk <= firstChunk) continue
            val chunksInRun = nextFirstChunk - firstChunk
            val samplesInRun = chunksInRun.toLong() * perChunk
            if (sample < first + samplesInRun) {
                val chunk = firstChunk + (sample - first) / perChunk
                if (chunk >= chunkOffsets.size) return null
                var offset = chunkOffsets[chunk]
                for (s in sample - (sample - first) % perChunk until sample) offset += sizeOf(s)
                return offset
            }
            first += samplesInRun.toInt()
        }
        return null
    }

    private class Entry(val codec: String, val description: ByteArray?, val width: Int, val height: Int)

    /** The first visual sample entry of an stsd, mapped to what WebCodecs needs. */
    private fun sampleEntry(b: ByteArray, stsd: Int, stsdEnd: Int): Entry? {
        // stsd: version/flags(4) + entry_count(4), then the entries.
        val p = stsd + 8
        if (p + 8 > stsdEnd) return null
        val size = u32(b, p).toInt()
        if (size < 86 || p + size > stsdEnd) return null
        val format = fourcc(b, p + 4)
        val width = u16(b, p + 32)
        val height = u16(b, p + 34)
        // VisualSampleEntry fields are 78 bytes after the box header; the config boxes follow.
        var entry: Entry? = null
        forEachBox(b, p + 86, p + size) { type, s, e ->
            if (entry != null) return@forEachBox
            val body = b.copyOfRange(s, e)
            val codec = when (type) {
                "avcC" -> if (format == "avc1" || format == "avc3") CodecStrings.avc(format, body) else null
                "hvcC" -> if (format == "hvc1" || format == "hev1") CodecStrings.hevc(format, body) else null
                "av1C" -> if (format == "av01") CodecStrings.av1(body) else null
                "vpcC" -> if (format == "vp09") CodecStrings.vp9(body) else null
                else -> null
            } ?: return@forEachBox
            entry = Entry(codec, if (type == "vpcC") null else body, width, height)
        }
        return entry
    }

    private inline fun forEachBox(b: ByteArray, start: Int, end: Int, body: (type: String, contentStart: Int, contentEnd: Int) -> Unit) {
        var p = start
        while (p + 8 <= end) {
            var size = u32(b, p)
            var header = 8
            if (size == 1L) {
                if (p + 16 > end) return
                size = u64(b, p + 8); header = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < header || p + size > end) return
            body(fourcc(b, p + 4), p + header, (p + size).toInt())
            p += size.toInt()
        }
    }

    internal fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    internal fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u24(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at + 2].toInt() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    private fun fourcc(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.US_ASCII)
}
