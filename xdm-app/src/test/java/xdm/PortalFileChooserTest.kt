package xdm

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.utils.linux.PortalFileChooser
import xdm.app.utils.linux.dbus.Reader
import xdm.app.utils.linux.dbus.Variant
import xdm.app.utils.linux.dbus.Writer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** The `OpenFile` options and `Response` handling of the XDG portal file chooser. */
class PortalFileChooserTest {

    @Test
    fun options_survivetheWireFormat() {
        val dir = Files.createTempDirectory("xdm portal").toFile()
        try {
            val options = PortalFileChooser.options("tok", true, dir, "Sounds" to listOf("wav"))
            val data = Writer().apply { write("ssa{sv}", listOf("", "Title", options)) }.bytes()
            val back = Reader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)).read("ssa{sv}")[2] as Map<*, *>

            assertEquals(Variant("s", "tok"), back["handle_token"], "token")
            assertEquals(Variant("b", true), back["directory"], "folder picker")
            val folder = (back["current_folder"] as Variant).value as ByteArray
            assertArrayEquals(dir.absolutePath.toByteArray() + 0, folder, "current folder: path bytes with a NUL")
            val filter = listOf("Sounds", listOf(listOf(0, "*.wav"), listOf(0, "*.WAV")))
            assertEquals(Variant("a(sa(us))", listOf(filter)), back["filters"], "filters")
            assertEquals(Variant("(sa(us))", filter), back["current_filter"], "current filter")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun options_leaveOutWhatIsNotAsked() {
        val options = PortalFileChooser.options("tok", false, File("/no/such/folder"), null)
        assertFalse("directory" in options, "file picker")
        assertFalse("current_folder" in options, "missing folder")
        assertFalse("filters" in options, "no filter")
    }

    @Test
    fun response_mapsCodesAndUris() {
        val ok = PortalFileChooser.toResult(listOf(0, mapOf("uris" to Variant("as", listOf("file:///home/a/My%20Videos")))))
        assertTrue(ok is PortalFileChooser.Result.Chosen, "chosen")
        assertEquals(File("/home/a/My Videos"), (ok as PortalFileChooser.Result.Chosen).file, "URI decoded")

        assertSame(PortalFileChooser.Result.Cancelled, PortalFileChooser.toResult(listOf(1, emptyMap<String, Any>())), "cancelled")
        assertSame(PortalFileChooser.Result.Unavailable, PortalFileChooser.toResult(listOf(2, emptyMap<String, Any>())), "failed: fall back")
        assertSame(
            PortalFileChooser.Result.Cancelled,
            PortalFileChooser.toResult(listOf(0, mapOf("uris" to Variant("as", listOf("sftp://host/x"))))),
            "not a local file",
        )
    }
}
