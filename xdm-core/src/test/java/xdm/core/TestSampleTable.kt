// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.core.media.muxer.transmux.sample.SampleSpill
import xdm.core.media.muxer.transmux.sample.SampleTable
import xdm.core.media.muxer.transmux.sample.upperMedian
import java.io.File
import kotlin.random.Random

class TestSampleTable {
    @TempDir
    lateinit var dir: File

    private fun fill(t: SampleTable, n: Int) {
        for (i in 0 until n) t.add(i * 7L + (1L shl 33), 100 + i, i * 3000L + 6000, i * 3000L, i % 5 == 0, 1 + i % 3)
    }

    private fun rows(t: SampleTable) = (0 until t.size).map {
        listOf(t.fileOffset(it), t.sampleSize(it).toLong(), t.pts(it), t.dts(it), if (t.isKeyframe(it)) 1L else 0L, t.descriptionIndex(it).toLong())
    }

    @Test
    fun spilledRowsReadBackAndKeepChanges() {
        val n = 10_000 // several blocks
        val memory = SampleTable().also { fill(it, n) }
        val scope = SampleSpill.open(dir)
        try {
            val spilled = SampleTable().also { fill(it, n) }
            assertEquals(1, dir.listFiles()!!.size, "one scratch file")
            assertEquals(rows(memory), rows(spilled), "same rows as in memory")

            // Changes to old (spilled) rows, read back in an order that evicts them from the cache.
            for (t in listOf(memory, spilled)) {
                t.setPts(5, -1)
                t.setDts(2049, 42)
                t.shift(3000, 9000, 90_000)
                t[7000].pts = 123
            }
            for (i in listOf(9999, 0, 4100, 6200, 8300)) spilled.pts(i)
            assertEquals(rows(memory), rows(spilled), "changes survive eviction")
        } finally {
            scope.close()
        }
        assertEquals(0, dir.listFiles()!!.size, "scratch file deleted with the scope")
    }

    @Test
    fun upperMedianMatchesSorting() {
        val r = Random(7)
        for (n in listOf(1, 2, 3, 10, 1001)) {
            val values = LongArray(n) { if (it % 3 == 0) r.nextLong(0, Long.MAX_VALUE) else r.nextLong(0, 5000) }
            val expected = values.sorted()[n / 2]
            assertEquals(expected, upperMedian { sink -> values.forEach { sink.accept(it) } }, "n=$n")
        }
        assertEquals(-1L, upperMedian { }, "empty")
    }
}
