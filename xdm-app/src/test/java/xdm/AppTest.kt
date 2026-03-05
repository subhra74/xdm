package xdm

import junit.framework.TestCase
import xdm.core.downloaders.web.streaming.manifest.hls.HlsParser
import java.nio.file.Files
import java.nio.file.Paths

class AppTest : TestCase() {
    fun testApp() {
        val lines = Files.lines(Paths.get(javaClass.getResource("/master.m3u8")?.toURI() ?: null)).toList()
        println(HlsParser.isMasterPlaylist(lines.iterator()))
        HlsParser.parseMasterPlaylist(
            lines.iterator(),
            "http://playlist"
        ).onSuccess { println(it) }
//        HlsParser.parseMediaSegments(
//            Files.lines(Paths.get(javaClass.getResource("/master.m3u8")?.toURI() ?: null)).iterator(),
//            "http://playlist"
//        ).onSuccess {
//            println(it)
//        }
    }
}