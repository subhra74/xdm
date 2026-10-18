package xdm.app.headless

import xdm.app.utils.win.Win32Console
import xdm.core.util.PlatformUtils
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * Whether each standard stream is an interactive terminal, so the CLI can behave in pipes, `2> log`,
 * cron, CI and scheduled tasks. `System.console().isTerminal` cannot answer this: it describes stdin
 * and stdout together, while progress goes to stderr and a prompt needs stdin.
 */
object Terminal {
    /** stderr is a terminal: progress can be redrawn in place with `\r`. */
    val stderrInteractive: Boolean by lazy { !dumb && isTty(2) }

    /** stdin is a terminal a person can type a user name and password into. */
    val stdinInteractive: Boolean by lazy { !dumb && isTty(0) }

    /** `TERM=dumb` (Emacs shells, some CI runners): a terminal that cannot redraw a line. */
    private val dumb: Boolean get() = System.getenv("TERM").equals("dumb", ignoreCase = true)

    private fun isTty(fd: Int): Boolean = runCatching {
        if (PlatformUtils.isWindows) windowsIsConsole(fd) else posixIsatty(fd)
    }.getOrDefault(false)

    private fun windowsIsConsole(fd: Int): Boolean = when {
        // xdm-app.exe pointed System.err at the console it attached to (its own handle was empty).
        fd == 2 && Win32Console.stderrAttached -> true
        // Attached after startup: the JVM's stdin was opened before, on no handle, so nobody can type into it.
        fd == 0 && Win32Console.attached -> false
        else -> Win32Console.isConsole(if (fd == 0) Win32Console.STD_INPUT_HANDLE else Win32Console.STD_ERROR_HANDLE)
    }

    // int isatty(int fd)
    private val isatty by lazy {
        val linker = Linker.nativeLinker()
        val symbol = linker.defaultLookup().find("isatty").orElseThrow()
        linker.downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    }

    private fun posixIsatty(fd: Int): Boolean = (isatty.invoke(fd) as Int) == 1
}
