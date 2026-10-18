package xdm.app.utils.win

import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.charset.Charset

/**
 * The packaged `xdm-app.exe` is a GUI-subsystem launcher, so it starts with no console and its
 * stdout/stderr go nowhere. Headless mode attaches to the console of the shell that started it and
 * points `System.out`/`System.err` at it. Under `java -jar` (a console program) there is already a
 * console, the attach fails, and the streams are left alone. Output redirected to a file or pipe
 * (`xdm-app.exe --headless ... > log.txt`) already has a valid handle and is left alone too.
 */
internal object Win32Console {

    private const val ATTACH_PARENT_PROCESS = -1
    const val STD_INPUT_HANDLE = -10
    private const val STD_OUTPUT_HANDLE = -11
    const val STD_ERROR_HANDLE = -12

    /** Set once [attachToParent] has attached the parent's console. `System.in` is never wired to it. */
    @Volatile
    var attached = false
        private set

    /** Set once [attachToParent] has pointed `System.err` at the parent's console. */
    @Volatile
    var stderrAttached = false
        private set

    // HANDLE GetStdHandle(DWORD nStdHandle)
    private val getStdHandle by lazy {
        Win32.kernel("GetStdHandle", FunctionDescriptor.of(ADDRESS, JAVA_INT))
    }

    // UINT GetConsoleOutputCP(void)
    private val getConsoleOutputCP by lazy {
        Win32.kernel("GetConsoleOutputCP", FunctionDescriptor.of(JAVA_INT))
    }

    // BOOL GetConsoleMode(HANDLE hConsoleHandle, LPDWORD lpMode)
    private val getConsoleMode by lazy {
        Win32.kernel("GetConsoleMode", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    }

    // BOOL AttachConsole(DWORD dwProcessId)
    private val attachConsole by lazy {
        Win32.kernel("AttachConsole", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    }

    /**
     * Attaches to the parent's console and points each standard stream that has no handle at it.
     * Streams the shell redirected (`> out.txt`, `2> err.txt`, a pipe) keep their handle, so
     * `xdm-app.exe --headless ... > paths.txt` still shows progress on the console. Returns true when
     * a console was attached.
     */
    fun attachToParent(): Boolean {
        val outMissing = !hasHandle(STD_OUTPUT_HANDLE)
        val errMissing = !hasHandle(STD_ERROR_HANDLE)
        if (!outMissing && !errMissing) return false
        if ((attachConsole.invoke(ATTACH_PARENT_PROCESS) as Int) == 0) return false
        attached = true
        // Autoflush: progress lines end in '\r', not a newline. Encode for the console's code page
        // (437/850 on most consoles, 65001 when set to UTF-8), or non-ASCII file names come out garbled.
        val console = PrintStream(FileOutputStream("CONOUT$"), true, consoleCharset())
        if (outMissing) System.setOut(console)
        if (errMissing) {
            System.setErr(console)
            stderrAttached = true
        }
        return true
    }

    private fun hasHandle(std: Int): Boolean {
        val address = (getStdHandle.invoke(std) as MemorySegment).address()
        return address != 0L && address != -1L
    }

    /**
     * True when the standard handle [std] is a console (a conhost window, Windows Terminal, an SSH
     * ConPTY), false for a file, pipe or no handle at all. GetConsoleMode fails on anything else.
     */
    fun isConsole(std: Int): Boolean {
        if (!hasHandle(std)) return false
        val handle = getStdHandle.invoke(std) as MemorySegment
        Arena.ofConfined().use { arena ->
            val mode = arena.allocate(JAVA_INT)
            return (getConsoleMode.invoke(handle, mode) as Int) != 0
        }
    }

    private fun consoleCharset(): Charset {
        val cp = runCatching { getConsoleOutputCP.invoke() as Int }.getOrDefault(0)
        return when (cp) {
            0 -> Charset.defaultCharset()
            65001 -> Charsets.UTF_8
            else -> runCatching { Charset.forName("cp$cp") }.getOrElse { Charset.defaultCharset() }
        }
    }
}
