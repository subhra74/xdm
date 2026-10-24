package xdm.app.utils.win

import xdm.core.util.Logger
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
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

    private const val SEE_MASK_IDLIST = 0x00000004
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

    // BOOL ShellExecuteExW(SHELLEXECUTEINFOW*), keeping GetLastError: the capture segment comes first.
    private val shellExecuteEx by lazy {
        Win32.shell(
            "ShellExecuteExW", FunctionDescriptor.of(JAVA_INT, ADDRESS),
            Linker.Option.captureCallState("GetLastError")
        )
    }
    private val CAPTURE = Linker.Option.captureStateLayout()
    private const val ERROR_CANCELLED = 1223

    /**
     * Opens [file] with [parameters]. Returns false when Windows cannot find or start it; no error
     * dialog is shown for that (`SEE_MASK_FLAG_NO_UI`), so the caller can fall back quietly.
     */
    fun execute(file: String, parameters: String? = null): Boolean = runCatching {
        Arena.ofConfined().use { arena ->
            val info = newInfo(arena, SEE_MASK_NOASYNC or SEE_MASK_FLAG_NO_UI)
            info.set(ADDRESS, INFO.byteOffset(MemoryLayout.PathElement.groupElement("lpVerb")), arena.wide("open"))
            info.set(ADDRESS, INFO.byteOffset(MemoryLayout.PathElement.groupElement("lpFile")), arena.wide(file))
            if (parameters != null) {
                info.set(
                    ADDRESS, INFO.byteOffset(MemoryLayout.PathElement.groupElement("lpParameters")),
                    arena.wide(parameters)
                )
            }
            shellExecute(arena, info) == 0
        }
    }.getOrElse {
        Logger.error("ShellExecuteExW($file) failed", it)
        false
    }

    /**
     * Opens a downloaded [path] with its default application the way an Explorer double-click does, so
     * the shell runs its zone checks on the Zone.Identifier mark (SmartScreen). Done as Chrome does
     * (`ui/base/win/shell.cc`, `OpenFileViaShell`): the default verb, so a type with no app gets the
     * "Select an app" picker; the file as an ID list, which skips the shell's legacy path resolution
     * (appending `.exe`, `.lnk`); its folder as the working directory; no owner window; UI allowed.
     * COM is initialised, as the shell may hand the launch to a COM handler (Store apps,
     * `DelegateExecute`). Never on the EDT: it waits for the user to answer a prompt.
     *
     * True when the file was opened or the user cancelled a prompt; false when it failed (Windows has
     * then usually shown its own error) or the call could not be made.
     */
    fun openFile(path: String): Boolean = runCatching {
        withCom {
            Arena.ofConfined().use { arena ->
                val pidlOut = arena.allocate(ADDRESS)
                val hr = parseDisplayName.invoke(arena.wide(path), MemorySegment.NULL, pidlOut, 0, MemorySegment.NULL) as Int
                val pidl = pidlOut.get(ADDRESS, 0)
                if (hr < 0 || pidl == MemorySegment.NULL) {
                    Logger.info("SHParseDisplayName($path) failed: 0x${Integer.toHexString(hr)}")
                    return@runCatching false
                }
                try {
                    val info = newInfo(arena, SEE_MASK_NOASYNC or SEE_MASK_IDLIST)
                    info.set(ADDRESS, INFO.byteOffset(MemoryLayout.PathElement.groupElement("lpIDList")), pidl)
                    File(path).parent?.let {
                        info.set(ADDRESS, INFO.byteOffset(MemoryLayout.PathElement.groupElement("lpDirectory")), arena.wide(it))
                    }
                    val error = shellExecute(arena, info)
                    if (error != 0 && error != ERROR_CANCELLED) Logger.info("ShellExecuteExW($path) failed: $error")
                    error == 0 || error == ERROR_CANCELLED
                } finally {
                    ilFree.invoke(pidl)
                }
            }
        }
    }.getOrElse {
        Logger.error("ShellExecuteExW($path) failed", it)
        false
    }

    /** A zeroed SHELLEXECUTEINFOW with its size, [mask] and SW_SHOWNORMAL; the verb is null (the default). */
    private fun newInfo(arena: Arena, mask: Int): MemorySegment {
        val info = arena.allocate(INFO)
        info.set(JAVA_INT, INFO.byteOffset(MemoryLayout.PathElement.groupElement("cbSize")), INFO.byteSize().toInt())
        info.set(JAVA_INT, INFO.byteOffset(MemoryLayout.PathElement.groupElement("fMask")), mask)
        info.set(JAVA_INT, INFO.byteOffset(MemoryLayout.PathElement.groupElement("nShow")), SW_SHOWNORMAL)
        return info
    }

    /** Calls ShellExecuteExW on [info]; 0 on success, else the GetLastError code. */
    private fun shellExecute(arena: Arena, info: MemorySegment): Int {
        val capture = arena.allocate(CAPTURE)
        if ((shellExecuteEx.invoke(capture, info) as Int) != 0) return 0
        val error = capture.get(JAVA_INT, CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError")))
        // A failure that left no error code still has to read as a failure.
        return if (error == 0) -1 else error
    }

    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val COINIT_DISABLE_OLE1DDE = 0x4

    // HRESULT CoInitializeEx(LPVOID, DWORD) / void CoUninitialize()
    private val coInitializeEx by lazy { Win32.ole("CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)) }
    private val coUninitialize by lazy { Win32.ole("CoUninitialize", FunctionDescriptor.ofVoid()) }

    // PIDLIST_ABSOLUTE ILCreateFromPathW(PCWSTR) / void ILFree(PIDLIST_RELATIVE)
    private val ilCreateFromPath by lazy { Win32.shell("ILCreateFromPathW", FunctionDescriptor.of(ADDRESS, ADDRESS)) }
    private val ilFree by lazy { Win32.shell("ILFree", FunctionDescriptor.ofVoid(ADDRESS)) }

    // HRESULT SHParseDisplayName(PCWSTR, IBindCtx*, PIDLIST_ABSOLUTE*, SFGAOF, SFGAOF*)
    private val parseDisplayName by lazy {
        Win32.shell("SHParseDisplayName", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS))
    }

    // HRESULT SHOpenFolderAndSelectItems(PCIDLIST_ABSOLUTE, UINT, PCUITEMID_CHILD_ARRAY, DWORD)
    private val openFolderAndSelectItems by lazy {
        Win32.shell("SHOpenFolderAndSelectItems", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))
    }

    /**
     * Opens Explorer on the folder holding [path] with that item selected, reusing a window already showing
     * the folder. Unlike `explorer /select,` there is no command line to parse, so commas and quotes in the
     * name are fine. Initialises COM on the calling thread, so call it from a thread of its own, not the EDT.
     */
    fun selectInExplorer(path: String): Boolean = runCatching {
        withCom {
            Arena.ofConfined().use { arena ->
                val pidl = ilCreateFromPath.invoke(arena.wide(path)) as MemorySegment
                if (pidl == MemorySegment.NULL) return@runCatching false
                try {
                    // cidl = 0: the pidl names the item itself, and Explorer selects it in its parent.
                    (openFolderAndSelectItems.invoke(pidl, 0, MemorySegment.NULL, 0) as Int) >= 0
                } finally {
                    ilFree.invoke(pidl)
                }
            }
        }
    }.getOrElse {
        Logger.error("SHOpenFolderAndSelectItems($path) failed", it)
        false
    }

    /** Runs [block] with COM initialised (STA) on the calling thread. */
    private inline fun <T> withCom(block: () -> T): T {
        // S_FALSE (already initialised) is a success too and needs its CoUninitialize.
        val init = coInitializeEx.invoke(MemorySegment.NULL, COINIT_APARTMENTTHREADED or COINIT_DISABLE_OLE1DDE) as Int
        try {
            return block()
        } finally {
            if (init >= 0) coUninitialize.invoke()
        }
    }

    private fun Arena.wide(text: String): MemorySegment = allocateFrom(text, StandardCharsets.UTF_16LE)
}
