// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.core.network.http.CertExceptions
import java.io.File

/** The certificate exception list: saved and loaded, corrupt files, add and remove. */
class TestCertExceptions {
    @TempDir
    lateinit var dir: File

    private val fpA = "a".repeat(64)
    private val fpB = "b".repeat(64)

    @Test
    fun roundTrip() {
        CertExceptions.init(dir.path)
        CertExceptions.add("Example.COM", fpA.uppercase())
        CertExceptions.add("other.example", fpB)
        val saved = CertExceptions.list()

        CertExceptions.init(dir.path)
        assertEquals(saved.toSet(), CertExceptions.list().toSet(), "same entries after loading")
        assertTrue(CertExceptions.isAccepted("example.com", fpA), "host and fingerprint normalized to lowercase")
        assertTrue(CertExceptions.isAccepted("other.example", fpB), "second entry")
        assertTrue(File(dir, CertExceptions.FILE_NAME).isFile, "saved under its file name")
    }

    @Test
    fun corruptOrMissingFile_isEmpty() {
        CertExceptions.init(dir.path)
        assertTrue(CertExceptions.list().isEmpty(), "missing file: empty")

        File(dir, CertExceptions.FILE_NAME).writeBytes(byteArrayOf(0, 0, 0, 1, 0x7f, 0x7f, 0x7f, 0x7f, 1, 2))
        CertExceptions.init(dir.path)
        assertTrue(CertExceptions.list().isEmpty(), "corrupt file: empty")

        File(dir, CertExceptions.FILE_NAME).writeText("not the format at all")
        CertExceptions.init(dir.path)
        assertTrue(CertExceptions.list().isEmpty(), "unknown version: empty")

        CertExceptions.add("example.com", fpA)
        CertExceptions.init(dir.path)
        assertTrue(CertExceptions.isAccepted("example.com", fpA), "a later save replaces the corrupt file")
    }

    @Test
    fun addAndRemove() {
        CertExceptions.init(dir.path)
        CertExceptions.add("example.com", fpA)
        CertExceptions.add("example.com", fpA)
        assertEquals(1, CertExceptions.list().size, "added once")
        assertTrue(CertExceptions.isAccepted("example.com", fpA), "accepted for its host")
        assertFalse(CertExceptions.isAccepted("other.example", fpA), "not for another host")
        assertFalse(CertExceptions.isAccepted("example.com", fpB), "not another certificate")
        assertTrue(CertExceptions.isAcceptedFingerprint(fpA), "fingerprint known")
        assertFalse(CertExceptions.isAcceptedFingerprint(fpB), "other fingerprint unknown")

        CertExceptions.remove("EXAMPLE.com", fpA)
        assertFalse(CertExceptions.isAccepted("example.com", fpA), "removed")
        assertFalse(CertExceptions.isAcceptedFingerprint(fpA), "fingerprint gone")
        CertExceptions.init(dir.path)
        assertTrue(CertExceptions.list().isEmpty(), "removal saved")
    }
}
