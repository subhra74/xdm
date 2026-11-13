// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.impl

import xdm.core.util.Logger
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Merges two renditions into one MPEG-TS without knowing their codecs: a fallback for when a
 * remux would leave a stream out. The primary rendition (the video) is a list of TS files; the
 * secondary (the audio) is either TS files or HLS packed audio, whose frames are wrapped into PES.
 *
 *  - Packets of 188/192/204 bytes in, 188 out. The primary keeps everything but its PAT/PMT (and
 *    null packets); the secondary keeps only its audio/video PIDs, remapped when they collide with
 *    the primary's (packagers put both renditions on PID 0x100).
 *  - A PAT and a merged PMT (the primary's program plus the secondary's streams, CRC computed) are
 *    written first and after every PAT the primary carries, and again when the primary's PMT changes.
 *  - Packets are grouped into units at each PES start and the two renditions are interleaved by
 *    (section, timestamp): renditions share one clock, but timestamps restart at each discontinuity,
 *    so the section comes first — the playlist's, else counted from timestamp jumps. PTS/DTS/PCR are
 *    copied as they are; 33-bit wraps are unwrapped only for the ordering.
 */
internal class TsMerge(private val stop: AtomicBoolean) {

    /** A segment file: its media starts [from] bytes in; [section] from the playlist, null if unknown. */
    class Input(val path: String, val from: Long, val section: Int?)

    private class Unit(val section: Int, val ts: Long, val packets: List<ByteArray>)

    /** Merges into [out]; false only on a stop. */
    fun merge(primary: List<Input>, secondary: List<Input>, secondaryPacked: Boolean, out: OutputStream): Boolean {
        val program = Program()
        val p = TsUnits(primary, primary = true, program)
        val s: () -> Unit? = if (secondaryPacked) PackedUnits(secondary, program) { p.firstTs }::next
            else TsUnits(secondary, primary = false, program)::next
        var pu = p.next()
        var su = s()
        // Both renditions' first units are read: their PMTs are known, the merged one can be built.
        for (pkt in program.patPackets()) out.write(pkt)
        for (pkt in program.pmtPackets()) out.write(pkt)
        var units = 0L
        while (pu != null || su != null) {
            if (stop.get()) return false
            val takePrimary = su == null || (pu != null && (pu.section < su.section || (pu.section == su.section && pu.ts <= su.ts)))
            if (takePrimary) {
                for (pkt in pu!!.packets) {
                    // The primary's PATs become ours (one continuity count on PID 0), each with the merged PMT.
                    if (pid(pkt) == 0) {
                        for (pat in program.patPackets()) out.write(pat)
                        for (pmt in program.pmtPackets()) out.write(pmt)
                    } else out.write(pkt)
                }
                pu = p.next()
            } else {
                for (pkt in su!!.packets) out.write(pkt)
                su = s()
            }
            if (program.changed) { program.changed = false; for (pmt in program.pmtPackets()) out.write(pmt) }
            units++
        }
        Logger.info("XDM", "TS merge: $units units, secondary PIDs ${program.remap.entries.joinToString { "0x%x->0x%x".format(it.key, it.value) }}")
        return true
    }

    // ---- the merged program ----

    /** The output PAT/PMT: the primary's program with the secondary's streams added under their PIDs. */
    private class Program {
        var programNumber = 1
        var pmtPid = 0x1000
        var pcrPid = 0x1FFF
        /** The primary's program_info descriptors and ES loop entries (stream_type, pid, descriptors). */
        var programInfo = ByteArray(0)
        var primaryStreams: List<Triple<Int, Int, ByteArray>> = emptyList()
        var secondaryStreams: List<Triple<Int, Int, ByteArray>> = emptyList()
        /** Secondary PID -> output PID. */
        val remap = LinkedHashMap<Int, Int>()
        var version = 0
        var changed = false
        private var pmtCc = 0
        private var patCc = 0

        private fun usedPids(): Set<Int> = setOf(0, 1, 2, 0x11, 0x12, 0x14, 0x1FFF, pmtPid, pcrPid) + primaryStreams.map { it.second }

        fun outPid(secondaryPid: Int): Int = remap.getOrPut(secondaryPid) {
            val used = usedPids() + remap.values
            var pid = if (secondaryPid !in used) secondaryPid else 0x101
            while (pid in used) pid++
            pid
        }

        fun setPrimary(number: Int, pmt: Int, pcr: Int, info: ByteArray, streams: List<Triple<Int, Int, ByteArray>>) {
            if (number == programNumber && pmt == pmtPid && pcr == pcrPid && info.contentEquals(programInfo) && sameStreams(streams, primaryStreams)) return
            programNumber = number; pmtPid = pmt; pcrPid = pcr; programInfo = info; primaryStreams = streams
            version = (version + 1) and 0x1F; changed = true
        }

        fun setSecondary(streams: List<Triple<Int, Int, ByteArray>>) {
            val mapped = streams.map { (type, pid, info) -> Triple(type, outPid(pid), info) }
            if (sameStreams(mapped, secondaryStreams)) return
            secondaryStreams = mapped
            version = (version + 1) and 0x1F; changed = true
        }

        private fun sameStreams(a: List<Triple<Int, Int, ByteArray>>, b: List<Triple<Int, Int, ByteArray>>) =
            a.size == b.size && a.indices.all { a[it].first == b[it].first && a[it].second == b[it].second && a[it].third.contentEquals(b[it].third) }

        fun patPackets(): List<ByteArray> {
            val body = byteArrayOf(0, 1, 0xC1.toByte(), 0, 0, (programNumber shr 8).toByte(), programNumber.toByte(),
                (0xE0 or (pmtPid shr 8)).toByte(), pmtPid.toByte())
            return packetize(0x00, section(0x00, body), patCc).also { patCc += it.size }
        }

        fun pmtPackets(): List<ByteArray> {
            val b = java.io.ByteArrayOutputStream()
            b.write(programNumber shr 8); b.write(programNumber)
            b.write(0xC1 or (version shl 1)); b.write(0); b.write(0)
            b.write(0xE0 or (pcrPid shr 8)); b.write(pcrPid)
            b.write(0xF0 or (programInfo.size shr 8)); b.write(programInfo.size); b.write(programInfo)
            for ((type, pid, info) in primaryStreams + secondaryStreams) {
                b.write(type); b.write(0xE0 or (pid shr 8)); b.write(pid)
                b.write(0xF0 or (info.size shr 8)); b.write(info.size); b.write(info)
            }
            return packetize(pmtPid, section(0x02, b.toByteArray()), pmtCc).also { pmtCc += it.size }
        }

        /** A PSI section: table id, section_length, [body] (from table_id_extension on), CRC32. */
        private fun section(tableId: Int, body: ByteArray): ByteArray {
            val length = body.size + 4
            val head = byteArrayOf(tableId.toByte(), (0xB0 or (length shr 8)).toByte(), length.toByte())
            val sec = head + body
            val crc = crc32(sec)
            return sec + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        }

        /** A section split into TS packets on [pid] (pointer field in the first), padded with 0xFF. */
        private fun packetize(pid: Int, sec: ByteArray, cc: Int): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            var pos = 0
            var n = 0
            while (pos < sec.size || n == 0) {
                val pkt = ByteArray(188) { 0xFF.toByte() }
                pkt[0] = 0x47
                pkt[1] = ((if (n == 0) 0x40 else 0) or (pid shr 8)).toByte()
                pkt[2] = pid.toByte()
                pkt[3] = (0x10 or ((cc + n) and 0x0F)).toByte()
                var p = 4
                if (n == 0) pkt[p++] = 0
                val take = minOf(188 - p, sec.size - pos)
                System.arraycopy(sec, pos, pkt, p, take)
                pos += take; n++
                out.add(pkt)
            }
            return out
        }
    }

    // ---- TS renditions ----

    /** A TS rendition read as units: each starts at a PES start (with a timestamp) of one of its streams. */
    private class TsUnits(private val inputs: List<Input>, private val primary: Boolean, private val program: Program) {
        private val packets = PacketReader(inputs)
        private var pmtPid = -1
        private var esPids = emptySet<Int>()
        private val clock = SectionClock()
        private var pending = ArrayList<ByteArray>()
        private var current: ArrayList<ByteArray>? = null
        private var currentSection = 0
        private var currentTs = 0L
        private val psi = HashMap<Int, java.io.ByteArrayOutputStream>()
        var firstTs: Long? = null
            private set

        fun next(): Unit? {
            while (true) {
                val pkt = packets.next() ?: return finishCurrent()
                val pid = pid(pkt)
                if (pid == 0x1FFF) continue
                if (pid == 0) {
                    psiPacket(pkt, pid)?.let { readPat(it) }
                    if (primary) add(pkt)
                    continue
                }
                if (pid == pmtPid) {
                    psiPacket(pkt, pid)?.let { readPmt(it) }
                    continue // replaced by the merged PMT
                }
                if (!primary && pid !in esPids) continue
                if (!primary) {
                    val out = program.outPid(pid)
                    pkt[1] = ((pkt[1].toInt() and 0xE0) or (out shr 8)).toByte(); pkt[2] = out.toByte()
                }
                val ts = if (pid in esPids && pkt[1].toInt() and 0x40 != 0) pesTimestamp(pkt) else null
                if (ts != null) {
                    val done = finishCurrent()
                    val (section, unwrapped) = clock.place(ts, packets.section)
                    if (firstTs == null) firstTs = ts
                    current = ArrayList<ByteArray>().also { it.addAll(pending); it.add(pkt) }
                    pending = ArrayList()
                    currentSection = section; currentTs = unwrapped
                    if (done != null) return done
                } else add(pkt)
            }
        }

        private fun add(pkt: ByteArray) { (current ?: pending).add(pkt) }

        private fun finishCurrent(): Unit? {
            val c = current ?: return if (pending.isNotEmpty() && primary) Unit(currentSection, currentTs, pending.also { pending = ArrayList() }) else null
            current = null
            return Unit(currentSection, currentTs, c)
        }

        /** Collects a PSI section over packets; returns it when complete. */
        private fun psiPacket(pkt: ByteArray, pid: Int): ByteArray? {
            var p = payloadStart(pkt) ?: return null
            if (pkt[1].toInt() and 0x40 != 0) {
                p += 1 + (pkt[p].toInt() and 0xFF)
                psi[pid] = java.io.ByteArrayOutputStream()
            }
            val buf = psi[pid] ?: return null
            if (p < 188) buf.write(pkt, p, 188 - p)
            val b = buf.toByteArray()
            if (b.size < 3) return null
            val len = 3 + (((b[1].toInt() and 0x0F) shl 8) or (b[2].toInt() and 0xFF))
            if (b.size < len) return null
            psi.remove(pid)
            return b.copyOf(len)
        }

        private fun readPat(s: ByteArray) {
            if (s.size < 16 || s[0].toInt() != 0) return
            var q = 8
            while (q + 4 <= s.size - 4) {
                val number = ((s[q].toInt() and 0xFF) shl 8) or (s[q + 1].toInt() and 0xFF)
                val pid = ((s[q + 2].toInt() and 0x1F) shl 8) or (s[q + 3].toInt() and 0xFF)
                if (number != 0) {
                    pmtPid = pid
                    if (primary) { program.programNumber = number }
                    return
                }
                q += 4
            }
        }

        private fun readPmt(s: ByteArray) {
            if (s.size < 16 || s[0].toInt() != 2) return
            val number = ((s[3].toInt() and 0xFF) shl 8) or (s[4].toInt() and 0xFF)
            val pcr = ((s[8].toInt() and 0x1F) shl 8) or (s[9].toInt() and 0xFF)
            val infoLen = ((s[10].toInt() and 0x0F) shl 8) or (s[11].toInt() and 0xFF)
            val streams = ArrayList<Triple<Int, Int, ByteArray>>()
            var q = 12 + infoLen
            val end = s.size - 4
            while (q + 5 <= end) {
                val len = ((s[q + 3].toInt() and 0x0F) shl 8) or (s[q + 4].toInt() and 0xFF)
                streams.add(Triple(s[q].toInt() and 0xFF, ((s[q + 1].toInt() and 0x1F) shl 8) or (s[q + 2].toInt() and 0xFF),
                    s.copyOfRange(q + 5, minOf(q + 5 + len, end))))
                q += 5 + len
            }
            esPids = streams.map { it.second }.toSet()
            if (primary) program.setPrimary(number, pmtPid, pcr, s.copyOfRange(12, 12 + infoLen), streams)
            else program.setSecondary(streams)
        }
    }

    // ---- packed audio renditions ----

    /** HLS packed audio wrapped into PES: a unit per ~100 ms of frames, timed from the segment's ID3 timestamp. */
    private class PackedUnits(private val inputs: List<Input>, private val program: Program, private val start: () -> Long?) {
        private var file = 0
        private var data = ByteArray(0)
        private var pos = 0
        private var pts = 0L
        private var samplesIntoSegment = 0L
        /** The playlist section of the segment being read; null when unknown (sections from timestamp jumps). */
        private var section: Int? = null
        private var nextPts: Long? = null
        private var pid = -1
        private var cc = 0
        private var kind: AudioFrames.Kind? = null
        private val clock = SectionClock()

        fun next(): Unit? {
            while (true) {
                if (pos >= data.size && !openNext()) return null
                val first = AudioFrames.at(data, pos)
                if (first == null) { pos++; continue } // junk between frames
                if (kind == null) declare(first.kind)
                val unitStart = pos
                val unitPts = pts + samplesIntoSegment * 90000 / first.sampleRate
                var samples = 0L
                while (pos < data.size && samples * 1000 < 100L * first.sampleRate && pos - unitStart < 60000) {
                    val f = AudioFrames.at(data, pos) ?: break
                    if (pos + f.length > data.size) { pos = data.size; break }
                    pos += f.length
                    samples += f.samples
                }
                if (pos == unitStart) { pos++; continue }
                samplesIntoSegment += samples
                nextPts = pts + samplesIntoSegment * 90000 / first.sampleRate
                val (sec, unwrapped) = clock.place(unitPts and 0x1FFFFFFFFL, section)
                return Unit(sec, unwrapped, pes(data, unitStart, pos - unitStart, unitPts and 0x1FFFFFFFFL))
            }
        }

        private fun declare(k: AudioFrames.Kind) {
            kind = k
            pid = program.outPid(0x100)
            val info = k.registration?.let { byteArrayOf(0x05, 4) + it.toByteArray(Charsets.US_ASCII) } ?: ByteArray(0)
            // The packed audio has no PMT: its one stream is declared directly (already on its output PID).
            program.secondaryStreams = listOf(Triple(k.streamType, pid, info))
            program.changed = true
        }

        private fun openNext(): Boolean {
            if (file >= inputs.size) return false
            val input = inputs[file++]
            RandomAccessFile(input.path, "r").use { raf -> data = ByteArray(raf.length().toInt()); raf.readFully(data) }
            pos = input.from.toInt()
            // The segment's timestamp: the ID3 PRIV `com.apple.streaming.transportStreamTimestamp` (RFC 8216 3.4).
            val id3 = id3Timestamp(data, pos)
            if (id3 != null) { pts = id3.first; pos = id3.second }
            else { pos = skipId3(data, pos); pts = nextPts ?: start() ?: 0L }
            samplesIntoSegment = 0
            section = input.section
            return true
        }

        private fun pes(d: ByteArray, from: Int, len: Int, pts: Long): List<ByteArray> {
            val header = ByteArray(14)
            header[0] = 0; header[1] = 0; header[2] = 1; header[3] = kind!!.streamId.toByte()
            val pesLen = 8 + len
            header[4] = (pesLen shr 8).toByte(); header[5] = pesLen.toByte()
            header[6] = 0x80.toByte(); header[7] = 0x80.toByte(); header[8] = 5
            header[9] = (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte()
            header[10] = (pts shr 22).toByte()
            header[11] = (((pts shr 14) and 0xFE) or 1).toByte()
            header[12] = (pts shr 7).toByte()
            header[13] = (((pts shl 1) and 0xFE) or 1).toByte()
            val payload = header + d.copyOfRange(from, from + len)
            val out = ArrayList<ByteArray>()
            var p = 0
            while (p < payload.size) {
                val pkt = ByteArray(188)
                pkt[0] = 0x47
                pkt[1] = ((if (p == 0) 0x40 else 0) or (pid shr 8)).toByte()
                pkt[2] = pid.toByte()
                val room = 184
                val left = payload.size - p
                if (left >= room) {
                    pkt[3] = (0x10 or (cc and 0x0F)).toByte()
                    System.arraycopy(payload, p, pkt, 4, room); p += room
                } else {
                    // The last packet: an adaptation field stuffs the rest.
                    pkt[3] = (0x30 or (cc and 0x0F)).toByte()
                    val afLen = room - left - 1
                    pkt[4] = afLen.toByte()
                    if (afLen > 0) { pkt[5] = 0; for (i in 6 until 5 + afLen) pkt[i] = 0xFF.toByte() }
                    System.arraycopy(payload, p, pkt, 5 + afLen, left); p += left
                }
                cc++
                out.add(pkt)
            }
            return out
        }

        private fun skipId3(d: ByteArray, at: Int): Int {
            if (at + 10 > d.size || d[at] != 'I'.code.toByte() || d[at + 1] != 'D'.code.toByte() || d[at + 2] != '3'.code.toByte()) return at
            val size = ((d[at + 6].toInt() and 0x7F) shl 21) or ((d[at + 7].toInt() and 0x7F) shl 14) or
                ((d[at + 8].toInt() and 0x7F) shl 7) or (d[at + 9].toInt() and 0x7F)
            return minOf(d.size, at + 10 + size + (if (d[at + 5].toInt() and 0x10 != 0) 10 else 0))
        }

        /** (timestamp, position after the tag) from an ID3 tag at [at], or null. */
        private fun id3Timestamp(d: ByteArray, at: Int): Pair<Long, Int>? {
            val end = skipId3(d, at)
            if (end == at) return null
            val owner = "com.apple.streaming.transportStreamTimestamp".toByteArray(Charsets.US_ASCII)
            for (i in at + 10 until end - owner.size - 9) {
                if ((0 until owner.size).all { d[i + it] == owner[it] } && d[i + owner.size].toInt() == 0) {
                    var v = 0L
                    for (k in 1..8) v = (v shl 8) or (d[i + owner.size + k].toLong() and 0xFF)
                    return (v and 0x1FFFFFFFFL) to end
                }
            }
            return null
        }
    }

    // ---- helpers ----

    /**
     * Places timestamps on (section, unwrapped time): a known section from the playlist, else a new
     * section when time jumps back by more than 10 s or forward by more than 60 s (a discontinuity).
     */
    private class SectionClock {
        private var section = 0
        private var last = Long.MIN_VALUE
        private var wrap = 0L
        private var known: Int? = null

        fun place(ts33: Long, playlistSection: Int?): Pair<Int, Long> {
            if (playlistSection != null && playlistSection != known) {
                known = playlistSection; section = playlistSection; last = Long.MIN_VALUE; wrap = 0
            }
            var t = ts33 + wrap
            if (last != Long.MIN_VALUE) {
                if (last - t > (1L shl 32)) { wrap += 1L shl 33; t += 1L shl 33 }      // 33-bit wrap
                else if (playlistSection == null && (t < last - 900_000 || t > last + 5_400_000)) {
                    section++; wrap = 0; t = ts33                                          // a discontinuity
                }
            }
            if (t > last) last = t
            return section to t
        }
    }

    /** 188-byte packets from a list of TS files, each read from its media start; 192/204-byte packets cut down. */
    private class PacketReader(private val inputs: List<Input>) {
        private var index = -1
        private var input: InputStream? = null
        private var size = 188
        private var skipFront = 0
        /** The playlist section of the file being read (null when unknown). */
        var section: Int? = null
            private set

        fun next(): ByteArray? {
            while (true) {
                val inp = input ?: if (!open()) return null else input!!
                val raw = ByteArray(size)
                val n = readFully(inp, raw)
                if (n < size) { inp.close(); input = null; continue }
                val pkt = raw.copyOfRange(skipFront, skipFront + 188)
                if (pkt[0].toInt() != 0x47) {
                    // Lost sync: find the next sync byte in this file.
                    if (!resync(inp, raw)) { inp.close(); input = null }
                    continue
                }
                return pkt
            }
        }

        private fun open(): Boolean {
            if (++index >= inputs.size) return false
            val i = inputs[index]
            section = i.section
            val head = ByteArray(4 + 204 * 3)
            RandomAccessFile(i.path, "r").use { raf ->
                raf.seek(i.from)
                val n = raf.read(head)
                fun sync(at: Int) = at < n && head[at].toInt() == 0x47
                when {
                    sync(0) && sync(188) && sync(376) -> { size = 188; skipFront = 0 }
                    sync(4) && sync(196) && sync(388) -> { size = 192; skipFront = 4 }
                    sync(0) && sync(204) && sync(408) -> { size = 204; skipFront = 0 }
                    else -> { size = 188; skipFront = 0 }
                }
            }
            val s = BufferedInputStream(FileInputStream(i.path), 1 shl 20)
            skipBytes(s, i.from)
            input = s
            return true
        }

        /**
         * After a packet without its sync byte: the next read starts at the next 0x47 in what was read
         * (a damaged or shifted packet), else the whole packet is dropped and reading goes on.
         */
        private fun resync(@Suppress("UNUSED_PARAMETER") inp: InputStream, raw: ByteArray): Boolean {
            for (i in skipFront + 1 until raw.size) {
                if (raw[i].toInt() == 0x47) { pushBack = raw.copyOfRange(i - skipFront, raw.size); break }
            }
            return true
        }

        private var pushBack: ByteArray? = null

        private fun readFully(inp: InputStream, b: ByteArray): Int {
            var n = 0
            pushBack?.let { pb -> val k = minOf(pb.size, b.size); System.arraycopy(pb, 0, b, 0, k); n = k; pushBack = if (k < pb.size) pb.copyOfRange(k, pb.size) else null }
            while (n < b.size) {
                val r = inp.read(b, n, b.size - n)
                if (r <= 0) break
                n += r
            }
            return n
        }

        private fun skipBytes(s: InputStream, n: Long) {
            var left = n
            while (left > 0) { val k = s.skip(left); if (k <= 0) { if (s.read() < 0) throw EOFException(); left-- } else left -= k }
        }
    }

    private companion object {
        fun pid(p: ByteArray) = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)

        fun payloadStart(p: ByteArray): Int? {
            val afc = (p[3].toInt() shr 4) and 3
            if (afc == 0 || afc == 2) return null
            var i = 4
            if (afc == 3) i += 1 + (p[4].toInt() and 0xFF)
            return if (i < 188) i else null
        }

        /** DTS (else PTS) of a PES starting in [p], or null. */
        fun pesTimestamp(p: ByteArray): Long? {
            val i = payloadStart(p) ?: return null
            if (i + 14 > 188 || p[i].toInt() != 0 || p[i + 1].toInt() != 0 || p[i + 2].toInt() != 1) return null
            val sid = p[i + 3].toInt() and 0xFF
            if (sid == 0xBE || sid == 0xBF) return null
            val flags = (p[i + 7].toInt() and 0xFF) shr 6
            val o = when (flags) { 3 -> i + 14; 2 -> i + 9; else -> return null }
            if (o + 5 > 188) return null
            return (((p[o].toLong() and 0x0E) shl 29) or ((p[o + 1].toLong() and 0xFF) shl 22) or
                ((p[o + 2].toLong() and 0xFE) shl 14) or ((p[o + 3].toLong() and 0xFF) shl 7) or
                ((p[o + 4].toLong() and 0xFE) shr 1))
        }

        private val CRC_TABLE = IntArray(256) { n ->
            var c = n shl 24
            repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            c
        }

        /** MPEG-2 CRC32 (polynomial 0x04C11DB7, no reflection, initial 0xFFFFFFFF). */
        fun crc32(b: ByteArray): Int {
            var crc = -1
            for (x in b) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
            return crc
        }
    }
}
