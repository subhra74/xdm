// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.ogg

import xdm.core.media.muxer.transmux.ContainerWriter
import xdm.core.media.muxer.transmux.mkv.TrackLostException
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.storage.RandomAccessOutput
import xdm.core.storage.StorageFile
import xdm.core.storage.asOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.random.Random

/**
 * Writes one Opus track as an Ogg Opus file (`.opus`, RFC 7845): the `OpusHead` page, an `OpusTags`
 * page, then the packets as they were, so audio from a WebM file (or any input whose Opus track has an
 * `OpusHead`) is saved as `.opus` with no re-encoding.
 *
 * Sample bytes are spooled to a scratch file as the demuxer produces them, like the Matroska writer;
 * [finish] reads them back into pages. Granule positions count 48 kHz samples from the start of the
 * stream including the pre-skip, each packet's length taken from its TOC byte (RFC 6716 §3.1), not
 * from the input's millisecond timestamps. Anything other than exactly one Opus track throws
 * [TrackLostException], so the caller can mux into Matroska instead.
 */
class OggWriter(
    private val output: StorageFile,
    spoolDir: String? = null,
    /** Checked between pages while [finish] copies the spool out: true drops the output. */
    private val cancelled: () -> Boolean = { false },
) : ContainerWriter {
    override var repairTimeline: Boolean = false

    private val spoolFile = File(
        spoolDir ?: output.folder.localPath ?: throw IllegalArgumentException("No spool folder for $output"),
        "${output.name}.spool"
    )
    private val spool = BufferedOutputStream(FileOutputStream(spoolFile), 1 shl 20)
    private var spoolPos = 0L
    private var out: OutputStream? = null
    private var handle: RandomAccessOutput? = null

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
        val track = usable.singleOrNull()
        val head = track?.decoderConfigRecord
        if (track == null || track.codec != Codec.OPUS || head == null || !isOpusHead(head)) {
            spoolFile.delete()
            throw TrackLostException("Ogg Opus holds one Opus track; got ${usable.joinToString { it.codec.name }}")
        }
        val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
        try {
            val h = output.open(create = true).also { handle = it }
            h.setLength(0)
            val o = BufferedOutputStream(h.asOutputStream(), 1 shl 20).also { out = it }
            val pages = PageWriter(o, Random.nextInt())
            pages.page(listOf(head), granule = 0, bos = true)
            pages.page(listOf(opusTags()), granule = 0)

            val s = track.samples
            val packets = ArrayList<ByteArray>()
            var segments = 0
            var granule = preSkip.toLong()
            var pageStart = granule
            RandomAccessFile(spoolFile, "r").use { raf ->
                for (i in 0 until s.size) {
                    val p = ByteArray(s.sampleSize(i))
                    raf.seek(s.fileOffset(i))
                    raf.readFully(p)
                    val segs = p.size / 255 + 1
                    if (packets.isNotEmpty() && (segments + segs > 255 || granule - pageStart >= PAGE_SAMPLES)) {
                        if (cancelled()) { abort(); return false }
                        pages.page(packets, granule)
                        packets.clear(); segments = 0; pageStart = granule
                    }
                    packets.add(p)
                    segments += segs
                    granule += samplesIn(p)
                }
            }
            // End trimming from the input (Matroska DiscardPadding on the last block): the final
            // granule position stops where the audio does (RFC 7845 §4.4).
            if (track.endPaddingSample == s.size - 1 && track.endPaddingNs > 0) {
                val trim = track.endPaddingNs * 48_000L / 1_000_000_000L
                granule = maxOf(pageStart, granule - trim)
            }
            pages.page(packets, granule, eos = true)
            o.flush()
            o.close()
            out = null
            handle = null
            return true
        } finally {
            spoolFile.delete()
        }
    }

    override fun abort() {
        try { spool.close() } catch (_: Exception) {}
        try { out?.close() ?: handle?.close() } catch (_: Exception) {}
        out = null; handle = null
        output.delete(); spoolFile.delete()
    }

    /** Builds and writes pages with a running sequence number. */
    private class PageWriter(private val out: OutputStream, private val serial: Int) {
        private var sequence = 0

        /** One page holding whole [packets]; [granule] is the sample position at the end of the last one. */
        fun page(packets: List<ByteArray>, granule: Long, bos: Boolean = false, eos: Boolean = false) {
            val lacing = ArrayList<Int>()
            for (p in packets) {
                repeat(p.size / 255) { lacing.add(255) }
                lacing.add(p.size % 255)
            }
            require(lacing.size <= 255) { "Ogg page with ${lacing.size} segments" }
            val bodySize = packets.sumOf { it.size }
            val page = ByteArray(27 + lacing.size + bodySize)
            "OggS".toByteArray(Charsets.US_ASCII).copyInto(page)
            page[4] = 0 // version
            page[5] = ((if (bos) 0x02 else 0) or (if (eos) 0x04 else 0)).toByte()
            le(page, 6, granule, 8)
            le(page, 14, serial.toLong(), 4)
            le(page, 18, (sequence++).toLong(), 4)
            // 22..25: CRC, computed with the field zero
            page[26] = lacing.size.toByte()
            var at = 27
            for (l in lacing) page[at++] = l.toByte()
            for (p in packets) { p.copyInto(page, at); at += p.size }
            le(page, 22, OggCrc.of(page).toLong() and 0xFFFFFFFFL, 4)
            out.write(page)
        }

        private fun le(b: ByteArray, at: Int, v: Long, n: Int) {
            for (i in 0 until n) b[at + i] = (v ushr (8 * i)).toByte()
        }
    }

    companion object {
        /** A page is closed once it holds this much audio (1 s at 48 kHz), as ffmpeg's Ogg muxer does by default. */
        private const val PAGE_SAMPLES = 48_000L

        private const val VENDOR = "XDM"

        internal fun isOpusHead(b: ByteArray) =
            b.size >= 19 && String(b, 0, 8, Charsets.US_ASCII) == "OpusHead"

        /** An `OpusTags` header (RFC 7845 §5.2): the vendor string and no comments. */
        internal fun opusTags(): ByteArray {
            val vendor = VENDOR.toByteArray(Charsets.UTF_8)
            val b = ByteArray(8 + 4 + vendor.size + 4)
            "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(b)
            b[8] = vendor.size.toByte()
            vendor.copyInto(b, 12)
            return b
        }

        /** 48 kHz samples in an Opus packet, from its TOC byte and frame count (RFC 6716 §3.1); 0 when empty. */
        internal fun samplesIn(p: ByteArray): Int {
            if (p.isEmpty()) return 0
            val toc = p[0].toInt() and 0xFF
            val config = toc ushr 3
            val frameSamples = when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3] // SILK: 10, 20, 40, 60 ms
                config < 16 -> intArrayOf(480, 960)[config and 1] // Hybrid: 10, 20 ms
                else -> intArrayOf(120, 240, 480, 960)[config and 3] // CELT: 2.5, 5, 10, 20 ms
            }
            val frames = when (toc and 3) {
                0 -> 1
                3 -> if (p.size > 1) p[1].toInt() and 0x3F else 0
                else -> 2
            }
            return frameSamples * frames
        }
    }
}

/** The Ogg page checksum: CRC-32 with polynomial 0x04C11DB7, no reflection, initial value 0, no final XOR. */
internal object OggCrc {
    private val TABLE = IntArray(256) { n ->
        var c = n shl 24
        repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        c
    }

    fun of(b: ByteArray): Int {
        var crc = 0
        for (x in b) crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
        return crc
    }
}
