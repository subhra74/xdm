package xdm.app.utils.win

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * The few `User32` window calls the tray menu needs: finding a Java window's HWND by its title,
 * giving it the foreground and asking which process owns the foreground.
 *
 * Windows only - callers must check the OS.
 */
internal object Win32Window {

    // HWND FindWindowW(LPCWSTR lpClassName, LPCWSTR lpWindowName)
    private val findWindow by lazy {
        Win32.user("FindWindowW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
    }

    // BOOL SetForegroundWindow(HWND)
    private val setForegroundWindow by lazy {
        Win32.user("SetForegroundWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    }

    // HWND GetForegroundWindow()
    private val getForegroundWindow by lazy {
        Win32.user("GetForegroundWindow", FunctionDescriptor.of(ADDRESS))
    }

    // DWORD GetWindowThreadProcessId(HWND, LPDWORD)
    private val getWindowThreadProcessId by lazy {
        Win32.user("GetWindowThreadProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    }

    /**
     * Brings the top-level window titled [title] to the foreground. Windows allows it only to the
     * process that received the last input, which a tray icon click makes us. Returns whether it worked.
     */
    fun bringToForeground(title: String): Boolean = runCatching {
        Arena.ofConfined().use { arena ->
            val hwnd = findWindow.invokeExact(MemorySegment.NULL, arena.allocateFrom(title, Charsets.UTF_16LE)) as MemorySegment
            hwnd != MemorySegment.NULL && (setForegroundWindow.invokeExact(hwnd) as Int) != 0
        }
    }.getOrDefault(false)

    /** The HWND (as a number) of the foreground window, 0 if there is none or the call failed. */
    fun foregroundWindow(): Long = runCatching {
        (getForegroundWindow.invokeExact() as MemorySegment).address()
    }.getOrDefault(0L)

    /** Whether the foreground window belongs to this process. */
    fun isForegroundOurs(): Boolean = runCatching {
        Arena.ofConfined().use { arena ->
            val hwnd = getForegroundWindow.invokeExact() as MemorySegment
            if (hwnd == MemorySegment.NULL) return@use false
            val pid = arena.allocate(JAVA_INT)
            getWindowThreadProcessId.invokeExact(hwnd, pid) as Int
            pid.get(JAVA_INT, 0).toLong() == ProcessHandle.current().pid()
        }
    }.getOrDefault(false)
}
