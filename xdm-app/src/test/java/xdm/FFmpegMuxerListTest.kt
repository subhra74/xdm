package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.media.FFmpegMuxer

/** Lines of the `concatf:` list: ffmpeg drops unescaped `\`, `'` and edge whitespace (issue 1449). */
class FFmpegMuxerListTest {
    @Test
    fun windowsSeparatorsAreEscaped() {
        assertEquals(
            "C:\\\\Users\\\\me\\\\AppData\\\\video_00000.ts",
            FFmpegMuxer.listLine("C:\\Users\\me\\AppData\\video_00000.ts"),
            "each backslash is doubled so ffmpeg reads it as a separator",
        )
    }

    @Test
    fun quotesAndSpacesAreEscaped() {
        assertEquals(
            "/home/me/it\\'s\\ a\\ video.ts",
            FFmpegMuxer.listLine("/home/me/it's a video.ts"),
            "a quote or space in the name is kept as is",
        )
    }

    @Test
    fun plainPathIsUnchanged() {
        assertEquals("/tmp/xdm/video_00001.ts", FFmpegMuxer.listLine("/tmp/xdm/video_00001.ts"), "nothing to escape")
    }
}
