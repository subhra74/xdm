package xdm.app.utils.linux.dbus

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** A D-Bus variant: a value with its type signature. */
data class Variant(val signature: String, val value: Any?)

/** An error reply from the bus or a peer, or one of ours sent back to a caller. */
class DBusError(val name: String, message: String?) : Exception(message ?: name)

/*
 * The D-Bus wire format, driven by type signatures. Values are plain Kotlin:
 *
 *   y -> Byte (any Number when writing)      s, o, g -> String
 *   b -> Boolean                             a       -> List (any Iterable when writing); ay -> ByteArray
 *   n, q, i, u, h -> Int (Number to write)   a{..}   -> Map
 *   x, t -> Long, d -> Double                (..)    -> List of the fields
 *   v -> Variant
 *
 * `u` is read into an Int: only small counters and ids travel that way here. Alignment is relative to
 * the start of the message; a body starts 8-aligned, so it can be written and read on its own.
 */
internal object Signature {

    /** Index just past the single complete type starting at [start]. */
    fun end(sig: String, start: Int): Int = when (sig[start]) {
        'a' -> end(sig, start + 1)
        '(' -> { var i = start + 1; while (sig[i] != ')') i = end(sig, i); i + 1 }
        '{' -> { var i = start + 1; while (sig[i] != '}') i = end(sig, i); i + 1 }
        else -> start + 1
    }

    /** "sa{sv}i" -> ["s", "a{sv}", "i"] */
    fun split(sig: String): List<String> {
        val types = ArrayList<String>()
        var i = 0
        while (i < sig.length) {
            val e = end(sig, i)
            types += sig.substring(i, e)
            i = e
        }
        return types
    }

    fun alignment(type: Char): Int = when (type) {
        'y', 'g', 'v' -> 1
        'n', 'q' -> 2
        'b', 'i', 'u', 'h', 's', 'o', 'a' -> 4
        'x', 't', 'd', '(', '{' -> 8
        else -> throw IllegalArgumentException("Unsupported D-Bus type '$type'")
    }
}

internal class Writer(private val order: ByteOrder = ByteOrder.LITTLE_ENDIAN) {

    private var buf: ByteBuffer = ByteBuffer.allocate(256).order(order)

    val size: Int get() = buf.position()

    fun bytes(): ByteArray = buf.array().copyOf(buf.position())

    fun write(signature: String, values: List<Any?>) {
        val types = Signature.split(signature)
        require(types.size == values.size) { "Signature $signature needs ${types.size} values, got ${values.size}" }
        types.forEachIndexed { i, type -> writeOne(type, values[i]) }
    }

    fun pad(alignment: Int) {
        while (buf.position() % alignment != 0) room(1).put(0)
    }

    fun writeOne(type: String, value: Any?) {
        when (type[0]) {
            'y' -> room(1).put((value as Number).toByte())
            'b' -> { pad(4); room(4).putInt(if (value as Boolean) 1 else 0) }
            'n', 'q' -> { pad(2); room(2).putShort((value as Number).toShort()) }
            'i', 'u', 'h' -> { pad(4); room(4).putInt((value as Number).toInt()) }
            'x', 't' -> { pad(8); room(8).putLong((value as Number).toLong()) }
            'd' -> { pad(8); room(8).putDouble((value as Number).toDouble()) }
            's', 'o' -> {
                val bytes = (value as String).toByteArray(StandardCharsets.UTF_8)
                pad(4)
                room(4 + bytes.size + 1).putInt(bytes.size).put(bytes).put(0)
            }
            'g' -> {
                val bytes = (value as String).toByteArray(StandardCharsets.US_ASCII)
                room(1 + bytes.size + 1).put(bytes.size.toByte()).put(bytes).put(0)
            }
            'v' -> {
                val variant = value as Variant
                writeOne("g", variant.signature)
                writeOne(variant.signature, variant.value)
            }
            'a' -> writeArray(type.substring(1), value)
            '(' -> {
                pad(8)
                val fields = Signature.split(type.substring(1, type.length - 1))
                val list = value as List<*>
                fields.forEachIndexed { i, field -> writeOne(field, list[i]) }
            }
            else -> throw IllegalArgumentException("Unsupported D-Bus type '$type'")
        }
    }

    private fun writeArray(element: String, value: Any?) {
        pad(4)
        val lengthAt = buf.position()
        room(4).putInt(0)
        // The length counts the elements only, not the padding before the first one.
        pad(Signature.alignment(element[0]))
        val start = buf.position()
        when {
            element == "y" && value is ByteArray -> room(value.size).put(value)
            element[0] == '{' -> {
                val (keyType, valueType) = Signature.split(element.substring(1, element.length - 1))
                (value as Map<*, *>).forEach { (k, v) ->
                    pad(8)
                    writeOne(keyType, k)
                    writeOne(valueType, v)
                }
            }
            value is Array<*> -> value.forEach { writeOne(element, it) }
            else -> (value as Iterable<*>).forEach { writeOne(element, it) }
        }
        buf.putInt(lengthAt, buf.position() - start)
    }

    private fun room(bytes: Int): ByteBuffer {
        if (buf.remaining() < bytes) {
            val grown = ByteBuffer.allocate(maxOf(buf.capacity() * 2, buf.position() + bytes)).order(order)
            buf.flip()
            grown.put(buf)
            buf = grown
        }
        return buf
    }
}

/** Reads values from [buf], whose position 0 is the start of the message. */
internal class Reader(private val buf: ByteBuffer) {

    var position: Int
        get() = buf.position()
        set(value) {
            buf.position(value)
        }

    fun read(signature: String): List<Any?> = Signature.split(signature).map { readOne(it) }

    fun align(alignment: Int) {
        val misalignment = buf.position() % alignment
        if (misalignment != 0) buf.position(buf.position() + alignment - misalignment)
    }

    fun readOne(type: String): Any? = when (type[0]) {
        'y' -> buf.get()
        'b' -> { align(4); buf.getInt() != 0 }
        'n' -> { align(2); buf.getShort().toInt() }
        'q' -> { align(2); buf.getShort().toInt() and 0xFFFF }
        'i', 'u', 'h' -> { align(4); buf.getInt() }
        'x', 't' -> { align(8); buf.getLong() }
        'd' -> { align(8); buf.getDouble() }
        's', 'o' -> {
            align(4)
            val length = buf.getInt()
            string(length)
        }
        'g' -> string(buf.get().toInt() and 0xFF)
        'v' -> {
            val signature = readOne("g") as String
            Variant(signature, readOne(signature))
        }
        'a' -> readArray(type.substring(1))
        '(' -> {
            align(8)
            Signature.split(type.substring(1, type.length - 1)).map { readOne(it) }
        }
        else -> throw IllegalArgumentException("Unsupported D-Bus type '$type'")
    }

    private fun readArray(element: String): Any {
        align(4)
        val length = buf.getInt()
        align(Signature.alignment(element[0]))
        val end = buf.position() + length
        return when {
            element == "y" -> ByteArray(length).also { buf.get(it) }
            element[0] == '{' -> {
                val (keyType, valueType) = Signature.split(element.substring(1, element.length - 1))
                val map = LinkedHashMap<Any?, Any?>()
                while (buf.position() < end) {
                    align(8)
                    map[readOne(keyType)] = readOne(valueType)
                }
                map
            }
            else -> {
                val list = ArrayList<Any?>()
                while (buf.position() < end) list += readOne(element)
                list
            }
        }
    }

    private fun string(length: Int): String {
        val bytes = ByteArray(length)
        buf.get(bytes)
        buf.get() // the terminating NUL
        return String(bytes, StandardCharsets.UTF_8)
    }
}
