// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.util.isOutOfSpace
import java.io.IOException
import java.io.UncheckedIOException

/** [isOutOfSpace] tells a full disk from other I/O failures, on every OS's wording. */
class TestDiskErrors {

    @Test
    fun linuxAndMacMessage() {
        assertTrue(isOutOfSpace(IOException("No space left on device")), "plain ENOSPC text")
    }

    @Test
    fun androidErrnoMessage() {
        // How a SAF descriptor's ErrnoException reads once wrapped by the stream.
        val errno = Exception("write failed: ENOSPC (No space left on device)")
        assertTrue(isOutOfSpace(IOException(errno)), "ENOSPC in the cause")
    }

    @Test
    fun windowsMessage() {
        assertTrue(isOutOfSpace(IOException("There is not enough space on the disk")), "Windows disk-full text")
    }

    @Test
    fun deepCause() {
        val inner = IOException("No space left on device")
        val wrapped = RuntimeException("mux", UncheckedIOException("write", IOException("flush", inner)))
        assertTrue(isOutOfSpace(wrapped), "disk full several causes down")
    }

    @Test
    fun otherErrorsAreNotDiskFull() {
        assertFalse(isOutOfSpace(IOException("Input/output error")), "EIO is not a full disk")
        assertFalse(isOutOfSpace(IllegalStateException("bad TS packet")), "a parse error is not a full disk")
        assertFalse(isOutOfSpace(IOException()), "no message")
        assertFalse(isOutOfSpace(null), "no error")
    }
}
