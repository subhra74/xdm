package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import xdm.app.utils.win.Ffm
import xdm.app.utils.win.Win32Registry
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * The FFM plumbing the Windows layers are built on, exercised against the C library so it can run
 * on any platform. The Win32-specific pieces need Windows and are covered by their own tests there;
 * what is checked here is the part that would break silently everywhere: that a downcall bound in
 * Kotlin actually reaches native code and returns the right value, and that the UTF-16 allocation
 * every `Reg*W` call depends on is laid out the way those APIs expect.
 */
class FfmDowncallTest {

    private fun libc() = Ffm.linker.defaultLookup()

    @Test
    fun downcallReachesNativeCodeAndReturnsItsValue() {
        assumeTrue(libc().find("strlen").isPresent, "no strlen in the default lookup")
        val strlen = Ffm.downcall(
            libc(), "strlen",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
        )
        Arena.ofConfined().use { arena ->
            val text: MemorySegment = arena.allocateFrom("Xtreme Download Manager")
            assertEquals(23L, strlen.invoke(text) as Long)
        }
    }

    /**
     * `RegSetValueExW` is handed the byte count of the string *including* its terminator, and
     * `RegQueryValueExW` hands one back the same way. Both rely on this allocation shape.
     */
    @Test
    fun wideStringsAreNullTerminatedUtf16() {
        Arena.ofConfined().use { arena ->
            val wide = arena.allocateFrom("XDM", StandardCharsets.UTF_16LE)
            assertEquals(8L, wide.byteSize(), "3 chars * 2 bytes + a 2-byte terminator")

            val raw = wide.toArray(ValueLayout.JAVA_BYTE)
            assertEquals("XDM", String(raw, 0, raw.size - 2, StandardCharsets.UTF_16LE))
        }
    }

    /**
     * HKEY_CURRENT_USER and friends are pseudo-handles passed as plain pointers.
     *
     * Pinned against the literal from winreg.h rather than against itself: the predefined handles
     * are adjacent values, 0x80000002 is HKEY_LOCAL_MACHINE, and getting it wrong shows up only as
     * an ERROR_ACCESS_DENIED at runtime on Windows. Reading the constant out of [Win32Registry]
     * touches no native symbol, so this runs on any platform.
     */
    @Test
    fun pseudoHandlesCanBePassedAsPointers() {
        assertEquals(0x80000001L, Win32Registry.HKEY_CURRENT_USER.address(), "HKEY_CURRENT_USER")
    }
}
