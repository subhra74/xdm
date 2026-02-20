import org.junit.Test
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest

class TestDashParser {
    @Test
    fun test1(){
        javaClass.getResourceAsStream("/mpd1").use {
            println(parseMpdManifest(it!!, "http://a/b/c"))
        }
    }
}