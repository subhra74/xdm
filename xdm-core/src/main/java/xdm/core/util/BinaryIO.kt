package xdm.core.util

import xdm.core.network.http.HeaderMap
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

/**
 * String and header serialisation for the download persistence files (`task-<id>.info`,
 * `<id>.state`).
 *
 * Strings are written as an `Int` byte length followed by UTF-8 bytes instead of
 * [DataOutput.writeUTF], which is limited to 64 KB and throws on larger values (big cookies, long
 * signed URLs). Read and write calls must stay in lockstep.
 */

/** Guards against allocating a huge buffer when reading a corrupt length. */
private const val MAX_STRING_BYTES = 64 * 1024 * 1024

fun DataOutput.writeLongString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

fun DataInput.readLongString(): String {
    val size = readInt()
    if (size < 0 || size > MAX_STRING_BYTES) throw IOException("Invalid string length: $size")
    val bytes = ByteArray(size)
    readFully(bytes)
    return String(bytes, Charsets.UTF_8)
}

fun DataOutput.writeNullableLongString(value: String?) {
    writeBoolean(value != null)
    value?.let { writeLongString(it) }
}

fun DataInput.readNullableLongString(): String? = if (readBoolean()) readLongString() else null

fun DataOutput.writeNullableHeaders(headers: HeaderMap?) {
    writeBoolean(headers != null)
    headers ?: return
    writeInt(headers.size)
    for ((name, values) in headers) {
        writeLongString(name)
        writeInt(values.size)
        values.forEach { writeLongString(it) }
    }
}

fun DataInput.readNullableHeaders(): HeaderMap? {
    if (!readBoolean()) return null
    val count = readInt()
    val headers = LinkedHashMap<String, List<String>>(count)
    repeat(count) {
        val name = readLongString()
        headers[name] = List(readInt()) { readLongString() }
    }
    return headers
}
