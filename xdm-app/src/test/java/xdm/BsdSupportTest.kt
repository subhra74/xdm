package xdm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.OS
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
}
