package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.media.Mp4InitProbe

/**
 * [Mp4InitProbe] on real file heads (`ftyp` + `moov` + `sidx`, no media) cut from browser captures:
 * an Instagram AAC track, a Facebook AV1 track and a Bilibili HEVC track. Expected values were read
 * from the same bytes with an independent parser; the total sizes are the CDNs' Content-Range totals.
 */
class TestMp4InitProbe {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/mp4probe/$name")!!.use { it.readBytes() }

    @Test
    fun instagramAudio() {
        val info = Mp4InitProbe.parse(fixture("ig-audio-mp4a.mp4"))!!
        assertTrue(info.isAudio, "hdlr soun")
        assertEquals("mp4a", info.codec, "codec")
        assertEquals(69.05, info.durationSeconds, 0.01, "duration from sidx (mvhd/mdhd are 0)")
        assertEquals(1276L, info.sidx!!.firstOffset, "first moof right after the sidx")
        assertEquals(35, info.sidx!!.refs.size, "sidx references")
        assertEquals(555095L, info.sidx!!.endOffset, "end of the last reference = file size")
        assertEquals(0L, info.needBytes, "nothing cut off")
    }

    @Test
    fun facebookAv1Video() {
        val info = Mp4InitProbe.parse(fixture("fb-video-av01.mp4"))!!
        assertTrue(info.isVideo, "hdlr vide")
        assertEquals("av01", info.codec, "codec")
        assertEquals(1080, info.width, "width")
        assertEquals(1080, info.height, "height")
        assertEquals(64.73, info.durationSeconds, 0.01, "duration")
        assertEquals(8015005L, info.sidx!!.endOffset, "file size from sidx")
    }

    @Test
    fun bilibiliHevcVideo_version1Sidx() {
        val info = Mp4InitProbe.parse(fixture("bili-video-hvc1.mp4"))!!
        assertEquals("hvc1", info.codec, "codec")
        assertEquals(853, info.width, "width")
        assertEquals(480, info.height, "height")
        assertEquals(1491.46, info.durationSeconds, 0.01, "duration")
        assertEquals(4766L, info.sidx!!.firstOffset, "first moof")
        assertEquals(302, info.sidx!!.refs.size, "references")
        assertEquals(36690287L, info.sidx!!.endOffset, "matches the CDN's Content-Range total")
    }

    @Test
    fun truncatedSidx_reportsBytesNeeded() {
        val full = fixture("bili-video-hvc1.mp4")
        val info = Mp4InitProbe.parse(full.copyOf(2000))!!
        assertNull(info.sidx, "sidx cut off")
        assertEquals(4766L, info.needBytes, "end of the sidx box")
    }

    @Test
    fun truncatedMoov_reportsBytesNeeded() {
        val info = Mp4InitProbe.parse(fixture("fb-video-av01.mp4").copyOf(100))
        assertNotNull(info, "a cut-off moov is still an MP4")
        assertEquals(826L, info!!.needBytes, "end of the moov box")
    }

    @Test
    fun notMp4_isNull() {
        assertNull(Mp4InitProbe.parse("<html><body>nope</body></html>".toByteArray()), "HTML")
        assertNull(Mp4InitProbe.parse(ByteArray(4)), "too short")
    }

    @Test
    fun segmentRanges_coverWholeFileOnSubsegmentBoundaries() {
        val info = Mp4InitProbe.parse(fixture("bili-video-hvc1.mp4"))!!
        val total = 36690287L
        val ranges = Mp4InitProbe.segmentRanges(info, total)!!
        assertEquals(0L to 4766L, ranges.first(), "head first")
        var expected = 0L
        for ((offset, length) in ranges) {
            assertEquals(expected, offset, "contiguous")
            expected += length
        }
        assertEquals(total, expected, "covers the file")
        // Every cut is on a reference boundary.
        val boundaries = HashSet<Long>()
        var at = info.sidx!!.firstOffset
        boundaries.add(at)
        info.sidx!!.refs.forEach { at += it.size; boundaries.add(at) }
        ranges.drop(1).forEach { (offset, _) -> assertTrue(offset in boundaries, "cut at $offset is a boundary") }
        ranges.drop(1).dropLast(1).forEach { (_, length) -> assertTrue(length >= 4L * 1024 * 1024, "merged up to 4 MB") }
    }

    @Test
    fun segmentRanges_trailingBytesJoinLastPiece() {
        val info = Mp4InitProbe.parse(fixture("ig-audio-mp4a.mp4"))!!
        val ranges = Mp4InitProbe.segmentRanges(info, 555095L + 100)!!
        assertEquals(555195L, ranges.last().let { it.first + it.second }, "trailing box included")
    }

    @Test
    fun segmentRanges_nullWithoutSidxOrWhenSidxExceedsFile() {
        val noSidx = Mp4InitProbe.parse(fixture("fb-video-av01.mp4").copyOf(826))!!
        assertNull(Mp4InitProbe.segmentRanges(noSidx, 8015005L), "no sidx: whole file")
        val info = Mp4InitProbe.parse(fixture("fb-video-av01.mp4"))!!
        assertNull(Mp4InitProbe.segmentRanges(info, 1000L), "sidx says the file is bigger than it is")
    }
}
