// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.core.media.muxer.impl.TransmuxingMuxer
import xdm.core.media.muxer.transmux.mkv.MkvWriter
import xdm.core.media.muxer.transmux.ts.TsDemuxer
import xdm.core.storage.LocalStorage
import xdm.core.storage.RandomAccessOutput
import xdm.core.storage.StorageFile
import xdm.core.storage.StorageFolder
import xdm.core.util.isOutOfSpace
import java.io.File
import java.io.IOException

/**
 * The transmuxer stopped mid-way (a pause while assembling) or out of disk space: no partial output is
 * left behind, a stop is not reported as an error, and a full disk is kept in [TransmuxingMuxer.lastError].
 */
class TestTransmuxerStopAndDiskFull {

    private lateinit var dir: File
    private lateinit var segments: List<String>

    @BeforeEach
    fun setup() {
        val ffmpeg = StreamingE2EBase.ffmpeg
        Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not on PATH; skipping")
        dir = java.nio.file.Files.createTempDirectory("xdm-mux-stop").toFile()
        val (code, out) = StreamingE2EBase.exec(
            ffmpeg!!, "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25", "-c:a", "aac",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(dir, "s%d.ts").path, File(dir, "s.m3u8").path,
        )
        Assumptions.assumeTrue(code == 0, "ffmpeg could not make the fixture: $out")
        segments = dir.listFiles { f -> f.name.matches(Regex("s\\d+\\.ts")) }!!
            .sortedBy { it.name.removePrefix("s").removeSuffix(".ts").toInt() }.map { it.absolutePath }
        Assumptions.assumeTrue(segments.size >= 3, "too few segments")
    }

    @AfterEach
    fun tearDown() {
        if (this::dir.isInitialized) dir.deleteRecursively()
    }

    /** A local folder whose files fail with the OS's disk-full error once [limit] bytes are written. */
    private fun fullAfter(limit: Long, message: String = "No space left on device"): StorageFolder {
        val real = LocalStorage.folder(File(dir, "out").apply { mkdirs() }.absolutePath)
        return object : StorageFolder by real {
            override fun file(name: String): StorageFile = StorageFile(this, name)
            override fun open(name: String, create: Boolean): RandomAccessOutput {
                val h = real.open(name, create)
                return object : RandomAccessOutput by h {
                    var written = 0L
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        written += len
                        if (written > limit) throw IOException(message)
                        h.write(b, off, len)
                    }
                }
            }
        }
    }

    @Test
    fun stopMidMuxLeavesNoOutputAndNoError() {
        val muxer = TransmuxingMuxer(dir.absolutePath)
        val out = LocalStorage.file(File(dir, "stopped.mp4").absolutePath)
        val ok = muxer.mux(segments, out, { muxer.stop() }, dir.absolutePath, false, false)
        assertFalse(ok, "a stopped mux must not report success")
        assertFalse(out.exists, "the partial output must be deleted")
        assertNull(muxer.lastError, "a stop is not an error")
    }

    @Test
    fun fullDiskMp4KeepsErrorAndDeletesOutput() {
        val muxer = TransmuxingMuxer(dir.absolutePath)
        val out = fullAfter(16 * 1024).file("full.mp4")
        assertFalse(muxer.mux(segments, out, {}, dir.absolutePath, false, false), "mux must fail")
        assertTrue(isOutOfSpace(muxer.lastError), "lastError must be the disk-full error, was ${muxer.lastError}")
        assertFalse(out.exists, "the partial output must be deleted")
    }

    @Test
    fun fullDiskMkvKeepsErrorAndDeletesOutput() {
        val muxer = TransmuxingMuxer(dir.absolutePath)
        val out = fullAfter(16 * 1024, "There is not enough space on the disk").file("full.mkv")
        assertFalse(muxer.mux(segments, out, {}, dir.absolutePath, false, false), "mux must fail")
        assertTrue(isOutOfSpace(muxer.lastError), "lastError must be the disk-full error, was ${muxer.lastError}")
        assertFalse(out.exists, "the partial output must be deleted")
        assertTrue(dir.listFiles { f -> f.name.endsWith(".spool") }!!.isEmpty(), "the spool must be deleted")
    }

    @Test
    fun mkvFinishStopsWhenCancelled() {
        val out = LocalStorage.file(File(dir, "cancelled.mkv").absolutePath)
        val writer = MkvWriter(out, dir.absolutePath) { true }
        val demux = TsDemuxer(writer)
        segments.forEach { demux.feed(File(it).readBytes(), File(it).length().toInt()) }
        demux.finish()
        assertFalse(writer.finish(demux.tracks), "a cancelled finish must not report success")
        assertFalse(out.exists, "the partial output must be deleted")
        assertTrue(dir.listFiles { f -> f.name.endsWith(".spool") }!!.isEmpty(), "the spool must be deleted")
    }
}
