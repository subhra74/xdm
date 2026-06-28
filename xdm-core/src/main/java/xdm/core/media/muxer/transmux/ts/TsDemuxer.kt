package xdm.core.media.muxer.transmux.ts

import xdm.core.media.muxer.transmux.es.Ac3Reader
import xdm.core.media.muxer.transmux.es.AdtsReader
import xdm.core.media.muxer.transmux.es.ByteArrayBuilder
import xdm.core.media.muxer.transmux.es.ElementaryStreamReader
import xdm.core.media.muxer.transmux.es.H264Reader
import xdm.core.media.muxer.transmux.es.H265Reader
import xdm.core.media.muxer.transmux.es.Mp3Reader
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track

/**
 * MPEG-2 Transport Stream demuxer. Feeds raw TS bytes (one or many segments, in order),
 * parses PAT/PMT to discover elementary streams, reassembles PES packets and dispatches them to
 * per-codec [ElementaryStreamReader]s. State (PMT, PES buffers, readers, timeline) persists across
 * [feed] calls so concatenated HLS segments form one continuous output.
 *
 * Supports 188- (TS), 192- (M2TS) and 204-byte (TS+FEC) packets.
 */
class TsDemuxer(private val sink: SampleSink) {

    private var packetSize = 188
    private var packetSizeResolved = false

    private var pmtPid = -1
    private val readersByPid = HashMap<Int, PesAssembler>()
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
            packetSizeResolved = true
        }

        var pos = alignToSync(data, 0, total)
        while (pos + packetSize <= total) {
            if ((data[pos].toInt() and 0xFF) != 0x47) {
                pos = alignToSync(data, pos, total)
                if (pos < 0) { return }
                continue
            }
            // M2TS prepends 4 bytes; the 0x47 sync is at the start of the 188-byte TS portion.
            val tsStart = if (packetSize == 192) pos + 4 else pos
            parsePacket(data, tsStart)
            pos += packetSize
        }
        // Stash remainder for next feed.
        val remaining = total - pos
        carryReset()
        if (remaining > 0) stash(data, pos, remaining)
    }

    /** Flush any buffered PES and let readers emit trailing samples. Call once after the last feed. */
    fun finish() {
        for (asm in readersByPid.values) {
            asm.flush()
            asm.reader.finish()
        }
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

    /** Tries 188/192/204 by checking that sync bytes recur at the candidate stride. */
    private fun detectPacketSize(data: ByteArray, total: Int): Int? {
        val candidates = intArrayOf(188, 192, 204)
        for (start in 0 until minOf(total, 188)) {
            if ((data[start].toInt() and 0xFF) != 0x47) continue
            for (size in candidates) {
                val syncOffset = if (size == 192) 4 else 0
                var ok = true
                var count = 0
                var p = start
                while (p + size < total && count < 5) {
                    if ((data[p + syncOffset].toInt() and 0xFF) != 0x47) { ok = false; break }
                    p += size
                    count++
                }
                if (ok && count >= 1) return size
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
            pid == 0 -> parsePat(data, p, payloadEnd, payloadUnitStart)
            pid == pmtPid -> parsePmt(data, p, payloadEnd, payloadUnitStart)
            readersByPid.containsKey(pid) ->
                readersByPid[pid]!!.consumePacket(data, p, payloadEnd, payloadUnitStart)
        }
    }

    private fun parsePat(data: ByteArray, start: Int, end: Int, pusi: Boolean) {
        if (pmtPid >= 0) return
        var p = start
        if (pusi) p += 1 + (data[p].toInt() and 0xFF) // pointer_field
        // table_id(1) + section header(2) + ... skip to program loop.
        if (p + 8 > end) return
        val sectionLength = ((data[p + 1].toInt() and 0x0F) shl 8) or (data[p + 2].toInt() and 0xFF)
        var loop = p + 8
        val sectionEnd = minOf(p + 3 + sectionLength - 4, end) // minus CRC32
        while (loop + 4 <= sectionEnd) {
            val programNumber = ((data[loop].toInt() and 0xFF) shl 8) or (data[loop + 1].toInt() and 0xFF)
            val pid = ((data[loop + 2].toInt() and 0x1F) shl 8) or (data[loop + 3].toInt() and 0xFF)
            if (programNumber != 0) { pmtPid = pid; return }
            loop += 4
        }
    }

    private fun parsePmt(data: ByteArray, start: Int, end: Int, pusi: Boolean) {
        if (readersByPid.isNotEmpty()) return // already configured
        var p = start
        if (pusi) p += 1 + (data[p].toInt() and 0xFF)
        if (p + 12 > end) return
        val sectionLength = ((data[p + 1].toInt() and 0x0F) shl 8) or (data[p + 2].toInt() and 0xFF)
        val sectionEnd = minOf(p + 3 + sectionLength - 4, end)
        val programInfoLength = ((data[p + 10].toInt() and 0x0F) shl 8) or (data[p + 11].toInt() and 0xFF)
        var esLoop = p + 12 + programInfoLength
        while (esLoop + 5 <= sectionEnd) {
            val streamType = data[esLoop].toInt() and 0xFF
            val esPid = ((data[esLoop + 1].toInt() and 0x1F) shl 8) or (data[esLoop + 2].toInt() and 0xFF)
            val esInfoLength = ((data[esLoop + 3].toInt() and 0x0F) shl 8) or (data[esLoop + 4].toInt() and 0xFF)
            val registration = readRegistration(data, esLoop + 5, esLoop + 5 + esInfoLength)
            val codec = StreamType.fromStreamType(streamType, registration)
            addReader(esPid, codec)
            esLoop += 5 + esInfoLength
        }
    }

    /** Scans an ES_info descriptor loop for a registration_descriptor (tag 0x05) format id. */
    private fun readRegistration(data: ByteArray, start: Int, end: Int): Int? {
        var d = start
        while (d + 2 <= end) {
            val tag = data[d].toInt() and 0xFF
            val len = data[d + 1].toInt() and 0xFF
            if (tag == 0x05 && len >= 4 && d + 2 + 4 <= end) {
                return ((data[d + 2].toInt() and 0xFF) shl 24) or
                    ((data[d + 3].toInt() and 0xFF) shl 16) or
                    ((data[d + 4].toInt() and 0xFF) shl 8) or
                    (data[d + 5].toInt() and 0xFF)
            }
            d += 2 + len
        }
        return null
    }

    private fun addReader(pid: Int, codec: Codec) {
        if (readersByPid.containsKey(pid)) return
        val reader: ElementaryStreamReader = when (codec) {
            Codec.H264 -> H264Reader(sink)
            Codec.H265 -> H265Reader(sink)
            Codec.AAC -> AdtsReader(sink)
            Codec.AC3 -> Ac3Reader(sink, eac3 = false)
            Codec.EAC3 -> Ac3Reader(sink, eac3 = true)
            Codec.MP3 -> Mp3Reader(sink)
            else -> throw UnsupportedCodecException("Unsupported codec in TS stream: $codec (pid=$pid)")
        }
        readersByPid[pid] = PesAssembler(reader)
        tracks.add(reader.track)
    }
}
