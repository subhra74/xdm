// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.core.media.muxer.transmux.mp4.Mp4Writer
import xdm.core.media.muxer.transmux.sample.Codec
import xdm.core.media.muxer.transmux.sample.SampleSpill
import xdm.core.media.muxer.transmux.sample.Track
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * A long video must finish in a small heap: a phone may give the app 48 MB, and a two-hour stream has
 * over half a million samples. The sample records spill to scratch files and the moov's sample
 * tables are streamed, so the muxer's memory does not grow with the length.
 */
class TestMp4LongStream {
    @TempDir
    lateinit var dir: File

    @Test
    fun twoHoursFinishInASmallHeap() {
        val out = File(dir, "long.mp4")
        val scratch = File(dir, "scratch").apply { mkdirs() }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(
            java, "-Xmx${HEAP_MB}m", "-cp", System.getProperty("java.class.path"),
            LongStreamMain::class.java.name, out.path, scratch.path, "2"
        ).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        assert(p.waitFor(5, TimeUnit.MINUTES)) { "muxer still running" }
        assertEquals(0, p.exitValue(), "mux in a $HEAP_MB MB heap failed:\n$log")

        val counts = sampleCounts(out)
        assertEquals(listOf(2 * 3600 * 30, (2 * 3600L * 48000 / 1024).toInt()), counts, "stsz sample counts per track")
        assertEquals(0, scratch.listFiles()!!.size, "scratch files deleted")
    }

    @Test
    fun spilledTablesWriteTheSameFileAsInMemory() {
        val inMemory = File(dir, "memory.mp4")
        LongStreamMain.mux(inMemory, null, minutes = 10)
        val spilled = File(dir, "spilled.mp4")
        LongStreamMain.mux(spilled, File(dir, "scratch"), minutes = 10)
        assertArrayEquals(inMemory.readBytes(), spilled.readBytes(), "same bytes")
    }

    /** stsz sample_count of each trak, from the moov at the end of [f]. */
    private fun sampleCounts(f: File): List<Int> {
        val moov = RandomAccessFile(f, "r").use { raf ->
            var pos = 0L
            var found: ByteArray? = null
            while (pos < raf.length()) {
                raf.seek(pos)
                var size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
                if (size == 1L) size = raf.readLong()
                if (type == "moov") found = ByteArray(size.toInt() - 8).also { raf.readFully(it) }
                pos += size
            }
            found ?: error("no moov")
        }
        val counts = ArrayList<Int>()
        fun walk(b: ByteBuffer, from: Int, to: Int) {
            var p = from
            while (p + 8 <= to) {
                val size = b.getInt(p)
                val type = String(ByteArray(4) { b.get(p + 4 + it) }, Charsets.US_ASCII)
                when (type) {
                    "trak", "mdia", "minf", "stbl" -> walk(b, p + 8, p + size)
                    "stsz" -> counts += b.getInt(p + 16)
                }
                p += size
            }
        }
        walk(ByteBuffer.wrap(moov), 0, moov.size)
        return counts
    }

    private companion object {
        const val HEAP_MB = 16
    }
}

/**
 * Muxes a synthetic stream: 30 fps video with B-frame reordering and 48 kHz AAC, one byte per sample
 * on disk (only the moov matters). Run on its own as `LongStreamMain <out> <scratchDir> <hours>`.
 */
object LongStreamMain {
    @JvmStatic
    fun main(args: Array<String>) {
        mux(File(args[0]), File(args[1]), minutes = args[2].toInt() * 60)
    }

    fun mux(out: File, scratch: File?, minutes: Int) {
        val spill = scratch?.let { SampleSpill.open(it) }
        try {
            val w = Mp4Writer(out.path)
            val video = Track(Codec.OTHER_VIDEO).apply { sampleEntryBox = entry("avc1"); width = 1280; height = 720 }
            val audio = Track(Codec.OTHER_AUDIO).apply { sampleEntryBox = entry("mp4a"); timescale = 48000 }
            val one = ByteArray(1)
            val frames = minutes * 60 * 30
            val audioFrames = minutes * 60L * 48000 / 1024
            var a = 0L
            for (i in 0 until frames) {
                val dts = 3000L * i
                val pts = dts + if (i % 3 == 0) 6000 else 3000
                video.samples.add(w.writeSampleData(one, 0, 1), 5000 + i % 7000, pts, dts, i % 60 == 0)
                while (a < audioFrames && a * 1024 * 90000 / 48000 <= dts) {
                    val t = a * 1024
                    audio.samples.add(w.writeSampleData(one, 0, 1), 300 + (a % 100).toInt(), t, t, true)
                    a++
                }
            }
            for (k in a until audioFrames) audio.samples.add(w.writeSampleData(one, 0, 1), 300, k * 1024, k * 1024, true)
            check(w.finish(listOf(video, audio))) { "nothing written" }
        } finally {
            spill?.close()
        }
    }

    private fun entry(fourcc: String) = byteArrayOf(0, 0, 0, 8) + fourcc.toByteArray(Charsets.US_ASCII)
}
