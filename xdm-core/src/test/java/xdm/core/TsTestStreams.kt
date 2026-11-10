// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.ts.TsDemuxer
import java.io.ByteArrayOutputStream

/** Collects sample bytes, like the MP4 writer's mdat. */
internal class RecordingSink : SampleSink {
    val bytes = ByteArrayOutputStream()
    override fun writeSampleData(data: ByteArray, offset: Int, length: Int): Long {
        val at = bytes.size().toLong()
        bytes.write(data, offset, length)
        return at
    }
}

/** MSB-first bit writer for building codec headers in tests. */
internal class BitWriter {
    private val out = ByteArrayOutputStream()
    private var cur = 0
    private var n = 0
    val bitCount get() = out.size() * 8 + n

    fun bits(value: Long, count: Int): BitWriter {
        for (i in count - 1 downTo 0) {
            cur = (cur shl 1) or ((value shr i) and 1).toInt()
            if (++n == 8) { out.write(cur); cur = 0; n = 0 }
        }
        return this
    }

    fun bits(value: Int, count: Int) = bits(value.toLong(), count)
    fun bit(b: Boolean) = bits(if (b) 1 else 0, 1)
    fun bytes(b: ByteArray) = apply { for (x in b) bits(x.toInt() and 0xFF, 8) }

    /** The bits written, the last byte zero-padded. */
    fun toByteArray(): ByteArray {
        val r = ByteArrayOutputStream()
        r.write(out.toByteArray())
        if (n > 0) r.write(cur shl (8 - n))
        return r.toByteArray()
    }
}

/** One PMT entry: stream type, PID and its ES_info descriptor bytes. */
internal class TsStream(val type: Int, val pid: Int, val descriptors: ByteArray = ByteArray(0))

/** Minimal MPEG-TS writer: PAT/PMT (CRC left zero: the demuxer doesn't check it) and PES packets. */
internal class TsBuilder {
    val out = ByteArrayOutputStream()
    private val cc = HashMap<Int, Int>()

    fun section(pid: Int, tableId: Int, body: ByteArray) {
        val len = body.size + 5 + 4
        val sec = byteArrayOf(tableId.toByte(), (0xB0 or (len shr 8)).toByte(), len.toByte(), 0, 1, 0xC1.toByte(), 0, 0) +
            body + ByteArray(4)
        packets(pid, byteArrayOf(0) + sec, psi = true)
    }

    fun pat(pmtPid: Int) = section(0, 0, byteArrayOf(0, 1, (0xE0 or (pmtPid shr 8)).toByte(), pmtPid.toByte()))

    fun pmt(pmtPid: Int, streams: List<Pair<Int, Int>>) = pmt(pmtPid, streams.map { TsStream(it.first, it.second) })

    fun pmt(pmtPid: Int, streams: List<TsStream>, programDescriptors: ByteArray = ByteArray(0)) {
        val pcr = streams.first().pid
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf((0xE0 or (pcr shr 8)).toByte(), pcr.toByte(),
            (0xF0 or (programDescriptors.size shr 8)).toByte(), programDescriptors.size.toByte()))
        body.write(programDescriptors)
        for (s in streams) {
            body.write(byteArrayOf(s.type.toByte(), (0xE0 or (s.pid shr 8)).toByte(), s.pid.toByte(),
                (0xF0 or (s.descriptors.size shr 8)).toByte(), s.descriptors.size.toByte()))
            body.write(s.descriptors)
        }
        section(pmtPid, 2, body.toByteArray())
    }

    /** A PES; [bounded] writes the real PES_packet_length (else 0, unbounded), [trailing] follows it. */
    fun pes(pid: Int, streamId: Int, pts: Long, data: ByteArray, bounded: Boolean = false, trailing: ByteArray = ByteArray(0)) {
        val ts = if (pts < 0) ByteArray(0) else byteArrayOf(
            (0x21 or (((pts shr 30) and 7).toInt() shl 1)).toByte(), (pts shr 22).toByte(),
            ((((pts shr 15) and 0x7F).toInt() shl 1) or 1).toByte(), (pts shr 7).toByte(),
            (((pts and 0x7F).toInt() shl 1) or 1).toByte()
        )
        val header = byteArrayOf(0x80.toByte(), if (pts < 0) 0 else 0x80.toByte(), ts.size.toByte()) + ts
        val length = if (bounded) header.size + data.size else 0
        packets(pid, byteArrayOf(0, 0, 1, streamId.toByte(), (length shr 8).toByte(), length.toByte()) + header + data + trailing, psi = false)
    }

    private fun packets(pid: Int, data: ByteArray, psi: Boolean) {
        var pos = 0
        var first = true
        while (pos < data.size) {
            val c = cc.getOrDefault(pid, 0); cc[pid] = (c + 1) and 0xF
            val n = minOf(184, data.size - pos)
            val pkt = ByteArray(188) { 0xFF.toByte() }
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            pkt[2] = pid.toByte()
            if (n == 184 || psi) {
                pkt[3] = (0x10 or c).toByte()
                System.arraycopy(data, pos, pkt, 4, n)
            } else {
                // Adaptation field stuffing so the payload ends the packet.
                val stuff = 184 - n
                pkt[3] = (0x30 or c).toByte()
                pkt[4] = (stuff - 1).toByte()
                if (stuff > 1) pkt[5] = 0
                System.arraycopy(data, pos, pkt, 4 + stuff, n)
            }
            out.write(pkt)
            pos += n; first = false
        }
    }
}

internal fun demuxTs(ts: ByteArray, chunk: Int = 4096): TsDemuxer {
    val d = TsDemuxer(RecordingSink())
    var k = 0
    while (k < ts.size) { val n = minOf(chunk, ts.size - k); d.feed(ts.copyOfRange(k, k + n), n); k += n }
    d.finish()
    return d
}
