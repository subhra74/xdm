package xdm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.utils.isBsd

class BsdSupportTest {

    @Test
    fun isBsd_matchesTheBsds() {
        listOf("FreeBSD", "OpenBSD", "NetBSD", "DragonFly", "MidnightBSD").forEach {
            assertTrue(isBsd(it), it)
        }
    }

    @Test
    fun isBsd_rejectsOtherSystems() {
        listOf("Linux", "Mac OS X", "Windows 11", "SunOS", "").forEach {
            assertFalse(isBsd(it), it)
        }
        assertFalse(isBsd(null))
    }
}
