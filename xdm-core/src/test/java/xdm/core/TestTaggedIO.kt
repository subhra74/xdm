// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import xdm.core.util.NotTaggedException
import xdm.core.util.StableId
import xdm.core.util.TagReader
import xdm.core.util.TagWriter
import xdm.core.util.TaggedFile
import xdm.core.util.byStableId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** The numbered-field format behind the config, the download lists and `task-<id>.info`. */
class TestTaggedIO {
    private inline fun file(body: TagWriter.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        TaggedFile.write(DataOutputStream(bytes), body)
        return bytes.toByteArray()
    }

    private fun read(bytes: ByteArray): TagReader = TaggedFile.read(DataInputStream(ByteArrayInputStream(bytes)))

    @Test
    fun valuesRoundTrip() {
        val big = "é".repeat(40_000)
        val r = read(file {
            bool(1, true)
            int(2, -7)
            long(3, Long.MIN_VALUE)
            long(4, Long.MAX_VALUE)
            string(5, big)
            strings(6, listOf("a", "", "c"))
            message(7) { int(1, 42) }
        })
        assertEquals(1, r.next(), "field 1")
        assertEquals(true, r.bool(), "bool")
        r.next(); assertEquals(-7, r.int(), "negative int")
        r.next(); assertEquals(Long.MIN_VALUE, r.long(), "min long")
        r.next(); assertEquals(Long.MAX_VALUE, r.long(), "max long")
        r.next(); assertEquals(big, r.string(), "string over 64 KB")
        r.next(); assertEquals(listOf("a", "", "c"), r.strings(), "list")
        r.next()
        val nested = r.message()
        assertEquals(1, nested.next(), "nested field")
        assertEquals(42, nested.int(), "nested value")
        assertEquals(-1, nested.next(), "end of nested record")
        assertEquals(-1, r.next(), "end of file")
    }

    @Test
    fun emptyListIsPresent() {
        val r = read(file { strings(1, emptyList()) })
        assertEquals(1, r.next(), "empty list still written")
        assertEquals(emptyList<String>(), r.strings(), "empty list")
    }

    @Test
    fun unknownFieldsOfEveryWireTypeAreSkipped() {
        val r = read(file {
            int(1, 1)
            long(50, 123)
            string(51, "newer")
            message(52) { string(1, "x") }
            int(2, 2)
        })
        val known = mutableMapOf<Int, Int>()
        while (true) {
            when (val no = r.next()) {
                -1 -> break
                1, 2 -> known[no] = r.int()
                else -> r.skip()
            }
        }
        assertEquals(mapOf(1 to 1, 2 to 2), known, "known fields around the unknown ones")
    }

    @Test
    fun rawFieldsAreWrittenBackUnchanged() {
        val original = file { int(1, 1); string(60, "kept"); message(61) { int(1, 5) } }
        val r = read(original)
        val copy = TagWriter()
        while (true) {
            when (r.next()) {
                -1 -> break
                1 -> copy.int(1, r.int())
                else -> copy.raw(r.rawField())
            }
        }
        assertArrayEquals(original, file { raw(copy.toByteArray()) }, "same bytes after a read and write")
    }

    @Test
    fun fileWithoutHeader_isNotTagged() {
        assertThrows(NotTaggedException::class.java, { read(byteArrayOf(0, 0, 0, 3, 1, 2)) }, "old format")
        assertThrows(NotTaggedException::class.java, { read(ByteArray(0)) }, "empty file")
    }

    @Test
    fun truncatedValue_fails() {
        val bytes = file { string(1, "hello") }
        val r = read(bytes.copyOf(bytes.size - 2))
        r.next()
        assertThrows(java.io.IOException::class.java, { r.string() }, "value past the end")
    }

    private enum class Color(override val id: Int) : StableId { RED(3), BLUE(1) }

    @Test
    fun enumsByStableId() {
        assertEquals(Color.RED, Color.entries.toTypedArray().byStableId(3), "known id")
        assertNull(Color.entries.toTypedArray().byStableId(2), "unknown id")
    }
}
