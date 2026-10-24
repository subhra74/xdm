package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.core.util.FileUtils
import xdm.core.util.getFileName
import xdm.core.util.getNameFromContentDisposition

/**
 * A server picks the Content-Disposition name. A trailing dot or space is one Windows strips when it
 * creates the file but not in front of ':', so `setup.exe.` landed as `setup.exe` while its
 * Zone.Identifier went to a separate empty file: the executable was saved unmarked.
 */
class TestUntrustedNames {
    @Test
    fun contentDisposition_dropsTrailingDotsAndSpaces() {
        assertEquals("setup.exe", getNameFromContentDisposition("attachment; filename=\"setup.exe.\""))
        assertEquals("setup.exe", getNameFromContentDisposition("attachment; filename=\"setup.exe . \""))
        assertEquals("setup.exe", getNameFromContentDisposition("attachment; filename*=UTF-8''setup.exe%2E"))
        assertEquals("setup.exe", getFileName("https://example.com/x", "attachment; filename=\"setup.exe.\""))
    }

    @Test
    fun contentDisposition_replacesCharactersWindowsRefuses() {
        assertEquals("a_b.zip", getNameFromContentDisposition("attachment; filename=\"a|b.zip\""))
        assertEquals("a_b.zip", getNameFromContentDisposition("attachment; filename=\"a%00b.zip\""))
        assertEquals("a_b.zip", getNameFromContentDisposition("attachment; filename=\"a%0Ab.zip\""))
    }

    /** "invoice‮fdp.exe" displays as "invoiceexe.pdf"; the override must not survive. */
    @Test
    fun invisibleFormatCharactersAreDropped() {
        assertEquals("invoicefdp.exe", FileUtils.sanitizeFileName("invoice‮fdp.exe"))
        assertEquals("invoicefdp.exe", getNameFromContentDisposition("attachment; filename*=UTF-8''invoice%E2%80%AEfdp.exe"))
        assertEquals("ab.txt", FileUtils.sanitizeFileName("a​b﻿.txt"))
        assertEquals("a_b.txt", FileUtils.sanitizeFileName("a\u0085b.txt"))
    }

    @Test
    fun ordinaryNamesAreUntouched() {
        assertEquals("Résumé (final) – v2.pdf", FileUtils.sanitizeFileName("Résumé (final) – v2.pdf"))
        assertEquals("日本語.mp4", getNameFromContentDisposition("attachment; filename*=UTF-8''%E6%97%A5%E6%9C%AC%E8%AA%9E.mp4"))
    }
}
