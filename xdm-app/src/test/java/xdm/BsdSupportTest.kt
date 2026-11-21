package xdm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.OS
import xdm.app.utils.TorrentLibrary
import xdm.app.utils.detectOS

class BsdSupportTest {

    @Test
    fun detectOS_mapsOsName() {
        assertEquals(OS.BSD, detectOS("FreeBSD"))
        assertEquals(OS.BSD, detectOS("OpenBSD"))
        assertEquals(OS.BSD, detectOS("NetBSD"))
        assertEquals(OS.BSD, detectOS("DragonFly"))
        assertEquals(OS.BSD, detectOS("MidnightBSD"))
        assertEquals(OS.Linux, detectOS("Linux"))
        assertEquals(OS.Linux, detectOS(null))
        assertEquals(OS.Linux, detectOS("SunOS"))
        assertEquals(OS.Linux, detectOS(""))
        assertEquals(OS.MacOS, detectOS("Mac OS X"))
        assertEquals(OS.Windows, detectOS("Windows 11"))
    }

    @Test
    fun isXdg_linuxAndBsdOnly() {
        assertTrue(OS.Linux.isXdg)
        assertTrue(OS.BSD.isXdg)
        assertFalse(OS.MacOS.isXdg)
        assertFalse(OS.Windows.isXdg)
    }

    @Test
    fun torrentPlatform_bsdHasItsOwnName() {
        // Unchanged for the platforms with builds.
        assertEquals("windows-x64", TorrentLibrary.platformFor("Windows 11", "amd64"))
        assertEquals("windows-arm64", TorrentLibrary.platformFor("Windows 11", "aarch64"))
        assertEquals("macos-arm64", TorrentLibrary.platformFor("Mac OS X", "aarch64"))
        assertEquals("macos-x64", TorrentLibrary.platformFor("Mac OS X", "x86_64"))
        assertEquals("linux-x64", TorrentLibrary.platformFor("Linux", "amd64"))
        assertEquals("linux-arm64", TorrentLibrary.platformFor("Linux", "aarch64"))
        // The BSDs: no build yet, and never the Linux one.
        assertEquals("freebsd-x64", TorrentLibrary.platformFor("FreeBSD", "amd64"))
        assertEquals("freebsd-arm64", TorrentLibrary.platformFor("FreeBSD", "aarch64"))
        assertEquals("openbsd-x64", TorrentLibrary.platformFor("OpenBSD", "amd64"))
        assertEquals("dragonfly-x64", TorrentLibrary.platformFor("DragonFly", "x86_64"))
    }
}
