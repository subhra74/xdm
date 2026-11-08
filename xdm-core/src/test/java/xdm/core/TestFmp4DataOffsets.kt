// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.iso.Mp4Demuxer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Where a fragment's sample data is found (ISO/IEC 14496-12 8.8.7.1 / 8.8.8.3): relative to the
 * moof with default-base-is-moof; otherwise the first traf at the moof and each later traf right
 * after the previous traf's data; a trun without data_offset right after the previous run's data.
 */
class TestFmp4DataOffsets {

    @TempDir
    lateinit var dir: File

    private class RecordingSink : SampleSink {
        val bytes = ByteArrayOutputStream()
        override fun writeSampleData(data: ByteArray, offset: Int, length: Int): Long {
            val at = bytes.size().toLong()
            bytes.write(data, offset, length)
            return at
        }
    }

    private val rnd = Random(7)

    // ---- box building ----

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(body.size + 8); writeBytes(type) }
        return out.toByteArray() + body
    }

    private fun ints(vararg v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { v.forEach { writeInt(it) } }
        return out.toByteArray()
    }

    private fun fullBox(type: String, version: Int, flags: Int, vararg parts: ByteArray) =
        box(type, ints((version shl 24) or flags), *parts)

    /** A trak with track_ID [id] and handler [handler] (vide, soun or text); the sample entry is a stub. */
    private fun trak(id: Int, handler: String): ByteArray = box(
        "trak",
        fullBox("tkhd", 0, 7, ints(0, 0, id, 0, 0), ByteArray(52), ints(0, 0)),
        box(
            "mdia",
            fullBox("mdhd", 0, 0, ints(0, 0, 1000, 0, 0)),
            fullBox("hdlr", 0, 0, ints(0), handler.toByteArray(), ByteArray(13)),
            box("minf", box("stbl", fullBox("stsd", 0, 0, ints(1), box(if (handler == "soun") "mp4a" else "avc1", ByteArray(16)))))
        )
    )

    private fun init(vararg tracks: Pair<Int, String>): ByteArray = box("ftyp", "isom".toByteArray(), ints(0)) + box(
        "moov",
        fullBox("mvhd", 0, 0, ints(0, 0, 1000, 0), ByteArray(80)),
        *tracks.map { (id, h) -> trak(id, h) }.toTypedArray(),
        box("mvex", *tracks.map { (id, _) -> fullBox("trex", 0, 0, ints(id, 1, 40, 0, 0)) }.toTypedArray())
    )

    /** A run of samples: sizes given per sample; [dataOffset] null leaves the field out. */
    private fun trun(sizes: List<Int>, dataOffset: Int?): ByteArray {
        val flags = 0x200 or (if (dataOffset != null) 1 else 0)
        return fullBox("trun", 0, flags, ints(sizes.size), if (dataOffset != null) ints(dataOffset) else ByteArray(0), ints(*sizes.toIntArray()))
    }

    private class Run(val samples: List<ByteArray>, val explicitOffset: Boolean)

    private class Traf(val trackId: Int, val runs: List<Run>)

    /**
     * Builds moof+mdat with [trafs] laid out back to back in the mdat. With [defaultBaseIsMoof] data
     * offsets are moof-relative; without, each traf's are relative to the end of the previous traf's data.
     */
    private fun fragment(trafs: List<Traf>, defaultBaseIsMoof: Boolean): ByteArray {
        fun moof(offsets: List<List<Int?>>): ByteArray = box(
            "moof", fullBox("mfhd", 0, 0, ints(1)),
            *trafs.mapIndexed { t, traf ->
                box(
                    "traf",
                    fullBox("tfhd", 0, if (defaultBaseIsMoof) 0x020000 else 0, ints(traf.trackId)),
                    fullBox("tfdt", 0, 0, ints(0)),
                    *traf.runs.mapIndexed { r, run -> trun(run.samples.map { it.size }, offsets[t][r]) }.toTypedArray()
                )
            }.toTypedArray()
        )
        // Same moof size whatever the offset values, so compute sizes with placeholders first.
        val placeholder = trafs.map { traf -> traf.runs.map { if (it.explicitOffset) 0 else null } }
        val moofSize = moof(placeholder).size
        var pos = moofSize + 8 // first sample byte, relative to the moof start
        val offsets = ArrayList<List<Int?>>()
        for (traf in trafs) {
            val trafStart = pos
            val list = ArrayList<Int?>()
            for (run in traf.runs) {
                // Moof-relative, or relative to where this traf's data begins (the previous traf's end).
                val base = if (defaultBaseIsMoof || offsets.isEmpty()) 0 else trafStart
                list += if (run.explicitOffset) pos - base else null
                pos += run.samples.sumOf { it.size }
            }
            offsets += list
        }
        val data = trafs.flatMap { it.runs }.flatMap { it.samples }.fold(ByteArray(0)) { a, b -> a + b }
        return moof(offsets) + box("mdat", data)
    }

    private fun samples(n: Int) = List(n) { ByteArray(50 + rnd.nextInt(200)) { rnd.nextInt(256).toByte() } }

    private fun demux(vararg files: ByteArray): Pair<Mp4Demuxer, RecordingSink> {
        val sink = RecordingSink()
        val d = Mp4Demuxer(sink)
        files.forEachIndexed { i, bytes ->
            val f = File(dir, "seg$i.m4s"); f.writeBytes(bytes)
            d.parseSegment(f.absolutePath)
        }
        d.finish()
        return d to sink
    }

    private fun assertTrack(d: Mp4Demuxer, sink: RecordingSink, index: Int, expected: List<ByteArray>, what: String) {
        val track = d.tracks[index]
        assertEquals(expected.size, track.samples.size, "$what sample count")
        val all = sink.bytes.toByteArray()
        for ((i, s) in track.samples.withIndex()) {
            assertArrayEquals(expected[i], all.copyOfRange(s.fileOffset.toInt(), s.fileOffset.toInt() + s.size), "$what sample $i")
        }
    }

    @Test
    fun secondRunWithoutDataOffsetFollowsTheFirst() {
        val v1 = samples(5); val v2 = samples(6); val a1 = samples(8); val a2 = samples(7)
        val frag = fragment(
            listOf(Traf(1, listOf(Run(v1, true), Run(v2, false))), Traf(2, listOf(Run(a1, true), Run(a2, false)))),
            defaultBaseIsMoof = true
        )
        val (d, sink) = demux(init(1 to "vide", 2 to "soun"), frag)
        assertTrack(d, sink, 0, v1 + v2, "video")
        assertTrack(d, sink, 1, a1 + a2, "audio")
    }

    @Test
    fun withoutDefaultBaseIsMoofLaterTrafsFollowThePreviousTrafsData() {
        val v = samples(9); val a = samples(12)
        val frag = fragment(listOf(Traf(1, listOf(Run(v, true))), Traf(2, listOf(Run(a, true)))), defaultBaseIsMoof = false)
        val (d, sink) = demux(init(1 to "vide", 2 to "soun"), frag)
        assertTrack(d, sink, 0, v, "video")
        assertTrack(d, sink, 1, a, "audio")
    }

    @Test
    fun skippedTrackStillAdvancesTheDataPosition() {
        // A subtitle track between video and audio: not muxed, but its data lies between theirs.
        val v = samples(6); val t = samples(3); val a = samples(9)
        val frag = fragment(
            listOf(Traf(1, listOf(Run(v, true))), Traf(3, listOf(Run(t, false))), Traf(2, listOf(Run(a, false)))),
            defaultBaseIsMoof = false
        )
        val (d, sink) = demux(init(1 to "vide", 3 to "text", 2 to "soun"), frag)
        assertEquals(2, d.tracks.size, "the text track is not muxed")
        assertTrack(d, sink, 0, v, "video")
        assertTrack(d, sink, 1, a, "audio")
    }
}
