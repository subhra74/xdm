package xdm.app.utils.win

import xdm.core.util.Logger
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.charset.StandardCharsets

/**
 * The slice of the Windows registry XDM needs, called directly through java.lang.foreign.
 *
 * This replaces shelling out to `reg.exe` (and to `reg import` with a hand-built UTF-16LE `.reg`
 * file). Besides the process spawn per operation, that approach had to get two layers of escaping
 * right - `.reg` syntax on top of command-line quoting - for values that are Windows paths full of
 * backslashes and spaces. `RegSetValueExW` takes the string as-is, so both layers disappear.
 *
 * Every operation is scoped to `HKEY_CURRENT_USER`: everything XDM registers (its login entry, its
 * URL scheme) is per-user and needs no elevation. Paths are therefore relative to HKCU, e.g.
 * `Software\Classes\xdm-app`.
 *
 * Windows only - callers must check the OS. Failures are logged and reported as `null`/`false`
 * rather than thrown: none of this is worth failing a launch over.
 */
internal object Win32Registry {

    /**
     * Pseudo-handle; passed like any other HKEY. The predefined handles sit next to each other -
     * 0x80000000 is HKEY_CLASSES_ROOT and 0x80000002 is HKEY_LOCAL_MACHINE - and picking the wrong
     * one fails as a bare ERROR_ACCESS_DENIED from an unelevated process, so leave this alone.
     */
    internal val HKEY_CURRENT_USER = MemorySegment.ofAddress(0x80000001L)

    private const val ERROR_SUCCESS = 0
    private const val ERROR_FILE_NOT_FOUND = 2

    private const val KEY_READ = 0x20019
    private const val KEY_WRITE = 0x20006
    private const val REG_SZ = 1
    private const val REG_OPTION_NON_VOLATILE = 0

    // LSTATUS RegOpenKeyExW(HKEY, LPCWSTR, DWORD, REGSAM, PHKEY)
    private val regOpenKeyEx by lazy {
        Win32.advapi("RegOpenKeyExW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS))
    }

    // LSTATUS RegCreateKeyExW(HKEY, LPCWSTR, DWORD, LPWSTR, DWORD, REGSAM, LPSECURITY_ATTRIBUTES,
    //                         PHKEY, LPDWORD)
    private val regCreateKeyEx by lazy {
        Win32.advapi(
            "RegCreateKeyExW",
            FunctionDescriptor.of(
                JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS
            )
        )
    }

    // LSTATUS RegQueryValueExW(HKEY, LPCWSTR, LPDWORD, LPDWORD, LPBYTE, LPDWORD)
    private val regQueryValueEx by lazy {
        Win32.advapi(
            "RegQueryValueExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
        )
    }

    // LSTATUS RegSetValueExW(HKEY, LPCWSTR, DWORD, DWORD, const BYTE*, DWORD)
    private val regSetValueEx by lazy {
        Win32.advapi(
            "RegSetValueExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        )
    }

    // LSTATUS RegDeleteValueW(HKEY, LPCWSTR)
    private val regDeleteValue by lazy {
        Win32.advapi("RegDeleteValueW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    }

    // LSTATUS RegDeleteTreeW(HKEY, LPCWSTR)
    private val regDeleteTree by lazy {
        Win32.advapi("RegDeleteTreeW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    }

    // LSTATUS RegCloseKey(HKEY)
    private val regCloseKey by lazy {
        Win32.advapi("RegCloseKey", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    }

    /**
     * Reads a string value, or null when the key or value does not exist (or is not a string).
     *
     * [valueName] is `""` for a key's default value, which is how the URL-scheme keys store their
     * payload.
     */
    fun getString(path: String, valueName: String): String? = runCatching {
        Arena.ofConfined().use { arena ->
            val keyOut = arena.allocate(ADDRESS)
            val open = regOpenKeyEx.invoke(
                HKEY_CURRENT_USER, arena.wide(path), 0, KEY_READ, keyOut
            ) as Int
            if (open != ERROR_SUCCESS) {
                if (open != ERROR_FILE_NOT_FOUND) log("RegOpenKeyExW", path, open)
                return null
            }
            val key = keyOut.get(ADDRESS, 0)
            try {
                val name = arena.wide(valueName)
                val sizeOut = arena.allocate(JAVA_INT)

                // First call sizes the buffer, second one fills it - the usual Win32 two-step.
                var status = regQueryValueEx.invoke(
                    key, name, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, sizeOut
                ) as Int
                if (status != ERROR_SUCCESS) {
                    if (status != ERROR_FILE_NOT_FOUND) log("RegQueryValueExW", "$path\\$valueName", status)
                    return null
                }
                val bytes = sizeOut.get(JAVA_INT, 0)
                if (bytes <= 0) return ""

                val buffer = arena.allocate(bytes.toLong())
                status = regQueryValueEx.invoke(
                    key, name, MemorySegment.NULL, MemorySegment.NULL, buffer, sizeOut
                ) as Int
                if (status != ERROR_SUCCESS) {
                    log("RegQueryValueExW", "$path\\$valueName", status)
                    return null
                }
                decodeWide(buffer, sizeOut.get(JAVA_INT, 0))
            } finally {
                regCloseKey.invoke(key)
            }
        }
    }.getOrElse {
        Logger.error("Registry: reading $path\\$valueName failed", it)
        null
    }

    /** Creates [path] if needed and writes a REG_SZ value ([valueName] `""` = the default value). */
    fun setString(path: String, valueName: String, data: String): Boolean = runCatching {
        Arena.ofConfined().use { arena ->
            val keyOut = arena.allocate(ADDRESS)
            val created = regCreateKeyEx.invoke(
                HKEY_CURRENT_USER, arena.wide(path), 0, MemorySegment.NULL,
                REG_OPTION_NON_VOLATILE, KEY_WRITE, MemorySegment.NULL, keyOut, MemorySegment.NULL
            ) as Int
            if (created != ERROR_SUCCESS) {
                log("RegCreateKeyExW", path, created)
                return false
            }
            val key = keyOut.get(ADDRESS, 0)
            try {
                // allocateFrom appends the terminator, and cbData has to count it.
                val value = arena.wide(data)
                val status = regSetValueEx.invoke(
                    key, arena.wide(valueName), 0, REG_SZ, value, value.byteSize().toInt()
                ) as Int
                if (status != ERROR_SUCCESS) {
                    log("RegSetValueExW", "$path\\$valueName", status)
                    return false
                }
                true
            } finally {
                regCloseKey.invoke(key)
            }
        }
    }.getOrElse {
        Logger.error("Registry: writing $path\\$valueName failed", it)
        false
    }

    /** Deletes one value. A value that is already gone counts as success. */
    fun deleteValue(path: String, valueName: String): Boolean = runCatching {
        Arena.ofConfined().use { arena ->
            val keyOut = arena.allocate(ADDRESS)
            val open = regOpenKeyEx.invoke(
                HKEY_CURRENT_USER, arena.wide(path), 0, KEY_WRITE, keyOut
            ) as Int
            if (open == ERROR_FILE_NOT_FOUND) return true
            if (open != ERROR_SUCCESS) {
                log("RegOpenKeyExW", path, open)
                return false
            }
            val key = keyOut.get(ADDRESS, 0)
            try {
                val status = regDeleteValue.invoke(key, arena.wide(valueName)) as Int
                if (status != ERROR_SUCCESS && status != ERROR_FILE_NOT_FOUND) {
                    log("RegDeleteValueW", "$path\\$valueName", status)
                    return false
                }
                true
            } finally {
                regCloseKey.invoke(key)
            }
        }
    }.getOrElse {
        Logger.error("Registry: deleting $path\\$valueName failed", it)
        false
    }

    /** Deletes a key and everything under it. A key that is already gone counts as success. */
    fun deleteTree(path: String): Boolean = runCatching {
        Arena.ofConfined().use { arena ->
            val status = regDeleteTree.invoke(HKEY_CURRENT_USER, arena.wide(path)) as Int
            if (status != ERROR_SUCCESS && status != ERROR_FILE_NOT_FOUND) {
                log("RegDeleteTreeW", path, status)
                return false
            }
            true
        }
    }.getOrElse {
        Logger.error("Registry: deleting $path failed", it)
        false
    }

    /** UTF-16LE, null-terminated: what every `*W` entry point expects. */
    private fun Arena.wide(text: String): MemorySegment = allocateFrom(text, StandardCharsets.UTF_16LE)

    /** Reads back a REG_SZ payload, which may or may not include its terminator in [bytes]. */
    private fun decodeWide(buffer: MemorySegment, bytes: Int): String {
        val length = minOf(bytes, buffer.byteSize().toInt()).coerceAtLeast(0)
        if (length == 0) return ""
        val raw = buffer.asSlice(0, length.toLong()).toArray(JAVA_BYTE)
        val text = String(raw, StandardCharsets.UTF_16LE)
        return text.substringBefore('\u0000')
    }

    private fun log(call: String, path: String, status: Int) {
        Logger.error("Registry: $call on HKCU\\$path failed with status $status")
    }
}
