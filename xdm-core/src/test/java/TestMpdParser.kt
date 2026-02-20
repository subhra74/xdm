import org.junit.Test
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest
import xdm.core.media.parser.dash.MpdParser

class TestMpdParser {
    @Test
    fun test1() {
        javaClass.getResourceAsStream("/mpd1").use {
            println(MpdParser.parse(it!!, "http://a/b/c"))
        }
    }
}