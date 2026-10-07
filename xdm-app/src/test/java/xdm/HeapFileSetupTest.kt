package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.utils.HeapFileSetup
import java.io.File
import java.nio.file.Files

class HeapFileSetupTest {
    private lateinit var dir: File
    private lateinit var marker: File

    private val base = "[Application]\r\napp.mainclass=xdm.app.AppMain\r\n\r\n[JavaOptions]\r\n" +
            "java-options=-XX:+UseSerialGC\r\njava-options=-Xms6m\r\n"

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-heapcfg").toFile()
        marker = File(dir, "xdm-heap-test.ok")
        File(dir, "xdm-app.cfg.base").writeText(base)
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun render_addsHeapLineFirstInJavaOptions_keepingCrlf() {
        val out = HeapFileSetup.render(base, "C:\\WINDOWS\\Temp")
        assertTrue(out.contains("[JavaOptions]\r\njava-options=-XX:AllocateHeapAt=C:\\WINDOWS\\Temp\r\n"), out)
        assertEquals(base.count { it == '\n' } + 1, out.count { it == '\n' }, "exactly one line is added")
    }

    @Test
    fun render_withoutHeapDir_dropsAnExistingHeapLine() {
        val withHeap = HeapFileSetup.render(base, "C:\\WINDOWS\\Temp")
        assertEquals(base, HeapFileSetup.render(withHeap, null), "back to the base content")
    }

    @Test
    fun render_createsJavaOptionsWhenMissing() {
        val out = HeapFileSetup.render("[Application]\napp.mainclass=x\n", "D:\\heap")
        assertEquals("[Application]\napp.mainclass=x\n[JavaOptions]\njava-options=-XX:AllocateHeapAt=D:\\heap\n", out)
    }

    @Test
    fun apply_freshMarker_writesHeapCfgAndDeletesMarker() {
        marker.writeText("ok")
        val code = HeapFileSetup.apply(dir, marker, "C:\\WINDOWS\\Temp", marker.lastModified() + 1000)
        assertEquals(0, code)
        assertTrue(File(dir, "xdm-app.cfg").readText().contains("-XX:AllocateHeapAt=C:\\WINDOWS\\Temp"))
        assertFalse(marker.exists(), "the marker is consumed")
    }

    @Test
    fun apply_noMarker_writesBaseContentOverAnOldCfg() {
        File(dir, "xdm-app.cfg").writeText("previous version's cfg")
        val code = HeapFileSetup.apply(dir, marker, "C:\\WINDOWS\\Temp", System.currentTimeMillis())
        assertEquals(0, code)
        assertEquals(base, File(dir, "xdm-app.cfg").readText(), "always rewritten from the base")
    }

    @Test
    fun apply_staleMarker_isIgnoredAndDeleted() {
        marker.writeText("ok")
        val code = HeapFileSetup.apply(dir, marker, "C:\\WINDOWS\\Temp", marker.lastModified() + 60 * 60 * 1000)
        assertEquals(0, code)
        assertEquals(base, File(dir, "xdm-app.cfg").readText(), "an old marker must not enable the heap file")
        assertFalse(marker.exists())
    }

    @Test
    fun apply_withoutBase_failsAndLeavesCfgAlone() {
        File(dir, "xdm-app.cfg.base").delete()
        File(dir, "xdm-app.cfg").writeText("installed")
        marker.writeText("ok")
        assertEquals(1, HeapFileSetup.apply(dir, marker, "C:\\WINDOWS\\Temp", marker.lastModified()))
        assertEquals("installed", File(dir, "xdm-app.cfg").readText())
        assertFalse(marker.exists())
    }

    @Test
    fun test_writesMarker() {
        assertEquals(0, HeapFileSetup.test(File(dir, "sub/marker.ok")))
        assertTrue(File(dir, "sub/marker.ok").isFile)
    }
}
