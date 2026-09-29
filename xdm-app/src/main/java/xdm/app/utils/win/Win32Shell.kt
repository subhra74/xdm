package xdm.app.utils.win

import xdm.core.util.Logger
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.charset.StandardCharsets

/**
 * `ShellExecuteExW`, for starting a program the way the Run box does: a bare `chrome.exe` is
 * resolved through the per-user and machine-wide `App Paths` registrations (both registry views),
 * and a URL like `microsoft-edge:...` goes to its protocol handler.
 *
 * Windows only - callers must check the OS.
 */
internal object Win32Shell {

    private const val SEE_MASK_NOASYNC = 0x00000100
    private const val SEE_MASK_FLAG_NO_UI = 0x00000400
    private const val SW_SHOWNORMAL = 1

    // SHELLEXECUTEINFOW; padding is left to the layout so offsets match the native struct.
    private val INFO = MemoryLayout.structLayout(
        JAVA_INT.withName("cbSize"),
        JAVA_INT.withName("fMask"),
        ADDRESS.withName("hwnd"),
        ADDRESS.withName("lpVerb"),
        ADDRESS.withName("lpFile"),
        ADDRESS.withName("lpParameters"),
        ADDRESS.withName("lpDirectory"),
        JAVA_INT.withName("nShow"),
        MemoryLayout.paddingLayout(ADDRESS.byteSize() - JAVA_INT.byteSize()),
        ADDRESS.withName("hInstApp"),
        ADDRESS.withName("lpIDList"),
        ADDRESS.withName("lpClass"),
        ADDRESS.withName("hkeyClass"),
        JAVA_INT.withName("dwHotKey"),
        MemoryLayout.paddingLayout(ADDRESS.byteSize() - JAVA_INT.byteSize()),
        ADDRESS.withName("hIconOrMonitor"),
        ADDRESS.withName("hProcess"),
    )

    // BOOL ShellExecuteExW(SHELLEXECUTEINFOW*)
    private val shellExecuteEx by lazy {
        Win32.shell("ShellExecuteExW", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    }

    /**
     * Opens [file] with [parameters]. Returns false when Windows cannot find or start it; no error
     * dialog is shown for that (`SEE_MASK_FLAG_NO_UI`), so the caller can fall back quietly.
     */
    fun execute(file: String, parameters: String? = null): Boolean = runCatching {
        Arena.ofConfined().use { arena ->
            val info = arena.allocate(INFO)
            info.set(JAVA_INT, INFO.byteOffset(MemoryLayout.PathElement.groupElement("cbSize")), INFO.byteSize().toInt())
            info.set(
                JAVA_INT, INFO.byteOffset(MemoryLayout.PathElement.groupElement("fMask")),
                SEE_MASK_NOASYNC or SEE_MASK_FLAG_NO_UI
            )
            info.set(ADDRESS, INFO.byteOffset(MemoryLayout.PathElement.groupElement("lpVerb")), arena.wide("open"))
            info.set(ADDRESS, INFO.byteOffset(MemoryLayout.PathElement.groupElement("lpFile")), arena.wide(file))
            if (parameters != null) {
                info.set(
                    ADDRESS, INFO.byteOffset(MemoryLayout.PathElement.groupElement("lpParameters")),
                    arena.wide(parameters)
                )
            }
            info.set(JAVA_INT, INFO.byteOffset(MemoryLayout.PathElement.groupElement("nShow")), SW_SHOWNORMAL)
            (shellExecuteEx.invoke(info) as Int) != 0
        }
    }.getOrElse {
        Logger.error("ShellExecuteExW($file) failed", it)
        false
    }

    private fun Arena.wide(text: String): MemorySegment = allocateFrom(text, StandardCharsets.UTF_16LE)
}
