package xdm.core.media.muxer.transmux.mp4

import xdm.core.media.muxer.transmux.ContainerWriter
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Sample
import xdm.core.media.muxer.transmux.sample.Track
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Writes a progressive (single-file) MP4 from demuxed [Track]s.
 *
 * Layout: `ftyp` then a streaming `mdat` (64-bit largesize) then `moov`. Sample bytes are appended
 * to mdat as the demuxer produces them (this class is the [SampleSink]); only lightweight sample
 * records are kept in memory, so the `moov` sample tables can be built in one pass at the end.
 * Each sample is emitted as its own MP4 chunk, which correctly handles audio/video interleaving.
 */
class Mp4Writer(outputPath: String, private val repairTimeline: Boolean = false) : ContainerWriter {
    private val file = File(outputPath)
    private val bos = BufferedOutputStream(FileOutputStream(file), 1 shl 20)
    private var position = 0L
    private var mdatHeaderPos = 0L
    private var mdatDataStart = 0L

    private val movieTimescale = 1000

    init {
        writeFtyp()
        mdatHeaderPos = position
        // 64-bit mdat header: size=1, type, largesize placeholder.
        writeRaw(byteArrayOf(0, 0, 0, 1))
        writeRaw("mdat".toByteArray(Charsets.US_ASCII))
        writeRaw(ByteArray(8)) // largesize placeholder
        mdatDataStart = position
    }

    override fun writeSampleData(data: ByteArray, offset: Int, length: Int): Long {
        val at = position
        bos.write(data, offset, length)
        position += length
        return at
    }

    /** Finalizes the file: patches the mdat size and appends the moov. Returns false if no media. */
    override fun finish(tracks: List<Track>): Boolean {
        val usable = tracks.filter { it.samples.isNotEmpty() && it.isReady() }
        if (usable.isEmpty()) {
            bos.flush(); bos.close()
            return false
        }
        val mdatEnd = position
        val moov = buildMoov(usable)
        writeRaw(moov)
        bos.flush()
        bos.close()

        // Patch the mdat largesize.
        RandomAccessFile(file, "rw").use { raf ->
            val mdatSize = mdatEnd - mdatHeaderPos
            raf.seek(mdatHeaderPos + 8)
            raf.write(longToBytes(mdatSize))
        }
        return true
    }

    override fun abort() {
        try { bos.close() } catch (_: Exception) {}
        file.delete()
    }

    private fun writeFtyp() {
        val b = BoxBuf()
        b.box("ftyp") {
            fourcc("isom"); u32(0x200)
            fourcc("isom"); fourcc("iso2"); fourcc("avc1"); fourcc("mp41")
        }
        writeRaw(b.toByteArray())
    }

    private fun writeRaw(bytes: ByteArray) {
        bos.write(bytes)
        position += bytes.size
    }

    private fun longToBytes(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (v ushr (56 - i * 8)).toByte()
        return out
    }

    // ---- moov construction ----

    private class TrackPlan(
        val track: Track,
        val trackId: Int,
        val firstCts: Long,
        val mediaDuration: Long,
        val emptyEditMovie: Long,
        val trackDurationMovie: Long
    )

    private fun buildMoov(tracks: List<Track>): ByteArray {
        // Normalize each track to start at media-time 0 and compute durations.
        var globalStartSec = Double.MAX_VALUE
        val baseInfo = tracks.map { t ->
            val base = t.samples[0].dts
            globalStartSec = minOf(globalStartSec, base.toDouble() / t.timescale)
            base
        }

        val plans = ArrayList<TrackPlan>()
        for ((idx, t) in tracks.withIndex()) {
            val base = baseInfo[idx]
            // durations from successive DTS deltas (repairing discontinuity gaps when enabled).
            assignSampleDurations(t.samples, repairTimeline)

            val mediaDuration = t.samples.sumOf { it.durationTicks }
            val firstCts = (t.samples[0].pts - t.samples[0].dts).coerceAtLeast(0)
            val startSec = base.toDouble() / t.timescale
            val emptyEditMovie = ((startSec - globalStartSec) * movieTimescale).toLong().coerceAtLeast(0)
            val mediaDurationMovie = mediaDuration * movieTimescale / t.timescale
            plans.add(TrackPlan(t, idx + 1, firstCts, mediaDuration, emptyEditMovie, emptyEditMovie + mediaDurationMovie))
        }
        val movieDuration = plans.maxOf { it.trackDurationMovie }

        val buf = BoxBuf()
        buf.box("moov") {
            writeMvhd(this, movieDuration, plans.size + 1)
            for (p in plans) writeTrak(this, p)
        }
        return buf.toByteArray()
    }

    private fun writeMvhd(buf: BoxBuf, duration: Long, nextTrackId: Int) {
        buf.box("mvhd") {
            fullBoxHeader(0, 0)
            u32(0); u32(0)                 // creation/modification time
            u32(movieTimescale)
            u32(duration)
            u32(0x00010000)                // rate 1.0
            u16(0x0100)                    // volume 1.0
            u16(0); u32(0); u32(0)         // reserved
            unityMatrix(this)
            repeat(6) { u32(0) }           // pre_defined
            u32(nextTrackId)
        }
    }

    private fun writeTrak(buf: BoxBuf, p: TrackPlan) {
        val t = p.track
        buf.box("trak") {
            box("tkhd") {
                fullBoxHeader(0, 0x000007)  // enabled | in movie | in preview
                u32(0); u32(0)
                u32(p.trackId)
                u32(0)                      // reserved
                u32(p.trackDurationMovie)
                u32(0); u32(0)              // reserved
                u16(0)                      // layer
                u16(0)                      // alternate_group
                u16(if (t.codec.isVideo) 0 else 0x0100) // volume
                u16(0)                      // reserved
                unityMatrix(this)
                u32((t.width.toLong() and 0xFFFF) shl 16)
                u32((t.height.toLong() and 0xFFFF) shl 16)
            }
            // Edit list: optional empty edit for A/V sync delay + composition (B-frame) trim.
            box("edts") {
                box("elst") {
                    val entries = ArrayList<Triple<Long, Long, Int>>() // segDur(movie), mediaTime(media), rate
                    if (p.emptyEditMovie > 0) entries.add(Triple(p.emptyEditMovie, -1L, 0x00010000))
                    val normalDur = p.mediaDuration * movieTimescale / t.timescale
                    entries.add(Triple(normalDur, p.firstCts, 0x00010000))
                    fullBoxHeader(0, 0)
                    u32(entries.size)
                    for ((seg, mt, rate) in entries) {
                        u32(seg)
                        u32(mt.toInt())     // version0 32-bit signed media_time
                        u32(rate)
                    }
                }
            }
            writeMdia(this, p)
        }
    }

    private fun writeMdia(buf: BoxBuf, p: TrackPlan) {
        val t = p.track
        buf.box("mdia") {
            box("mdhd") {
                fullBoxHeader(0, 0)
                u32(0); u32(0)
                u32(t.timescale)
                u32(p.mediaDuration)
                u16(0x55C4)                 // language 'und'
                u16(0)
            }
            box("hdlr") {
                fullBoxHeader(0, 0)
                u32(0)
                fourcc(if (t.codec.isVideo) "vide" else "soun")
                u32(0); u32(0); u32(0)
                fourcc(if (t.codec.isVideo) "VideoHandler" else "SoundHandler")
                u8(0)                       // trailing of the name + null
            }
            writeMinf(this, p)
        }
    }

    private fun writeMinf(buf: BoxBuf, p: TrackPlan) {
        val t = p.track
        buf.box("minf") {
            if (t.codec.isVideo) {
                box("vmhd") { fullBoxHeader(0, 1); u16(0); u16(0); u16(0); u16(0) }
            } else {
                box("smhd") { fullBoxHeader(0, 0); u16(0); u16(0) }
            }
            box("dinf") {
                box("dref") {
                    fullBoxHeader(0, 0)
                    u32(1)
                    box("url ") { fullBoxHeader(0, 1) } // self-contained
                }
            }
            writeStbl(this, p)
        }
    }

    private fun writeStbl(buf: BoxBuf, p: TrackPlan) {
        val t = p.track
        buf.box("stbl") {
            writeStsd(this, t)
            writeStts(this, t)
            writeCtts(this, t)
            if (t.codec.isVideo) writeStss(this, t)
            // stsc: one sample per chunk.
            box("stsc") {
                fullBoxHeader(0, 0)
                u32(1)
                u32(1); u32(1); u32(1)     // first_chunk, samples_per_chunk, sample_desc_index
            }
            // stsz
            box("stsz") {
                fullBoxHeader(0, 0)
                u32(0)                      // sample_size = 0 (varying)
                u32(t.samples.size)
                for (s in t.samples) u32(s.size.toLong())
            }
            // stco / co64
            val needs64 = t.samples.any { it.fileOffset > 0xFFFFFFFFL }
            if (needs64) {
                box("co64") {
                    fullBoxHeader(0, 0)
                    u32(t.samples.size)
                    for (s in t.samples) u64(s.fileOffset)
                }
            } else {
                box("stco") {
                    fullBoxHeader(0, 0)
                    u32(t.samples.size)
                    for (s in t.samples) u32(s.fileOffset)
                }
            }
        }
    }

    private fun writeStsd(buf: BoxBuf, t: Track) {
        buf.box("stsd") {
            fullBoxHeader(0, 0)
            u32(1)
            val entry = t.sampleEntryBox
            if (entry != null) {
                // Copy the input MP4's sample entry (avc1/mp4a/...) verbatim.
                bytes(entry)
                return@box
            }
            when (t.codec) {
                Codec.H264 -> visualSampleEntry(this, "avc1", t) { CodecBoxes.writeAvcC(this, t) }
                Codec.H265 -> visualSampleEntry(this, "hev1", t) { CodecBoxes.writeHvcC(this, t) }
                Codec.AAC -> audioSampleEntry(this, "mp4a", t) {
                    CodecBoxes.writeEsds(this, CodecBoxes.OTI_AAC, t.audioSpecificConfig)
                }
                Codec.MP3 -> audioSampleEntry(this, "mp4a", t) {
                    CodecBoxes.writeEsds(this, CodecBoxes.OTI_MP3, null)
                }
                Codec.AC3 -> audioSampleEntry(this, "ac-3", t) {
                    box("dac3") { bytes(t.decoderConfigRecord ?: ByteArray(3)) }
                }
                Codec.EAC3 -> audioSampleEntry(this, "ec-3", t) {
                    box("dec3") { bytes(t.decoderConfigRecord ?: ByteArray(5)) }
                }
                else -> {}
            }
        }
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
            u32((t.sampleRate.toLong() and 0xFFFF) shl 16)
            config()
        }
    }

    private fun writeStts(buf: BoxBuf, t: Track) {
        // Run-length encode equal durations.
        val counts = ArrayList<Long>()
        val deltas = ArrayList<Long>()
        for (s in t.samples) {
            val d = s.durationTicks
            if (deltas.isNotEmpty() && deltas.last() == d) {
                counts[counts.size - 1] = counts.last() + 1
            } else {
                deltas.add(d); counts.add(1)
            }
        }
        buf.box("stts") {
            fullBoxHeader(0, 0)
            u32(deltas.size)
            for (i in deltas.indices) { u32(counts[i]); u32(deltas[i]) }
        }
    }

    private fun writeCtts(buf: BoxBuf, t: Track) {
        val base = t.samples[0].dts
        var any = false
        val counts = ArrayList<Long>()
        val offsets = ArrayList<Int>()
        for (s in t.samples) {
            val cts = (s.pts - s.dts).toInt()
            if (cts != 0) any = true
            if (offsets.isNotEmpty() && offsets.last() == cts) {
                counts[counts.size - 1] = counts.last() + 1
            } else {
                offsets.add(cts); counts.add(1)
            }
        }
        if (!any) return
        buf.box("ctts") {
            fullBoxHeader(1, 0)             // version 1: signed offsets
            u32(offsets.size)
            for (i in offsets.indices) { u32(counts[i]); u32(offsets[i]) }
        }
        @Suppress("UNUSED_EXPRESSION") base
    }

    private fun writeStss(buf: BoxBuf, t: Track) {
        val keys = ArrayList<Int>()
        for (i in t.samples.indices) if (t.samples[i].isKeyframe) keys.add(i + 1)
        if (keys.isEmpty() || keys.size == t.samples.size) return // all sync -> omit
        buf.box("stss") {
            fullBoxHeader(0, 0)
            u32(keys.size)
            for (k in keys) u32(k.toLong())
        }
    }

    private fun unityMatrix(buf: BoxBuf) {
        buf.u32(0x00010000); buf.u32(0); buf.u32(0)
        buf.u32(0); buf.u32(0x00010000); buf.u32(0)
        buf.u32(0); buf.u32(0); buf.u32(0x40000000)
    }
}

/** A discontinuity gap is an inter-sample delta larger than this multiple of the typical frame. */
private const val DISCONTINUITY_GAP_FACTOR = 8

/**
 * Fills in each sample's [Sample.durationTicks] from successive DTS deltas.
 *
 * When [repairTimeline] is true (the manifest signalled a discontinuity), inter-sample gaps that
 * are negative or implausibly large — the artefact of a mid-stream timestamp reset — are replaced
 * with the track's typical (median) frame duration, so the output timeline stays monotonic and
 * continuous across the boundary. When false, behaviour is unchanged (negative gaps clamp to 0,
 * legitimate large gaps are preserved).
 */
internal fun assignSampleDurations(samples: List<Sample>, repairTimeline: Boolean) {
    val n = samples.size
    if (n == 0) return
    val base = samples[0].dts
    for (i in 0 until n) {
        val cur = samples[i].dts - base
        val durTo = if (i < n - 1) (samples[i + 1].dts - base) - cur else -1L
        samples[i].durationTicks = durTo
    }
    // Last sample: reuse previous duration (or 1) since there's no following DTS.
    if (n >= 2) samples[n - 1].durationTicks = samples[n - 2].durationTicks
    else samples[n - 1].durationTicks = 1

    if (repairTimeline) {
        val positives = samples.mapNotNull { if (it.durationTicks > 0) it.durationTicks else null }.sorted()
        if (positives.isNotEmpty()) {
            val typical = positives[positives.size / 2]
            val threshold = typical * DISCONTINUITY_GAP_FACTOR
            for (i in 0 until n) {
                val d = samples[i].durationTicks
                if (d < 0 || d > threshold) samples[i].durationTicks = typical
            }
        }
    }
    for (i in 0 until n) if (samples[i].durationTicks < 0) samples[i].durationTicks = 0
}
