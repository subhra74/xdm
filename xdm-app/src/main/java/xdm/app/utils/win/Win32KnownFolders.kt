package xdm.app.utils.win

import xdm.core.util.Logger
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.nio.charset.StandardCharsets

/**
 * `SHGetKnownFolderPath`: where a known folder such as Downloads really is. The profile folder plus a
 * fixed name is only its default: the user can move it (Downloads > Properties > Location) and policy
 * can redirect it. This is the lookup Explorer does for `shell:Downloads`. No COM needed.
 *
 * Windows only - callers must check the OS.
 */
internal object Win32KnownFolders {

    private const val FOLDERID_DOWNLOADS = "374DE290-123F-4565-9164-39C4925E467B"

    // The path even when the folder is missing: a moved folder that was deleted, or sits on a drive
    // that is not plugged in, is still where the user wants downloads, not the profile default.
    private const val KF_FLAG_DONT_VERIFY = 0x00004000

    // HRESULT SHGetKnownFolderPath(REFKNOWNFOLDERID, DWORD, HANDLE, PWSTR*)
    private val getKnownFolderPath by lazy {
        Win32.shell("SHGetKnownFolderPath", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS))
    }

    // void CoTaskMemFree(LPVOID)
    private val coTaskMemFree by lazy { Win32.ole("CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS)) }

    /** The Downloads folder of the current user, or null when Windows could not say. Never throws. */
    fun downloads(): String? = path("Downloads", FOLDERID_DOWNLOADS)

    private fun path(name: String, id: String): String? = runCatching {
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(ADDRESS)
            val hr = getKnownFolderPath.invoke(arena.guid(id), KF_FLAG_DONT_VERIFY, MemorySegment.NULL, out) as Int
            val text = out.get(ADDRESS, 0)
            try {
                if (hr < 0 || text == MemorySegment.NULL) {
                    Logger.info("SHGetKnownFolderPath($name) failed: 0x${Integer.toHexString(hr)}")
                    null
                } else {
                    text.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE)
                }
            } finally {
                // Freed whether the call succeeded or not, as its documentation asks; null is a no-op.
                coTaskMemFree.invoke(text)
            }
        }
    }.getOrElse {
        Logger.error("SHGetKnownFolderPath($name) failed", it)
        null
    }

    /** A GUID struct from its text form: Data1-3 in native (little-endian) order, Data4 as written. */
    internal fun Arena.guid(text: String): MemorySegment {
        val hex = text.replace("-", "")
        require(hex.length == 32) { "Not a GUID: $text" }
        val guid = allocate(16, 4)
        guid.set(JAVA_INT, 0, hex.substring(0, 8).toLong(16).toInt())
        guid.set(JAVA_SHORT, 4, hex.substring(8, 12).toInt(16).toShort())
        guid.set(JAVA_SHORT, 6, hex.substring(12, 16).toInt(16).toShort())
        for (i in 0 until 8) guid.set(JAVA_BYTE, 8L + i, hex.substring(16 + 2 * i, 18 + 2 * i).toInt(16).toByte())
        return guid
    }
}
