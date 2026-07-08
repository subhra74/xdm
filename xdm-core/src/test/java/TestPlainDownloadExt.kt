import org.junit.Assert.assertEquals
import org.junit.Test
import xdm.core.util.plainDownloadExt

/**
 * [plainDownloadExt] decides the output extension for content saved *without* remuxing (the
 * single-file HLS / single-stream DASH plain-HTTP paths). The native container must be preserved:
 * MPEG-TS stays "ts" (never "mp4"), WebM stays "webm" (never "mkv"), and audio keeps its own format.
 */
class TestPlainDownloadExt {

    // --- MIME-type driven (DASH representations carry an authoritative mimeType) --------------
    @Test fun mimeVideoMp4() = assertEquals("mp4", plainDownloadExt("video/mp4", "x/seg?a=1"))
    @Test fun mimeVideoWebm_staysWebmNotMkv() = assertEquals("webm", plainDownloadExt("video/webm", "x/seg"))
    @Test fun mimeMpegTs_staysTsNotMp4() = assertEquals("ts", plainDownloadExt("video/mp2t", "x/seg"))
    @Test fun mimeAudioMp4_isM4a() = assertEquals("m4a", plainDownloadExt("audio/mp4", "x/seg"))
    @Test fun mimeAudioAac() = assertEquals("aac", plainDownloadExt("audio/aac", "x/seg"))
    @Test fun mimeAudioMpeg_isMp3() = assertEquals("mp3", plainDownloadExt("audio/mpeg", "x/seg"))
    @Test fun mimeAudioWebm_staysWebm() = assertEquals("webm", plainDownloadExt("audio/webm", "x/seg"))

    // --- URL-extension fallback (HLS media segments carry the container in the URL) -----------
    @Test fun urlTs() = assertEquals("ts", plainDownloadExt(null, "http://h/p/media.ts"))
    @Test fun urlFmp4SegmentIsMp4() = assertEquals("mp4", plainDownloadExt(null, "http://h/p/media.m4s?tok=9"))
    @Test fun urlWebm() = assertEquals("webm", plainDownloadExt(null, "http://h/p/a.webm"))
    @Test fun urlM4a() = assertEquals("m4a", plainDownloadExt(null, "http://h/p/a.m4a"))
    @Test fun urlMp3() = assertEquals("mp3", plainDownloadExt(null, "http://h/p/a.mp3"))
    @Test fun noHintsDefaultsToTs() = assertEquals("ts", plainDownloadExt(null, "http://h/p/media"))
}
