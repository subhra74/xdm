package xdm

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.utils.AutoStart

/**
 * The Windows `Explorer\StartupApproved\Run` flag the settings toggle shares with Task Manager. Reading
 * it wrong shows autostart on after Task Manager disabled it; writing it wrong leaves Explorer
 * starting (or not starting) XDM regardless of the toggle.
 */
class AutoStartApprovalTest {

    @Test
    fun missingOrEmptyFlagIsEnabled() {
        assertTrue(AutoStart.isApproved(null), "no flag")
        assertTrue(AutoStart.isApproved(ByteArray(0)), "empty flag")
    }

    @Test
    fun evenStateIsEnabledAndOddIsDisabled() {
        assertTrue(AutoStart.isApproved(byteArrayOf(2, 0, 0, 0)), "state 2")
        assertTrue(AutoStart.isApproved(byteArrayOf(6, 0, 0, 0)), "state 6")
        assertFalse(AutoStart.isApproved(byteArrayOf(3, 0, 0, 0)), "state 3")
        assertFalse(AutoStart.isApproved(byteArrayOf(7, 0, 0, 0)), "state 7")
    }

    @Test
    fun enabledFlagIsStateTwoWithNoTime() {
        val flag = AutoStart.approvedFlag(true)
        assertArrayEquals(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), flag, "enabled flag")
        assertTrue(AutoStart.isApproved(flag), "reads back as enabled")
    }

    @Test
    fun disabledFlagCarriesFiletime() {
        // 1970-01-01 is 116444736000000000 ticks (0x019DB1DED53E8000) after 1601-01-01.
        val flag = AutoStart.approvedFlag(false, nowMillis = 0)
        assertEquals(12, flag.size, "flag length")
        assertEquals(3.toByte(), flag[0], "state byte")
        val ticks = (0 until 8).fold(0L) { acc, i -> acc or ((flag[4 + i].toLong() and 0xFF) shl (8 * i)) }
        assertEquals(116_444_736_000_000_000L, ticks, "FILETIME of the Unix epoch")
        assertFalse(AutoStart.isApproved(flag), "reads back as disabled")
    }
}
