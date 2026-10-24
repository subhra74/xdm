// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.core.util.getFileName

/**
 * The Content-Disposition name is the server's choice and is sanitized like a URL's. A trailing dot or
 * space is one Windows drops when it creates the file: `setup.exe.` landed as `setup.exe` while XDM
 * (and the Zone.Identifier stream it writes) still addressed `setup.exe.`.
 */
class TestDispositionFileName {
    private fun cd(header: String, contentType: String? = null) = getFileName("https://h/dl?id=1", header, contentType)

    @Test
    fun trailingDotOrSpaceIsDropped() {
        assertEquals("setup.exe", cd("attachment; filename=\"setup.exe.\""), "trailing dot")
        assertEquals("setup.exe", cd("attachment; filename=\"setup.exe . \""), "trailing dot and spaces")
        assertEquals("setup.exe", cd("attachment; filename*=UTF-8''setup.exe%2E"), "encoded trailing dot")
        assertEquals("setup.exe", cd("inline; filename=\"setup.exe...\""), "several trailing dots")
    }

    @Test
    fun charactersWindowsRefusesAreReplaced() {
        assertEquals("a_b.zip", cd("attachment; filename=\"a|b.zip\""), "pipe")
        assertEquals("a_b.zip", cd("attachment; filename=\"a%0Ab.zip\""), "newline")
        assertEquals("a_b.zip", cd("attachment; filename=\"a%00b.zip\""), "NUL")
        assertEquals("_CON.txt", cd("attachment; filename=\"CON.txt\""), "reserved device name")
    }

    @Test
    fun pathPartsNeverSurvive() {
        for (header in listOf(
            "attachment; filename=\"../../evil.exe\"",
            "attachment; filename=\"..\\\\..\\\\evil.exe\"",
            "attachment; filename*=UTF-8''..%2F..%2Fevil.exe",
        )) {
            val name = cd(header)
            assertEquals(false, name.contains('/') || name.contains('\\'), "separator in \"$name\" from $header")
        }
    }

    @Test
    fun sanitizedBeforeTheExtensionIsAdded() =
        assertEquals("report.pdf", cd("attachment; filename=\"report.\"", "application/pdf"), "no \"report..pdf\"")

    @Test
    fun ordinaryNamesAreUntouched() {
        assertEquals("Résumé (final) – v2.pdf", cd("attachment; filename*=UTF-8''R%C3%A9sum%C3%A9%20(final)%20%E2%80%93%20v2.pdf"))
        assertEquals("日本語.mp4", cd("attachment; filename*=UTF-8''%E6%97%A5%E6%9C%AC%E8%AA%9E.mp4"))
        assertEquals("v1.2.tar.gz", cd("attachment; filename=\"v1.2.tar.gz\""))
    }

    /** A redirect's name comes from the final URL, which was already sanitized; it still is. */
    @Test
    fun urlNameStillSanitized() =
        assertEquals("setup.exe", getFileName("https://cdn.h/files/setup.exe%2E", null, null))
}
