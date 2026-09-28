package xdm

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import xdm.app.utils.win.Ffm
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
        assumeTrue("no strlen in the default lookup", libc().find("strlen").isPresent)
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
            assertEquals("3 chars * 2 bytes + a 2-byte terminator", 8L, wide.byteSize())

            val raw = wide.toArray(ValueLayout.JAVA_BYTE)
            assertEquals("XDM", String(raw, 0, raw.size - 2, StandardCharsets.UTF_16LE))
        }
    }

    /** HKEY_CURRENT_USER and friends are pseudo-handles passed as plain pointers. */
    @Test
    fun pseudoHandlesCanBePassedAsPointers() {
        val hkcu = MemorySegment.ofAddress(0x80000002L)
        assertEquals(0x80000002L, hkcu.address())
    }
}
