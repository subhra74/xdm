package xdm

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.utils.linux.dbus.Message
import xdm.app.utils.linux.dbus.Reader
import xdm.app.utils.linux.dbus.Signature
import xdm.app.utils.linux.dbus.Variant
import xdm.app.utils.linux.dbus.Writer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Pipe

/** The hand-written D-Bus wire format behind the Linux tray and notifications. */
class DBusMarshalTest {

    private fun bytes(signature: String, vararg values: Any?): List<Int> =
        Writer().apply { write(signature, values.toList()) }.bytes().map { it.toInt() and 0xFF }

    private fun roundTrip(signature: String, vararg values: Any?): List<Any?> {
        val data = Writer().apply { write(signature, values.toList()) }.bytes()
        return Reader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)).read(signature)
    }

    @Test
    fun splitsSignatures() {
        assertEquals(listOf("s", "a{sv}", "(ia{sv}av)", "aay", "i"), Signature.split("sa{sv}(ia{sv}av)aayi"), "complete types")
    }

    @Test
    fun alignsBasicTypes() {
        assertEquals(listOf(1, 0, 0, 0, 2, 0, 0, 0), bytes("yi", 1, 2), "int aligned to 4 after a byte")
        assertEquals(listOf(2, 0, 0, 0, 'a'.code, 'b'.code, 0), bytes("s", "ab"), "string: length, bytes, NUL")
        assertEquals(listOf(1, 'i'.code, 0, 0, 5, 0, 0, 0), bytes("v", Variant("i", 5)), "variant: signature then aligned value")
        assertEquals(listOf(1, 0, 0, 0), bytes("b", true), "boolean is 4 bytes")
    }

    @Test
    fun arrayLengthExcludesPaddingBeforeTheFirstElement() {
        // u32 length 0, then padding to the struct's 8-byte alignment even though there are no elements.
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0), bytes("a(yy)", emptyList<Any>()), "empty struct array")
        // Entries start at 8: "a" + byte ends at 15, the second entry is padded to 16 and ends at 23.
        val dict = bytes("a{sy}", linkedMapOf("a" to 1, "b" to 2))
        assertEquals(15, dict[0], "dict length counts entries and the padding between them, not before the first")
        assertEquals(8 + 15, dict.size, "4 length + 4 padding + entries")
    }

    @Test
    fun roundTripsNestedValues() {
        val pixmap = listOf(2, 1, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val layout = listOf(0, mapOf("children-display" to Variant("s", "submenu")), listOf(Variant("(ia{sv}av)", listOf(1, emptyMap<String, Variant>(), emptyList<Variant>()))))
        val values = roundTrip(
            "ua(iiay)(ia{sv}av)asxdnq", 7, listOf(pixmap), layout, listOf("x", "yz"), -5L, 1.5, -2, 65535,
        )
        assertEquals(7, values[0], "u")
        @Suppress("UNCHECKED_CAST")
        val readPixmap = (values[1] as List<List<Any?>>)[0]
        assertEquals(2, readPixmap[0], "width")
        assertArrayEquals(pixmap[2] as ByteArray, readPixmap[2] as ByteArray, "ay comes back as ByteArray")
        assertEquals(layout, values[2], "struct with dict and variant children")
        assertEquals(listOf("x", "yz"), values[3], "as")
        assertEquals(-5L, values[4], "x")
        assertEquals(1.5, values[5], "d")
        assertEquals(-2, values[6], "n is signed")
        assertEquals(65535, values[7], "q is unsigned")
    }

    @Test
    fun messageSurvivesEncodeAndRead() {
        val encoded = Message.encode(
            Message.METHOD_CALL, 42, path = "/StatusNotifierItem", interfaceName = "org.kde.StatusNotifierWatcher",
            member = "RegisterStatusNotifierItem", destination = "org.kde.StatusNotifierWatcher",
            signature = "sa{sv}", body = listOf("org.kde.StatusNotifierItem-1-1", mapOf("k" to Variant("b", true))),
        )
        val bodyStart = headerAndBodyStart(encoded)
        assertEquals(0, bodyStart % 8, "body starts 8-aligned")
        assertEquals(encoded.size - bodyStart, ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(4), "body length field")
        val pipe = Pipe.open()
        pipe.sink().write(ByteBuffer.wrap(encoded))
        val message = Message.read(pipe.source())
        assertEquals(Message.METHOD_CALL, message.type, "type")
        assertEquals(42, message.serial, "serial")
        assertEquals("/StatusNotifierItem", message.path, "path")
        assertEquals("org.kde.StatusNotifierWatcher", message.interfaceName, "interface")
        assertEquals("RegisterStatusNotifierItem", message.member, "member")
        assertEquals("org.kde.StatusNotifierWatcher", message.destination, "destination")
        assertEquals("sa{sv}", message.signature, "signature")
        assertEquals(listOf("org.kde.StatusNotifierItem-1-1", mapOf("k" to Variant("b", true))), message.body, "body")
    }

    /** Offset where the body begins: 16 fixed bytes + header fields, padded to 8. */
    private fun headerAndBodyStart(encoded: ByteArray): Int {
        val fields = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
        return (16 + fields + 7) and 7.inv()
    }
}
