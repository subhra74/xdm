import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.core.downloaders.web.streaming.manifest.hls.HlsMasterPlaylist
import xdm.core.downloaders.web.streaming.manifest.hls.HlsPlaylist
import xdm.core.downloaders.web.streaming.manifest.hls.HlsParser
import xdm.core.downloaders.web.streaming.manifest.hls.getInfoString
import xdm.core.downloaders.web.streaming.manifest.hls.singleFileHttpUrl

/**
 * Unit coverage for [HlsParser] — VOD media playlists and master playlists. Exercises the common
 * cases (plain/byte-range/encrypted VOD, alternate-audio masters) plus the edge cases behind the
 * recent fixes: valueless tags (I-FRAMES-ONLY / INDEPENDENT-SEGMENTS), per-segment byte ranges,
 * mixed/rotating encryption, default-IV derivation, BOM signatures, same-origin query carry-over,
 * and robust STREAM-INF↔URI pairing.
 */
class TestHlsParser {

    private val mediaUrl = "https://host.example/path/index.m3u8"
    private val masterUrl = "https://host.example/path/master.m3u8"

    private fun media(text: String, url: String = mediaUrl): HlsPlaylist =
        HlsParser.parseMediaSegments(text.trimIndent().lineSequence().iterator(), url).getOrThrow()

    private fun mediaResult(text: String, url: String = mediaUrl) =
        HlsParser.parseMediaSegments(text.trimIndent().lineSequence().iterator(), url)

    private fun master(text: String, url: String = masterUrl): List<HlsMasterPlaylist> =
        HlsParser.parseMasterPlaylist(text.trimIndent().lineSequence().iterator(), url).getOrThrow()

    private fun masterResult(text: String, url: String = masterUrl) =
        HlsParser.parseMasterPlaylist(text.trimIndent().lineSequence().iterator(), url)

    // ---------------------------------------------------------------------------------------
    // Media playlist — basics
    // ---------------------------------------------------------------------------------------

    @Test
    fun basicVod_relativeUrlsResolvedAndDurationsAccumulated() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:9.5,
            seg0.ts
            #EXTINF:8.0,Title text
            seg1.ts
            #EXT-X-ENDLIST
            """
        )
        assertEquals(2, p.mediaSegments.size)
        assertEquals("https://host.example/path/seg0.ts", p.mediaSegments[0].url)
        assertEquals("https://host.example/path/seg1.ts", p.mediaSegments[1].url)
        assertEquals(9.5, p.mediaSegments[0].duration, 1e-9)
        assertEquals(8.0, p.mediaSegments[1].duration, 1e-9)
        assertEquals(17.5, p.totalDuration, 1e-9)
        assertEquals(3, p.version)
        assertFalse(p.encrypted)
        assertFalse(p.hasByteRange)
        assertFalse(p.hasInitSection)
        assertFalse(p.keyFrameOnly)
        assertFalse(p.independent)
    }

    @Test
    fun absoluteSegmentUrlsPreserved() {
        val p = media(
            """
            #EXTM3U
            #EXTINF:4,
            https://cdn.other/a/seg0.ts
            #EXTINF:4,
            //cdn.other/a/seg1.ts
            """
        )
        assertEquals("https://cdn.other/a/seg0.ts", p.mediaSegments[0].url)
        // Protocol-relative resolves against the manifest scheme.
        assertEquals("https://cdn.other/a/seg1.ts", p.mediaSegments[1].url)
    }

    @Test
    fun integerExtinfSupported() {
        val p = media(
            """
            #EXTM3U
            #EXTINF:6,
            a.ts
            """
        )
        assertEquals(6.0, p.mediaSegments[0].duration, 1e-9)
    }

    // ---------------------------------------------------------------------------------------
    // Media playlist — valueless tags (regression: constants used to carry a bogus ':')
    // ---------------------------------------------------------------------------------------

    @Test
    fun iFramesOnlyTagDetected() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-I-FRAMES-ONLY
            #EXTINF:4,
            #EXT-X-BYTERANGE:1000@0
            v.mp4
            """
        )
        assertTrue("EXT-X-I-FRAMES-ONLY should set keyFrameOnly", p.keyFrameOnly)
    }

    @Test
    fun independentSegmentsTagDetectedInMediaPlaylist() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXTINF:4,
            a.ts
            """
        )
        assertTrue("EXT-X-INDEPENDENT-SEGMENTS should set independent", p.independent)
    }

    // ---------------------------------------------------------------------------------------
    // Media playlist — byte ranges
    // ---------------------------------------------------------------------------------------

    @Test
    fun byteRange_explicitOffsetAndContinuation() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="800@0"
            #EXTINF:4,
            #EXT-X-BYTERANGE:2000@1000
            main.mp4
            #EXTINF:4,
            #EXT-X-BYTERANGE:3000
            main.mp4
            #EXT-X-ENDLIST
            """
        )
        assertTrue(p.hasByteRange)
        assertTrue(p.hasInitSection)
        // [0] init map segment, [1] first media, [2] second media (continuation).
        assertEquals(3, p.mediaSegments.size)
        assertEquals(Pair(0L, 800L), p.mediaSegments[0].byteRange)
        assertEquals(0.0, p.mediaSegments[0].duration, 1e-9)
        assertEquals(Pair(1000L, 2000L), p.mediaSegments[1].byteRange)
        // Continuation (no @offset) starts right after the previous range end (1000+2000=3000).
        assertEquals(Pair(3000L, 3000L), p.mediaSegments[2].byteRange)
    }

    @Test
    fun byteRange_notInheritedBySegmentWithoutOwnRange() {
        // Regression: a global "hasByteRange" flag used to leak the previous segment's range onto a
        // segment that has no EXT-X-BYTERANGE of its own.
        val p = media(
            """
            #EXTM3U
            #EXTINF:4,
            #EXT-X-BYTERANGE:2000@0
            a.ts
            #EXTINF:4,
            b.ts
            """
        )
        assertEquals(Pair(0L, 2000L), p.mediaSegments[0].byteRange)
        assertNull("segment without its own BYTERANGE must not inherit one", p.mediaSegments[1].byteRange)
    }

    // ---------------------------------------------------------------------------------------
    // Media playlist — encryption
    // ---------------------------------------------------------------------------------------

    @Test
    fun aes128_explicitIvSharedAcrossSegments() {
        val iv = "0x000102030405060708090A0B0C0D0E0F"
        val p = media(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=$iv
            #EXTINF:4,
            s0.ts
            #EXTINF:4,
            s1.ts
            """
        )
        assertTrue(p.encrypted)
        p.mediaSegments.forEach {
            assertTrue(it.encrypted)
            assertEquals("https://host.example/path/enc.key", it.keyUrl)
            assertEquals(iv, it.iv)
        }
    }

    @Test
    fun aes128_defaultIvDerivedFromMediaSequence() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key"
            #EXTINF:4,
            s0.ts
            #EXTINF:4,
            s1.ts
            """
        )
        assertEquals("00000000000000000000000000000000", p.mediaSegments[0].iv)
        assertEquals("00000000000000000000000000000001", p.mediaSegments[1].iv)
    }

    @Test
    fun aes128_defaultIvHonoursMediaSequenceStart() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:97
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key"
            #EXTINF:4,
            s0.ts
            """
        )
        // 97 == 0x61
        assertEquals("00000000000000000000000000000061", p.mediaSegments[0].iv)
    }

    @Test
    fun keyRotation_eachSegmentUsesCurrentKey() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k1.key",IV=0x00000000000000000000000000000001
            #EXTINF:4,
            s0.ts
            #EXT-X-KEY:METHOD=AES-128,URI="k2.key",IV=0x00000000000000000000000000000002
            #EXTINF:4,
            s1.ts
            """
        )
        assertEquals("https://host.example/path/k1.key", p.mediaSegments[0].keyUrl)
        assertEquals("0x00000000000000000000000000000001", p.mediaSegments[0].iv)
        assertEquals("https://host.example/path/k2.key", p.mediaSegments[1].keyUrl)
        assertEquals("0x00000000000000000000000000000002", p.mediaSegments[1].iv)
    }

    @Test
    fun mixedEncryption_methodNoneClearsKeyStateButPlaylistStaysEncrypted() {
        // Regression: playlist.encrypted must reflect "any segment encrypted", and a METHOD=NONE
        // switch must clear the stale keyUrl/iv on the following clear segments.
        val p = media(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=0x00000000000000000000000000000001
            #EXTINF:4,
            enc0.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            clear1.ts
            """
        )
        assertTrue("any encrypted segment => playlist encrypted", p.encrypted)
        assertTrue(p.mediaSegments[0].encrypted)
        assertEquals("https://host.example/path/enc.key", p.mediaSegments[0].keyUrl)

        assertFalse(p.mediaSegments[1].encrypted)
        assertNull("clear segment must not carry a stale keyUrl", p.mediaSegments[1].keyUrl)
        assertNull("clear segment must not carry a stale iv", p.mediaSegments[1].iv)
    }

    @Test
    fun keyMethodNoneOnly_playlistNotEncrypted() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            a.ts
            """
        )
        assertFalse(p.encrypted)
        assertFalse(p.mediaSegments[0].encrypted)
        assertNull(p.mediaSegments[0].keyUrl)
    }

    @Test
    fun unsupportedKeyFormat_failsCleanly() {
        val r = mediaResult(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://lic/key",KEYFORMAT="com.widevine"
            #EXTINF:4,
            a.ts
            """
        )
        assertTrue("non-identity KEYFORMAT should not parse as plain AES-128", r.isFailure)
    }

    @Test
    fun sampleAes_failsCleanlyWithExplicitMessage() {
        val r = mediaResult(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="enc.key"
            #EXTINF:4,
            a.ts
            """
        )
        assertTrue("SAMPLE-AES is not supported and should fail", r.isFailure)
        // Only full-segment AES-128 is supported; the rejection should name SAMPLE-AES explicitly.
        assertTrue(
            "expected an explicit SAMPLE-AES message, got: ${r.exceptionOrNull()?.message}",
            r.exceptionOrNull()?.message?.contains("SAMPLE-AES") == true
        )
    }

    // ---------------------------------------------------------------------------------------
    // Media playlist — discontinuity
    // ---------------------------------------------------------------------------------------

    @Test
    fun discontinuity_flagsOnlyTheFollowingSegment() {
        val p = media(
            """
            #EXTM3U
            #EXTINF:4,
            a.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:4,
            b.ts
            #EXTINF:4,
            c.ts
            """
        )
        assertFalse(p.mediaSegments[0].discontinuity)
        assertTrue("segment after EXT-X-DISCONTINUITY should be flagged", p.mediaSegments[1].discontinuity)
        assertFalse(p.mediaSegments[2].discontinuity)
        assertTrue(p.hasDiscontinuity)
    }

    @Test
    fun noDiscontinuity_flagsClear() {
        val p = media(
            """
            #EXTM3U
            #EXTINF:4,
            a.ts
            """
        )
        assertFalse(p.hasDiscontinuity)
        assertFalse(p.mediaSegments[0].discontinuity)
    }

    @Test
    fun discontinuitySequenceTag_notTreatedAsDiscontinuity() {
        // EXT-X-DISCONTINUITY-SEQUENCE must NOT trip the per-segment discontinuity flag
        // (exact-match guard, not startsWith).
        val p = media(
            """
            #EXTM3U
            #EXT-X-DISCONTINUITY-SEQUENCE:3
            #EXTINF:4,
            a.ts
            """
        )
        assertFalse(p.hasDiscontinuity)
        assertFalse(p.mediaSegments[0].discontinuity)
    }

    // ---------------------------------------------------------------------------------------
    // Media playlist — same-origin query carry-over (regression)
    // ---------------------------------------------------------------------------------------

    @Test
    fun query_carriedToSameOriginOnly() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.other/k"
            #EXTINF:4,
            seg0.ts
            #EXTINF:4,
            https://cdn.other/x/seg1.ts
            #EXTINF:4,
            seg2.ts?v=9
            """,
            url = "https://host.example/path/index.m3u8?token=SECRET"
        )
        // Same origin => token appended.
        assertEquals("https://host.example/path/seg0.ts?token=SECRET", p.mediaSegments[0].url)
        // Cross origin => token NOT leaked.
        assertEquals("https://cdn.other/x/seg1.ts", p.mediaSegments[1].url)
        // Same origin but already carries its own query => left untouched.
        assertEquals("https://host.example/path/seg2.ts?v=9", p.mediaSegments[2].url)
        // Cross-origin key => token NOT leaked either.
        assertEquals("https://keys.other/k", p.mediaSegments[0].keyUrl)
    }

    // ---------------------------------------------------------------------------------------
    // Media playlist — malformed / signature
    // ---------------------------------------------------------------------------------------

    @Test
    fun bomBeforeSignatureAccepted() {
        val p = media("﻿#EXTM3U\n#EXTINF:4,\na.ts\n")
        assertEquals(1, p.mediaSegments.size)
    }

    @Test
    fun missingSignature_fails() {
        val r = mediaResult(
            """
            #EXTINF:4,
            a.ts
            """
        )
        assertTrue(r.isFailure)
    }

    @Test
    fun noSegments_fails() {
        val r = mediaResult(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-ENDLIST
            """
        )
        assertTrue(r.isFailure)
    }

    // ---------------------------------------------------------------------------------------
    // Master playlist
    // ---------------------------------------------------------------------------------------

    @Test
    fun isMasterPlaylist_detection() {
        assertTrue(
            HlsParser.isMasterPlaylist(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n".lineSequence().iterator()
            )
        )
        assertFalse(
            HlsParser.isMasterPlaylist(
                "#EXTM3U\n#EXTINF:4,\na.ts\n".lineSequence().iterator()
            )
        )
    }

    @Test
    fun master_simpleVariantsResolveAndCarryAttributes() {
        val list = master(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            high/index.m3u8
            """
        )
        assertEquals(2, list.size)
        assertEquals("https://host.example/path/low/index.m3u8", list[0].videoPlaylist.toString())
        assertNull(list[0].audioPlaylist)
        assertEquals("640x360", list[0].attributes["RESOLUTION"])
        assertEquals("https://host.example/path/high/index.m3u8", list[1].videoPlaylist.toString())
        // getInfoString surfaces resolution + bandwidth.
        val info = list[1].getInfoString()
        assertNotNull(info)
        assertTrue(info!!.contains("1280x720"))
        assertTrue(info.contains("kbps"))
    }

    @Test
    fun master_alternateAudioPairedWithEachVariant() {
        val list = master(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
            video/360/index.m3u8
            """
        )
        assertEquals(1, list.size)
        val entry = list[0]
        assertEquals("https://host.example/path/video/360/index.m3u8", entry.videoPlaylist.toString())
        assertEquals("https://host.example/path/audio/en/index.m3u8", entry.audioPlaylist.toString())
        assertEquals("en", entry.attributes["LANGUAGE"])
    }

    @Test
    fun master_independentSegmentsTagDetected() {
        val list = master(
            """
            #EXTM3U
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            """
        )
        assertEquals(1, list.size)
        assertTrue(list[0].independent)
    }

    @Test
    fun master_iFrameStreamInfDoesNotShiftPairing() {
        // Regression for positional pairing: EXT-X-I-FRAME-STREAM-INF carries its URI as an
        // attribute (no following line) and must not offset variant↔URI binding.
        val list = master(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,URI="low/iframe.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            high/index.m3u8
            """
        )
        assertEquals(2, list.size)
        assertEquals("640x360", list[0].attributes["RESOLUTION"])
        assertEquals("https://host.example/path/low/index.m3u8", list[0].videoPlaylist.toString())
        assertEquals("1280x720", list[1].attributes["RESOLUTION"])
        assertEquals("https://host.example/path/high/index.m3u8", list[1].videoPlaylist.toString())
    }

    @Test
    fun master_strayUriDoesNotCrashOrMispair() {
        // A STREAM-INF with more following URI lines than expected used to throw
        // IndexOutOfBounds; now the extra bare URI is simply ignored.
        val list = master(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            only/index.m3u8
            stray/extra.m3u8
            """
        )
        assertEquals(1, list.size)
        assertEquals("https://host.example/path/only/index.m3u8", list[0].videoPlaylist.toString())
    }

    // ---------------------------------------------------------------------------------------
    // Single-file byte-range playlists -> plain-HTTP download detection
    // ---------------------------------------------------------------------------------------

    @Test
    fun singleFileByteRange_contiguousFromZero_isPlainHttp() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXTINF:1,
            #EXT-X-BYTERANGE:100@0
            media.ts
            #EXTINF:1,
            #EXT-X-BYTERANGE:120@100
            media.ts
            #EXTINF:1,
            #EXT-X-BYTERANGE:80@220
            media.ts
            #EXT-X-ENDLIST
            """
        )
        assertEquals("https://host.example/path/media.ts", p.singleFileHttpUrl())
    }

    @Test
    fun multipleFiles_notPlainHttp() {
        val p = media(
            """
            #EXTM3U
            #EXTINF:1,
            #EXT-X-BYTERANGE:100@0
            a.ts
            #EXTINF:1,
            #EXT-X-BYTERANGE:100@100
            b.ts
            """
        )
        assertNull(p.singleFileHttpUrl())
    }

    @Test
    fun singleFileWithGap_notPlainHttp() {
        // A hole between ranges means a plain whole-file download would not match the segments.
        val p = media(
            """
            #EXTM3U
            #EXTINF:1,
            #EXT-X-BYTERANGE:100@0
            media.ts
            #EXTINF:1,
            #EXT-X-BYTERANGE:100@200
            media.ts
            """
        )
        assertNull(p.singleFileHttpUrl())
    }

    @Test
    fun plainSegmentsWithoutByteRange_notPlainHttp() {
        val p = media(
            """
            #EXTM3U
            #EXTINF:1,
            seg0.ts
            #EXTINF:1,
            seg1.ts
            """
        )
        assertNull(p.singleFileHttpUrl())
    }

    @Test
    fun encryptedSingleFile_notPlainHttp() {
        // AES-128 segments can't be handed to a plain HTTP download (no decryption there).
        val p = media(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=0x00000000000000000000000000000000
            #EXTINF:1,
            #EXT-X-BYTERANGE:100@0
            media.ts
            #EXTINF:1,
            #EXT-X-BYTERANGE:100@100
            media.ts
            """
        )
        assertNull(p.singleFileHttpUrl())
    }

    @Test
    fun master_noStreamInf_fails() {
        val r = masterResult(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",URI="a.m3u8"
            """
        )
        assertTrue(r.isFailure)
    }
}
