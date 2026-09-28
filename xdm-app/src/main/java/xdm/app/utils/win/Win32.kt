package xdm.app.utils.win

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle

/**
 * Win32 libraries, looked up lazily.
 *
 * `Advapi32`/`Kernel32` are not in the linker's default lookup, so they are resolved by name. Both
 * are already mapped into every Windows process, so [SymbolLookup.libraryLookup] just takes a
 * reference to what is loaded; [Arena.global] keeps that reference for the life of the process,
 * which is what we want for handles the app may use at any time.
 *
 * Touching this object on a non-Windows platform will fail - callers must check the OS first.
 */
internal object Win32 {

    private val advapi32: SymbolLookup by lazy { SymbolLookup.libraryLookup("Advapi32.dll", Arena.global()) }
    private val kernel32: SymbolLookup by lazy { SymbolLookup.libraryLookup("Kernel32.dll", Arena.global()) }

    fun advapi(name: String, descriptor: FunctionDescriptor): MethodHandle =
        Ffm.downcall(advapi32, name, descriptor)

    fun kernel(name: String, descriptor: FunctionDescriptor): MethodHandle =
        Ffm.downcall(kernel32, name, descriptor)
}
