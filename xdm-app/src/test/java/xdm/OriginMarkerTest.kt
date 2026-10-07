package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xdm.app.utils.DownloadSource
import xdm.app.utils.OriginMarker
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

class OriginMarkerTest {
    private lateinit var dir: File

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-origin").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun cleanUrl_keepsPlainHttpUrls() {
        assertEquals("https://example.com/a?b=1#c", OriginMarker.cleanUrl("https://example.com/a?b=1#c"))
        assertEquals("HTTP://example.com", OriginMarker.cleanUrl("HTTP://example.com"))
    }

    @Test
    fun cleanUrl_dropsCredentials() {
        assertEquals("https://example.com/f", OriginMarker.cleanUrl("https://user:p@ss@example.com/f"))
        assertEquals("http://example.com:8080?q=a@b", OriginMarker.cleanUrl("http://u@example.com:8080?q=a@b"))
        assertEquals("https://example.com/x@y", OriginMarker.cleanUrl("https://example.com/x@y"), "an @ in the path is not userinfo")
    }

    @Test
    fun cleanUrl_removesLineBreaks() {
        assertEquals("https://example.com/aZoneId=0", OriginMarker.cleanUrl("https://example.com/a\r\nZoneId=0"))
    }

    @Test
    fun cleanUrl_rejectsOtherSchemesAndEmpty() {
        assertNull(OriginMarker.cleanUrl(null))
        assertNull(OriginMarker.cleanUrl(""))
        assertNull(OriginMarker.cleanUrl("ftp://example.com/f"))
        assertNull(OriginMarker.cleanUrl("file:///etc/passwd"))
        assertNull(OriginMarker.cleanUrl("blob:https://example.com/1"))
        assertNull(OriginMarker.cleanUrl("not a url"))
    }

    @Test
    fun zoneIdentifier_listsReferrerAndHost() {
        assertEquals(
            "[ZoneTransfer]\r\nZoneId=3\r\nReferrerUrl=https://r/\r\nHostUrl=https://h/f\r\n",
            OriginMarker.zoneIdentifier(DownloadSource("https://h/f", "https://r/"))
        )
    }

    @Test
    fun zoneIdentifier_leavesOutMissingUrls() {
        assertEquals("[ZoneTransfer]\r\nZoneId=3\r\n", OriginMarker.zoneIdentifier(DownloadSource(null, null)))
        assertEquals(
            "[ZoneTransfer]\r\nZoneId=3\r\nHostUrl=https://h/f\r\n",
            OriginMarker.zoneIdentifier(DownloadSource("https://h/f", null))
        )
    }

    @Test
    fun quarantineValue_isFlagsHexTimeAndAgent() {
        assertEquals("0081;6abe29e4;XDM;", OriginMarker.quarantineValue(0x6abe29e4))
    }

    @Test
    fun missingFile_returnsFalseWithoutThrowing() {
        assertFalse(OriginMarker.mark(File(dir, "missing.bin"), DownloadSource("https://h/f", null), xdm.app.OS.MacOS))
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun mac_writesQuarantineAttribute() {
        val file = File(dir, "a \$b 'c\".bin").apply { writeText("x") }

        assertTrue(OriginMarker.mark(file, DownloadSource("https://h/f", null)), "mark reported failure")

        val read = ProcessBuilder("/usr/bin/xattr", "-p", "com.apple.quarantine", file.absolutePath).start()
        val value = read.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        assertEquals(0, read.waitFor(), "attribute missing")
        assertTrue(Regex("0081;[0-9a-f]+;XDM;").matches(value), "unexpected value: $value")
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun windows_writesZoneIdentifierStream() {
        val file = File(dir, "a.exe").apply { writeText("x") }

        assertTrue(OriginMarker.mark(file, DownloadSource("https://h/f", "https://r/")), "mark reported failure")

        val stream = FileInputStream(file.absolutePath + ":Zone.Identifier").use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(OriginMarker.zoneIdentifier(DownloadSource("https://h/f", "https://r/")), stream)
        assertEquals("x", file.readText(), "file contents untouched")
        assertEquals(listOf("a.exe"), dir.list()!!.toList(), "no separate file created")
    }
}
