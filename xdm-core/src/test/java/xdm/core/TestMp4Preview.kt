// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.media.Mp4InitProbe
import xdm.core.media.preview.Mp4KeyframeLocator
import xdm.core.media.preview.Mp4PreviewReader

/**
 * [Mp4PreviewReader] and [Mp4KeyframeLocator] on 10 s, 160x90 clips made with ffmpeg (keyframe
 * every 2 s), progressive and fragmented with a `sidx` (`-movflags +dash+global_sidx`). Expected
 * keyframe offsets and sizes are ffprobe's packet `pos`/`size` for the `K` packets of the same
 * files. Reads are 4 KB rather than the real 64 KB, so these small files are not read whole by the
 * first one.
 */
class TestMp4Preview {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/mp4preview/$name")!!.use { it.readBytes() }

    /** Serves ranges of [file] like a server honouring `Range`, and records each read. */
    private class FileReads(private val file: ByteArray) {
        val reads = ArrayList<Pair<Long, Int>>()
        fun read(offset: Long, length: Int): ByteArray {
            reads.add(offset to length)
            if (offset >= file.size) return ByteArray(0)
            return file.copyOfRange(offset.toInt(), minOf(file.size.toLong(), offset + length).toInt())
        }
    }

    private fun load(name: String, count: Int = 5, headBytes: Int = 4096): Pair<Mp4PreviewReader.Preview, FileReads> {
        val file = FileReads(fixture(name))
        return Mp4PreviewReader(headBytes, read = file::read).load(count) to file
    }

    /** Each frame is the file's bytes at ffprobe's `pos`/`size` for that keyframe. */
    private fun assertFrames(name: String, preview: Mp4PreviewReader.Preview, expected: List<Pair<Int, Int>>) {
        val bytes = fixture(name)
        assertEquals(expected.size, preview.frames.size, "frame count")
        expected.forEachIndexed { i, (pos, size) ->
            assertArrayEquals(bytes.copyOfRange(pos, pos + size), preview.frames[i].data, "frame $i at $pos")
        }
    }

    @Test
    fun faststartAvc_oneHeadReadThenOneReadPerFrame() {
        val (preview, file) = load("avc-faststart.mp4")
        assertEquals("avc1.64000a", preview.codec, "High profile, level 1.0")
        assertEquals(160, preview.width, "width")
        assertEquals(90, preview.height, "height")
        assertEquals(10_000_000L, preview.durationUs, "duration")
        assertEquals(listOf(0L, 2_000_000L, 4_000_000L, 6_000_000L, 8_000_000L), preview.frames.map { it.timeUs }, "frame times")
        assertEquals(listOf(2423, 1928, 1934, 1931, 1971), preview.frames.map { it.data.size }, "frame sizes")
        assertEquals(
            listOf(0L, 1818L, 7343L, 12354L, 17411L, 22378L), file.reads.map { it.first },
            "moov fits in the head read; then each keyframe once, in file order"
        )
        val bytes = fixture("avc-faststart.mp4")
        assertArrayEquals(bytes.copyOfRange(7343, 7343 + 1928), preview.frames[1].data, "frame bytes")
        assertTrue(preview.description!!.size > 7 && preview.description!![0] == 1.toByte(), "avcC body, version 1")
    }

    @Test
    fun moovAtEnd_hopsOverMdat() {
        // A head read smaller than the file, as with any real video: the walk must hop over mdat.
        val (preview, file) = load("avc-moov-at-end.mp4", headBytes = 4096)
        assertEquals("avc1.64000a", preview.codec, "codec")
        assertEquals(listOf(48L, 5573L, 10584L, 15641L, 20608L), file.reads.drop(2).map { it.first }, "keyframe offsets")
        assertEquals(0L, file.reads[0].first, "head read")
        assertEquals(25547L, file.reads[1].first, "second read jumps past mdat to moov")
        assertEquals(7, file.reads.size, "head, moov, five frames")
        assertEquals(5, preview.frames.size, "frames")
    }

    @Test
    fun fewerFramesAsked_spreadOverTheVideo() {
        val (preview, _) = load("avc-faststart.mp4", count = 3)
        // Targets 1.67 s, 5 s and 8.33 s; keyframes every 2 s.
        assertEquals(listOf(2_000_000L, 4_000_000L, 8_000_000L), preview.frames.map { it.timeUs }, "frame times")
    }

    @Test
    fun hevc() {
        val (preview, file) = load("hevc-faststart.mp4")
        assertTrue(preview.codec.startsWith("hvc1.1.6.L30"), "Main profile, level 1 (30): ${preview.codec}")
        // Keyframe decode times are 0, 1.7, 3.7, 5.7 and 7.7 s: 9 s and 7 s both want the last one,
        // so one point falls back to the first keyframe instead of repeating a frame.
        assertFrames("hevc-faststart.mp4", preview, listOf(4590 to 1369, 7610 to 1441, 10768 to 1412, 13959 to 1445, 17198 to 1445))
        assertEquals(2 + 5, file.reads.size, "head, the rest of a 4.5 KB moov, five frames")
    }

    @Test
    fun vp9_noDescription() {
        val (preview, file) = load("vp9-faststart.mp4")
        assertEquals("vp09.01.10.08", preview.codec, "profile 1, level 1, 8-bit")
        assertNull(preview.description, "VP9 needs no decoder description")
        assertFrames("vp9-faststart.mp4", preview, listOf(1246 to 2463, 7373 to 2447, 13506 to 2462, 19872 to 2451, 25798 to 2461))
        assertEquals(1 + 4, file.reads.size, "the first keyframe came with the head read")
    }

    @Test
    fun fragmentedWithSidx_oneReadPerFragment() {
        val (preview, file) = load("avc-sidx.mp4")
        assertEquals("avc1.64000a", preview.codec, "codec")
        assertEquals(10_000_000L, preview.durationUs, "duration from the sidx")
        assertEquals(listOf(0L, 2_000_000L, 4_000_000L, 6_000_000L, 8_000_000L), preview.frames.map { it.timeUs }, "fragment times")
        assertFrames("avc-sidx.mp4", preview, listOf(1174 to 2423, 6971 to 1928, 12254 to 1934, 17583 to 1931, 22822 to 1971))
        // The head holds moov, sidx and the first fragment's moof and keyframe. A fragment read is
        // 16 KB from its moof, which in these 5 KB fragments also holds the next ones.
        assertEquals(listOf(0L, 6699L, 22822L), file.reads.map { it.first }, "head, one fragment read, and the last keyframe, which runs past it")
        assertEquals(Mp4PreviewReader.FRAGMENT_HEAD_BYTES, file.reads[1].second, "fragment read length")
    }

    @Test
    fun oneFrame_firstKeyframe() {
        val (preview, file) = load("avc-faststart.mp4", count = 1)
        assertEquals(listOf(0L), preview.frames.map { it.timeUs }, "frame time")
        assertFrames("avc-faststart.mp4", preview, listOf(1818 to 2423))
        assertEquals(2, file.reads.size, "head, then the keyframe")
    }

    /** A split track's capture probe keeps its head up to the first moof; a preview then needs one read. */
    @Test
    fun splitTrackWithKnownHead_oneRequest() {
        val bytes = fixture("avc-sidx.mp4")
        val head = bytes.copyOf(902) // ftyp + moov + sidx: everything before the first moof
        val file = FileReads(bytes)
        val preview = Mp4PreviewReader(head = head, read = file::read).load(1)
        assertFrames("avc-sidx.mp4", preview, listOf(1174 to 2423))
        assertEquals(listOf(902L), file.reads.map { it.first }, "one read, at the chosen fragment's moof")
    }

    @Test
    fun fragmentedVp9WithSidx() {
        val (preview, _) = load("vp9-sidx.mp4")
        assertEquals("vp09.01.10.08", preview.codec, "codec")
        assertNull(preview.description, "no description for VP9")
        assertFrames("vp9-sidx.mp4", preview, listOf(1070 to 2463, 7389 to 2447, 13714 to 2462, 20272 to 2451, 26390 to 2461))
    }

    @Test
    fun fragmentedWithoutSidx_unsupported() {
        val e = assertThrows(Mp4PreviewReader.UnavailableException::class.java) { load("avc-fragmented.mp4") }
        assertEquals("fragmented MP4 without a sidx", e.message, "reason")
    }

    /** Heads of real captures (see TestMp4InitProbe): the codec strings and the fragments picked. */
    @Test
    fun facebookAv1Head() {
        val head = javaClass.getResourceAsStream("/mp4probe/fb-video-av01.mp4")!!.use { it.readBytes() }
        val moov = moovOf(head)
        val track = Mp4KeyframeLocator.videoTrack(moov)!!
        assertTrue(track.fragmented, "mvex")
        assertTrue(track.codec.matches(Regex("""av01\.0\.\d\dM\.08""")), "main profile, 8-bit: ${track.codec}")
        assertTrue(track.description != null && track.description!![0].toInt() and 0x80 != 0, "av1C body, marker bit")
        val sidx = Mp4InitProbe.parse(head)!!.sidx!!
        val fragments = Mp4KeyframeLocator.fragments(sidx, 5)
        assertEquals(5, fragments.size, "five fragments")
        assertEquals(fragments.sortedBy { it.offset }, fragments, "in file order")
        assertTrue(fragments.first().timeUs < 64_730_000 * 2 / 10 && fragments.last().timeUs > 64_730_000 * 7 / 10, "spread over 64.7 s")
    }

    @Test
    fun bilibiliHevcHead() {
        val head = javaClass.getResourceAsStream("/mp4probe/bili-video-hvc1.mp4")!!.use { it.readBytes() }
        val track = Mp4KeyframeLocator.videoTrack(moovOf(head))!!
        assertTrue(track.codec.startsWith("hvc1."), "codec: ${track.codec}")
        assertEquals(852, track.width, "coded width from the sample entry (tkhd says 853)")
    }

    private fun moovOf(head: ByteArray): ByteArray {
        val found = Mp4KeyframeLocator.walk(head, 0) as Mp4KeyframeLocator.Walk.Found
        return head.copyOfRange(found.offset.toInt(), (found.offset + found.size).toInt())
    }

    @Test
    fun notMp4_unsupportedAfterOneRead() {
        val file = FileReads("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray())
        assertThrows(Mp4PreviewReader.UnavailableException::class.java) { Mp4PreviewReader(read = file::read).load(5) }
        assertEquals(1, file.reads.size, "gives up on the first read")
    }

    @Test
    fun truncatedMoov_unavailable() {
        val bytes = fixture("avc-faststart.mp4").copyOfRange(0, 1000)
        val file = FileReads(bytes)
        assertThrows(Mp4PreviewReader.UnavailableException::class.java) { Mp4PreviewReader(read = file::read).load(5) }
    }

    @Test
    fun stop_returnsFramesReadSoFar() {
        val file = FileReads(fixture("avc-faststart.mp4"))
        val preview = Mp4PreviewReader(4096, read = file::read).load(5) { file.reads.size >= 3 }
        assertEquals(2, preview.frames.size, "head + two frames, then stopped")
    }
}
