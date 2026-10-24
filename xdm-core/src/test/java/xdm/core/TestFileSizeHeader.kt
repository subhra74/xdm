// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.core.util.getFileSize

/** [getFileSize]: a 206's Content-Range total over its range-sized Content-Length. */
class TestFileSizeHeader {
    @Test
    fun rangeResponse_usesContentRangeTotal() {
        val headers = mapOf(
            "content-length" to listOf("100511507"),
            "Content-Range" to listOf("bytes 498794496-599306002/599306003"),
        )
        assertEquals(599306003L, getFileSize(headers), "total from Content-Range")
    }

    @Test
    fun fullResponse_usesContentLength() {
        assertEquals(1234L, getFileSize(mapOf("Content-Length" to listOf("1234"))), "no Content-Range")
    }

    @Test
    fun unknownTotal_fallsBackToContentLength() {
        val headers = mapOf("content-length" to listOf("100"), "content-range" to listOf("bytes 0-99/*"))
        assertEquals(100L, getFileSize(headers), "total given as *")
    }

    @Test
    fun noSize_isNull() {
        assertNull(getFileSize(mapOf("content-length" to listOf("abc"))), "malformed length")
        assertNull(getFileSize(null), "no headers")
    }
}
