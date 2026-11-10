// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.ts

import xdm.core.media.muxer.transmux.es.Ac3Reader
import xdm.core.media.muxer.transmux.es.AdtsReader
import xdm.core.media.muxer.transmux.es.ByteArrayBuilder
import xdm.core.media.muxer.transmux.es.ElementaryStreamReader
import xdm.core.media.muxer.transmux.es.H264Reader
import xdm.core.media.muxer.transmux.es.H265Reader
import xdm.core.media.muxer.transmux.es.MpegVideoReader
import xdm.core.media.muxer.transmux.es.LatmReader
import xdm.core.media.muxer.transmux.es.DtsReader
import xdm.core.media.muxer.transmux.es.MpegAudioReader
import xdm.core.media.muxer.transmux.es.OpusReader
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.util.Logger

/**
 * MPEG-2 Transport Stream demuxer. Feeds raw TS bytes (one or many segments, in order),
 * parses PAT/PMT to discover elementary streams, reassembles PES packets and dispatches them to
 * per-codec [ElementaryStreamReader]s. State (PMT, PES buffers, readers, timeline) persists across
 * [feed] calls so concatenated HLS segments form one continuous output.
 *
 * Segments after an HLS discontinuity often come from another encoder with other PIDs (and the
 * same table version), so PAT and PMT are re-read whenever their content changes: a stream on a new
 * PID continues the existing track of the same codec, and only a genuinely additional stream adds
 * a track.
 *
 * Supports 188- (TS), 192- (M2TS: a 4-byte timestamp header before each sync byte) and
 * 204-byte (TS+FEC) packets.
 */
class TsDemuxer(private val sink: SampleSink) {

    private var packetSize = 188
    /** Bytes before the 0x47 sync byte in each packet (4 for M2TS). */
    private var syncOffset = 0
    private var packetSizeResolved = false

    private var pmtPid = -1
    /** ES loop bytes of the PMT in effect, to notice when a new one differs. */
    private var pmtEsLoop: ByteArray? = null
    private val readersByPid = HashMap<Int, PesAssembler>()
    private val kindByPid = HashMap<Int, StreamReader>()
    /** Every reader created, in creation order; one per output track. */
    private val readers = ArrayList<Pair<StreamReader, ElementaryStreamReader>>()
    /** PIDs already reported as skipped. */
    private val loggedPids = HashSet<Int>()
    /** Opus channel_config_code of the PMT being applied (for a new Opus reader). */
    private var opusChannelConfig: Int? = null
    /** Tracks in PMT declaration order, for stable output ordering. */
    val tracks = ArrayList<Track>()

    private val carry = ByteArrayBuilder(256 * 1024)

    /** Feeds a chunk of TS bytes. Buffers any trailing partial packet until the next [feed]. */
    fun feed(input: ByteArray, length: Int) {
        // Prepend any carried-over partial packet.
        val data: ByteArray
        val total: Int
        if (carry.size > 0) {
            carry.write(input, 0, length)
            data = carry.buffer
            total = carry.size
        } else {
            data = input
            total = length
        }

        if (!packetSizeResolved) {
            packetSize = detectPacketSize(data, total) ?: run {
                stash(data, 0, total); return
            }
            syncOffset = if (packetSize == 192) 4 else 0
            packetSizeResolved = true
        }

        // pos is a packet start; its sync byte sits at pos + syncOffset.
        var pos = alignToSync(data, syncOffset, total) - syncOffset
        while (pos + packetSize <= total) {
            if ((data[pos + syncOffset].toInt() and 0xFF) != 0x47) {
                pos = alignToSync(data, pos + syncOffset + 1, total) - syncOffset
                continue
            }
            parsePacket(data, pos + syncOffset)
            pos += packetSize
        }
        // Stash remainder for next feed.
        val remaining = total - pos
        carryReset()
        if (remaining > 0) stash(data, pos, remaining)
    }

    /** Flush any buffered PES and let readers emit trailing samples. Call once after the last feed. */
    fun finish() {
        for (asm in readersByPid.values.toList()) asm.flush()
        for ((_, reader) in readers) reader.finish()
    }

    private fun carryReset() {
        // Move carry back to empty without losing the backing array.
        carry.reset()
    }

    private fun stash(data: ByteArray, offset: Int, length: Int) {
        // carry currently holds the same bytes if it was the source; rebuild cleanly.
        if (data === carry.buffer) {
            val tmp = data.copyOfRange(offset, offset + length)
            carry.reset()
            carry.write(tmp, 0, length)
        } else {
            carry.reset()
            carry.write(data, offset, length)
        }
    }

    private fun alignToSync(data: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i < end) {
            if ((data[i].toInt() and 0xFF) == 0x47) return i
            i++
        }
        return end
    }

    /**
     * Tries 188/192/204 by checking that sync bytes recur at the candidate stride (at least
     * [SYNC_CONFIRMATIONS] times). Null when there is not enough data yet to tell.
     */
    private fun detectPacketSize(data: ByteArray, total: Int): Int? {
        val candidates = intArrayOf(188, 192, 204)
        for (start in 0 until minOf(total, 204 + 4)) {
            if ((data[start].toInt() and 0xFF) != 0x47) continue
            for (size in candidates) {
                // M2TS: the packet begins 4 bytes before its sync byte.
                if (size == 192 && start < 4) continue
                var count = 0
                var p = start + size
                while (p < total && count < SYNC_CONFIRMATIONS && (data[p].toInt() and 0xFF) == 0x47) {
                    p += size
                    count++
                }
                if (count >= SYNC_CONFIRMATIONS) return size
            }
        }
        return null
    }

    private fun parsePacket(data: ByteArray, off: Int) {
        if ((data[off].toInt() and 0xFF) != 0x47) return
        val payloadUnitStart = (data[off + 1].toInt() and 0x40) != 0
        val pid = ((data[off + 1].toInt() and 0x1F) shl 8) or (data[off + 2].toInt() and 0xFF)
        val adaptationControl = (data[off + 3].toInt() and 0x30) shr 4
        val hasAdaptation = adaptationControl == 2 || adaptationControl == 3
        val hasPayload = adaptationControl == 1 || adaptationControl == 3

        var p = off + 4
        if (hasAdaptation) {
            val adaptationLen = data[p].toInt() and 0xFF
            p += 1 + adaptationLen
        }
        if (!hasPayload) return
        val payloadEnd = off + 188
        if (p >= payloadEnd) return

        when {
            pid == 0 -> patSection.feed(data, p, payloadEnd, payloadUnitStart)
            pid == pmtPid -> pmtSection.feed(data, p, payloadEnd, payloadUnitStart)
            readersByPid.containsKey(pid) ->
                readersByPid[pid]!!.consumePacket(data, p, payloadEnd, payloadUnitStart)
        }
    }

    /**
     * Reassembles PSI sections (PAT, PMT) that may span several TS packets, as ffmpeg does: a PMT
     * with many streams or descriptors (DVB restreams) does not fit in one packet.
     */
    private class SectionAssembler(private val onSection: (ByteArray) -> Unit) {
        private val buf = ByteArrayBuilder(1024)
        private var collecting = false

        fun feed(data: ByteArray, start: Int, end: Int, pusi: Boolean) {
            var p = start
            if (pusi) {
                val pointer = data[p].toInt() and 0xFF
                p++
                // Bytes before the pointer end the previous section.
                if (collecting && pointer > 0) append(data, p, minOf(p + pointer, end))
                p += pointer
                buf.reset()
                collecting = true
            } else if (!collecting) {
                return
            }
            append(data, p, end)
        }

        private fun append(data: ByteArray, from: Int, to: Int) {
            if (from >= to) return
            buf.write(data, from, to - from)
            // One or more complete sections may now be buffered (stuffing 0xFF ends the run).
            while (buf.size >= 3) {
                val b = buf.buffer
                if ((b[0].toInt() and 0xFF) == 0xFF) { buf.reset(); collecting = false; return }
                val length = 3 + (((b[1].toInt() and 0x0F) shl 8) or (b[2].toInt() and 0xFF))
                if (buf.size < length) return
                onSection(b.copyOf(length))
                buf.compact(length)
            }
        }
    }

    private val patSection = SectionAssembler { parsePat(it) }
    private val pmtSection = SectionAssembler { parsePmt(it) }

    /** [s] is a whole section from its table_id. */
    private fun parsePat(s: ByteArray) {
        if (s.size < 12 || (s[0].toInt() and 0xFF) != 0x00) return
        if ((s[5].toInt() and 0x01) == 0) return // current_next_indicator: not yet applicable
        var loop = 8
        val sectionEnd = s.size - 4 // minus CRC32
        while (loop + 4 <= sectionEnd) {
            val programNumber = ((s[loop].toInt() and 0xFF) shl 8) or (s[loop + 1].toInt() and 0xFF)
            val pid = ((s[loop + 2].toInt() and 0x1F) shl 8) or (s[loop + 3].toInt() and 0xFF)
            if (programNumber != 0) {
                if (pid != pmtPid) {
                    if (pmtPid >= 0) Logger.info("XDM", "TS: PMT moved from pid=$pmtPid to pid=$pid")
                    pmtPid = pid
                    pmtEsLoop = null
                }
                return
            }
            loop += 4
        }
    }

    private fun parsePmt(s: ByteArray) {
        if (s.size < 16 || (s[0].toInt() and 0xFF) != 0x02) return
        if ((s[5].toInt() and 0x01) == 0) return // current_next_indicator
        val sectionEnd = s.size - 4
        val programInfoLength = ((s[10].toInt() and 0x0F) shl 8) or (s[11].toInt() and 0xFF)
        val loopStart = 12 + programInfoLength
        if (loopStart > sectionEnd) return
        // PMTs repeat in every segment: only a changed program needs work.
        val program = s.copyOfRange(10, sectionEnd)
        if (pmtEsLoop?.contentEquals(program) == true) return
        val firstPmt = pmtEsLoop == null && readers.isEmpty()
        pmtEsLoop = program
        // A program-level registration (HDMV/HDPR for Blu-ray) changes what some stream types mean.
        val programRegistration = readEsDescriptors(s, 12, loopStart).registration

        val streams = ArrayList<Triple<Int, Int, EsDescriptors>>() // stream_type, pid, descriptors
        var esLoopPos = loopStart
        while (esLoopPos + 5 <= sectionEnd) {
            val streamType = s[esLoopPos].toInt() and 0xFF
            val esPid = ((s[esLoopPos + 1].toInt() and 0x1F) shl 8) or (s[esLoopPos + 2].toInt() and 0xFF)
            val esInfoLength = ((s[esLoopPos + 3].toInt() and 0x0F) shl 8) or (s[esLoopPos + 4].toInt() and 0xFF)
            val info = readEsDescriptors(s, esLoopPos + 5, minOf(esLoopPos + 5 + esInfoLength, sectionEnd))
            streams.add(Triple(streamType, esPid, info))
            esLoopPos += 5 + esInfoLength
        }
        if (!firstPmt) Logger.info("XDM", "TS: PMT changed; remapping ${streams.size} streams")
        applyPmt(streams, programRegistration)
    }

    /**
     * Points each PID of a new PMT at a reader. A PID keeps its reader if its kind is unchanged;
     * otherwise the stream continues the first existing reader of the same kind not claimed by
     * another PID of this PMT; only when there is none does it get a new reader (and track).
     */
    private fun applyPmt(streams: List<Triple<Int, Int, EsDescriptors>>, programRegistration: Int?) {
        val newByPid = HashMap<Int, PesAssembler>()
        val newKindByPid = HashMap<Int, StreamReader>()
        val claimed = HashSet<ElementaryStreamReader>()
        val pending = ArrayList<Triple<Int, StreamReader, Int?>>() // pid, reader, Opus channel config
        for ((streamType, pid, info) in streams) {
            when (val m = StreamType.map(streamType, programRegistration, info.registration, info.dvbAudio, info.hasOther)) {
                is StreamMapping.Skip -> {
                    if (pid !in loggedPids) {
                        loggedPids += pid
                        if (m.unsupported != null) {
                            Logger.error("XDM", "TS: ${m.unsupported} (pid=$pid, stream_type=0x${Integer.toHexString(streamType)}) can't be muxed; leaving it out")
                        } else {
                            Logger.info("XDM", "TS: skipping non-A/V stream pid=$pid stream_type=0x${Integer.toHexString(streamType)}")
                        }
                    }
                }
                is StreamMapping.Probe -> {
                    newByPid[pid] = PesAssembler(ProbeReader(pid, m.fallback))
                }
                is StreamMapping.Read -> {
                    val asm = readersByPid[pid]
                    if (asm != null && kindByPid[pid] == m.reader && asm.reader !in claimed) {
                        newByPid[pid] = asm; newKindByPid[pid] = m.reader; claimed += asm.reader
                    } else {
                        pending += Triple(pid, m.reader, info.opusChannelConfig)
                    }
                }
            }
        }
        for ((pid, kind, opusConfig) in pending) {
            opusChannelConfig = opusConfig
            val reader = readerFor(kind, claimed)
            claimed += reader
            newByPid[pid] = PesAssembler(reader)
            newKindByPid[pid] = kind
        }
        // Hand over cleanly: whatever a dropped or re-pointed PID still buffers belongs to the old stream.
        for ((pid, asm) in readersByPid.entries.toList()) if (newByPid[pid] !== asm) asm.flush()
        readersByPid.clear(); readersByPid.putAll(newByPid)
        kindByPid.clear(); kindByPid.putAll(newKindByPid)
    }

    /** The first existing reader of [kind] not in [claimed], else a new one (and track). */
    private fun readerFor(kind: StreamReader, claimed: Set<ElementaryStreamReader>): ElementaryStreamReader =
        readers.firstOrNull { (k, r) -> k == kind && r !in claimed }?.second
            ?: createReader(kind).also {
                readers += kind to it
                tracks.add(it.track)
            }

    /** What we use from a descriptor loop. */
    private class EsDescriptors(
        val registration: Int?,
        val opusChannelConfig: Int?,
        val dvbAudio: StreamReader?,
        /** Any descriptor other than registration/language (DVB subtitles, teletext, ...). */
        val hasOther: Boolean,
    )

    /**
     * Scans a descriptor loop for the registration_descriptor (tag 0x05) format id, the Opus
     * channel_config_code (extension descriptor 0x7F/0x80) and the DVB AC-3/E-AC-3/DTS descriptors.
     */
    private fun readEsDescriptors(data: ByteArray, start: Int, end: Int): EsDescriptors {
        var registration: Int? = null
        var opusChannelConfig: Int? = null
        var dvbAudio: StreamReader? = null
        var hasOther = false
        var d = start
        while (d + 2 <= end) {
            val tag = data[d].toInt() and 0xFF
            val len = data[d + 1].toInt() and 0xFF
            if (d + 2 + len > end) break
            when {
                tag == 0x05 && len >= 4 && registration == null ->
                    registration = ((data[d + 2].toInt() and 0xFF) shl 24) or
                        ((data[d + 3].toInt() and 0xFF) shl 16) or
                        ((data[d + 4].toInt() and 0xFF) shl 8) or
                        (data[d + 5].toInt() and 0xFF)
                tag == 0x7F && len >= 2 && (data[d + 2].toInt() and 0xFF) == 0x80 ->
                    opusChannelConfig = data[d + 3].toInt() and 0xFF
                tag == 0x6A -> dvbAudio = StreamReader.AC3   // DVB AC-3_descriptor
                tag == 0x7A -> dvbAudio = StreamReader.EAC3  // DVB enhanced_AC-3_descriptor
                tag == 0x7B -> dvbAudio = StreamReader.DTS   // DVB DTS_descriptor
                tag != 0x0A -> hasOther = true               // 0x0A: ISO 639 language
            }
            d += 2 + len
        }
        return EsDescriptors(registration, opusChannelConfig, dvbAudio, hasOther)
    }

    /** Stands in for the reader of a stream whose codec its first PES payloads have to show. */
    private inner class ProbeReader(val pid: Int, val fallback: StreamReader?) : ElementaryStreamReader {
        override val track = Track(Codec.UNKNOWN)
        var attempts = 0
        override fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) =
            identify(pid, this, data, offset, length, pts, dts)
        override fun finish() {}
    }

    private fun identify(pid: Int, probe: ProbeReader, data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long) {
        val asm = readersByPid[pid]?.takeIf { it.reader === probe } ?: return
        probe.attempts++
        // A PES may start inside a frame: give the probe a few PES before falling back.
        val kind = sniffAudio(data, offset, length) ?: if (probe.attempts < PROBE_PES) return else probe.fallback
        if (kind == null) {
            Logger.info("XDM", "TS: skipping unidentified private stream pid=$pid")
            readersByPid.remove(pid)
            kindByPid.remove(pid)
            return
        }
        Logger.info("XDM", "TS: stream pid=$pid identified as $kind")
        val reader = readerFor(kind, readersByPid.values.map { it.reader }.toSet())
        asm.reader = reader
        kindByPid[pid] = kind
        reader.consume(data, offset, length, pts, dts)
    }

    /** Audio codec from the first bytes of a PES payload, or null if they don't say. */
    private fun sniffAudio(data: ByteArray, offset: Int, length: Int): StreamReader? {
        if (length < 6) return null
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        return when {
            b0 == 0xFF && (b1 and 0xF6) == 0xF0 -> StreamReader.ADTS                       // ADTS: layer 00
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x06) != 0 -> StreamReader.MPEG_AUDIO
            b0 == 0x56 && (b1 and 0xE0) == 0xE0 -> StreamReader.LATM                       // LOAS sync 0x2B7
            b0 == 0x0B && b1 == 0x77 ->                                                    // AC-3 family: bsid
                if (((data[offset + 5].toInt() and 0xFF) shr 3) > 10) StreamReader.EAC3 else StreamReader.AC3
            b0 == 0x7F && b1 == 0xFE && (data[offset + 2].toInt() and 0xFF) == 0x80 &&
                (data[offset + 3].toInt() and 0xFF) == 0x01 -> StreamReader.DTS
            else -> null
        }
    }

    private fun createReader(kind: StreamReader): ElementaryStreamReader = when (kind) {
        StreamReader.H264 -> H264Reader(sink)
        StreamReader.H265 -> H265Reader(sink)
        StreamReader.ADTS -> AdtsReader(sink)
        StreamReader.LATM -> LatmReader(sink)
        StreamReader.AC3 -> Ac3Reader(sink, eac3 = false)
        StreamReader.EAC3 -> Ac3Reader(sink, eac3 = true)
        StreamReader.MPEG_AUDIO -> MpegAudioReader(sink)
        StreamReader.OPUS -> OpusReader(sink, opusChannelConfig)
        StreamReader.MPEG_VIDEO -> MpegVideoReader(sink, mpeg4 = false)
        StreamReader.MPEG4_VISUAL -> MpegVideoReader(sink, mpeg4 = true)
        StreamReader.DTS -> DtsReader(sink)
    }

    private companion object {
        /** Sync bytes that must recur at a packet size's stride before it is accepted. */
        const val SYNC_CONFIRMATIONS = 3
        /** PES payloads a probe looks at before falling back. */
        const val PROBE_PES = 4
    }
}
