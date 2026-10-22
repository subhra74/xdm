package xdm.core.util

import java.io.DataInputStream
import java.io.DataOutput
import java.io.IOException

/**
 * Versioned binary format for the config, the download lists and `task-<id>.info` (see
 * docs/architecture.md, "Persistence").
 *
 * A file is [MAGIC], a format version (varint) and then fields. Each field is a tag
 * (`fieldNo shl 3 or wireType`) and a value, in protobuf's wire encoding: varints (wire type 0)
 * for booleans, ints, longs and enum ids, length-prefixed bytes (wire type 2) for strings and
 * nested records. A reader handles the field numbers it knows and skips the rest, so a field can
 * be added or retired without breaking older or newer builds. Field numbers are never reused or
 * retyped: a changed field gets a new number.
 *
 * Absent fields keep the reader's defaults. Lists are written as one nested record (see
 * [TagWriter.strings]) so an empty list is still present and does not fall back to a non-empty
 * default.
 */
object TaggedFile {
    /** "XDMT". Files in the previous untagged format never start with it. */
    const val MAGIC = 0x58444D54
    const val FORMAT_VERSION = 1

    /** Writes the header and the fields [body] adds to [out]. */
    inline fun write(out: DataOutput, body: TagWriter.() -> Unit) {
        val w = TagWriter()
        w.varint(FORMAT_VERSION.toLong())
        w.body()
        out.writeInt(MAGIC)
        w.writeTo(out)
    }

    /**
     * The fields of a file written by [write]. Throws [NotTaggedException] for a file without the
     * header, such as one from a build that used the untagged format.
     */
    fun read(input: DataInputStream): TagReader {
        val bytes = input.readBytes()
        if (bytes.size < 4 || readInt(bytes) != MAGIC) throw NotTaggedException()
        val r = TagReader(bytes, 4, bytes.size)
        r.varint() // format version: nothing to migrate yet
        return r
    }

    private fun readInt(b: ByteArray): Int =
        (b[0].toInt() and 0xFF shl 24) or (b[1].toInt() and 0xFF shl 16) or
            (b[2].toInt() and 0xFF shl 8) or (b[3].toInt() and 0xFF)
}

/** The file has no [TaggedFile] header: it is missing it or was written in the old format. */
class NotTaggedException : IOException("Not a tagged file")

/** An enum constant's persisted id. Unlike the ordinal or name it survives reordering and renaming. */
interface StableId {
    val id: Int
}

/** The constant with persisted [id], or null for an id this build does not know. */
fun <E> Array<E>.byStableId(id: Int): E? where E : Enum<E>, E : StableId = firstOrNull { it.id == id }

class TagWriter {
    private var buf = ByteArray(256)
    private var size = 0

    fun bool(no: Int, value: Boolean) = long(no, if (value) 1 else 0)

    fun int(no: Int, value: Int) = long(no, value.toLong())

    fun long(no: Int, value: Long) {
        tag(no, WIRE_VARINT)
        varint(value)
    }

    fun string(no: Int, value: String) = bytes(no, value.toByteArray(Charsets.UTF_8))

    /** Writes nothing for null: an absent field reads back as null. */
    fun stringOrNull(no: Int, value: String?) {
        if (value != null) string(no, value)
    }

    fun bytes(no: Int, value: ByteArray) {
        tag(no, WIRE_BYTES)
        varint(value.size.toLong())
        put(value, 0, value.size)
    }

    /** A nested record; read it back with [TagReader.message]. */
    inline fun message(no: Int, body: TagWriter.() -> Unit) {
        val child = TagWriter()
        child.body()
        bytes(no, child.toByteArray())
    }

    /** A list as one nested record of repeated field 1, so an empty list is still present. */
    fun strings(no: Int, values: List<String>) = message(no) { values.forEach { string(1, it) } }

    /** Fields copied as they were read, see [TagReader.rawField]. */
    fun raw(fields: ByteArray) = put(fields, 0, fields.size)

    fun toByteArray(): ByteArray = buf.copyOf(size)

    fun writeTo(out: DataOutput) = out.write(buf, 0, size)

    private fun tag(no: Int, wireType: Int) = varint((no.toLong() shl 3) or wireType.toLong())

    @PublishedApi
    internal fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            putByte(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        putByte(v.toInt())
    }

    private fun putByte(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    private fun put(src: ByteArray, off: Int, len: Int) {
        ensure(len)
        System.arraycopy(src, off, buf, size, len)
        size += len
    }

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }
}

/**
 * Reads the fields of one record from `buf[pos until limit]`. Call [next] for each field number,
 * then exactly one of the value readers or [skip].
 */
class TagReader(private val buf: ByteArray, private var pos: Int, private val limit: Int) {
    private var wireType = -1
    private var fieldStart = 0

    /** The next field's number, or -1 at the end of the record. */
    fun next(): Int {
        if (pos >= limit) return -1
        fieldStart = pos
        val tag = varint()
        wireType = (tag and 7).toInt()
        val no = (tag ushr 3).toInt()
        if (no <= 0) throw IOException("Invalid field number $no")
        return no
    }

    fun bool(): Boolean = long() != 0L

    fun int(): Int = long().toInt()

    fun long(): Long {
        expect(WIRE_VARINT)
        return varint()
    }

    fun string(): String {
        val len = length()
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    /** A record written by [TagWriter.message]. */
    fun message(): TagReader {
        val len = length()
        val r = TagReader(buf, pos, pos + len)
        pos += len
        return r
    }

    /** A list written by [TagWriter.strings]. */
    fun strings(): List<String> {
        val r = message()
        val list = ArrayList<String>()
        while (true) {
            when (r.next()) {
                -1 -> return list
                1 -> list.add(r.string())
                else -> r.skip()
            }
        }
    }

    /** Skips the value of a field this build does not know. */
    fun skip() {
        when (wireType) {
            WIRE_VARINT -> varint()
            WIRE_FIXED64 -> advance(8)
            WIRE_BYTES -> advance(length0())
            WIRE_FIXED32 -> advance(4)
            else -> throw IOException("Unsupported wire type $wireType")
        }
    }

    /** Skips the current field and returns its tag and value as written, for [TagWriter.raw]. */
    fun rawField(): ByteArray {
        skip()
        return buf.copyOfRange(fieldStart, pos)
    }

    private fun expect(type: Int) {
        if (wireType != type) throw IOException("Wire type $wireType, expected $type")
    }

    private fun length(): Int {
        expect(WIRE_BYTES)
        return length0()
    }

    private fun length0(): Int {
        val len = varint()
        if (len < 0 || len > limit - pos) throw IOException("Invalid length $len")
        return len.toInt()
    }

    private fun advance(n: Int) {
        if (n > limit - pos) throw IOException("Field runs past the end")
        pos += n
    }

    internal fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= limit) throw IOException("Field runs past the end")
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IOException("Varint too long")
    }
}

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_BYTES = 2
private const val WIRE_FIXED32 = 5
