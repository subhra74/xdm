package xdm.app.utils.win

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle

/**
 * The small amount of java.lang.foreign plumbing the platform layers share.
 *
 * Nothing here is loaded until a platform feature actually calls a native function: the first touch
 * of this object is what pulls in the FFM machinery (`jdk.internal.foreign.abi`, the generated
 * method handles), which is a few hundred classes of metaspace we would rather not pay for on a
 * platform that never makes a native call. Keep the call sites lazy.
 *
 * Downcalls are made with [MethodHandle.invoke] rather than `invokeExact`: `invoke` adapts the call
 * site to the handle's type, so it is correct regardless of how the Kotlin compiler renders the
 * signature-polymorphic call. These handles are used a handful of times per session, so the
 * adaptation costs nothing that matters.
 */
internal object Ffm {

    val linker: Linker by lazy { Linker.nativeLinker() }

    /** Resolves [name] in [lookup] and binds it to [descriptor]. Throws if the symbol is missing. */
    fun downcall(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name).orElseThrow { UnsatisfiedLinkError("Symbol not found: $name") }
        return linker.downcallHandle(symbol, descriptor)
    }

}
