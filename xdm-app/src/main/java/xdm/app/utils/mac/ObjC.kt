package xdm.app.utils.mac

import xdm.app.utils.win.Ffm
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * The slice of the Objective-C runtime the macOS layer needs, through java.lang.foreign: classes,
 * selectors, `objc_msgSend` bound per call shape, autorelease pools, a class defined at run time,
 * and blocks.
 *
 * An Objective-C exception thrown into a downcall takes the whole process down, so callers only send
 * messages that cannot throw in the state they have checked. macOS only - callers must check the OS.
 */
internal object ObjC {

    private val arena: Arena = Arena.global()
    private val lookup: SymbolLookup by lazy { SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena) }

    private val getClass by lazy { Ffm.downcall(lookup, "objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS)) }
    private val registerName by lazy { Ffm.downcall(lookup, "sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS)) }
    private val poolPush by lazy { Ffm.downcall(lookup, "objc_autoreleasePoolPush", FunctionDescriptor.of(ADDRESS)) }
    private val poolPop by lazy { Ffm.downcall(lookup, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val allocateClassPair by lazy {
        Ffm.downcall(lookup, "objc_allocateClassPair", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
    }
    private val registerClassPair by lazy {
        Ffm.downcall(lookup, "objc_registerClassPair", FunctionDescriptor.ofVoid(ADDRESS))
    }
    private val addMethod by lazy {
        Ffm.downcall(lookup, "class_addMethod", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    }
    private val msgSend: MemorySegment by lazy { lookup.find("objc_msgSend").orElseThrow() }

    /** `_NSConcreteGlobalBlock` from libsystem_blocks (re-exported by libSystem). */
    private val globalBlockIsa: MemorySegment by lazy {
        Linker.nativeLinker().defaultLookup().find("_NSConcreteGlobalBlock")
            .or { SymbolLookup.libraryLookup("/usr/lib/system/libsystem_blocks.dylib", arena).find("_NSConcreteGlobalBlock") }
            .orElseThrow { UnsatisfiedLinkError("_NSConcreteGlobalBlock") }
    }

    private val sends = ConcurrentHashMap<FunctionDescriptor, MethodHandle>()
    private val selectors = ConcurrentHashMap<String, MemorySegment>()

    /** `id f(id, SEL)` */
    private val ID = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)

    /** `id f(id, SEL, id)` */
    private val ID_ID = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS)

    fun cls(name: String): MemorySegment = Arena.ofConfined().use { getClass.invoke(it.allocateFrom(name)) as MemorySegment }

    fun sel(name: String): MemorySegment = selectors.computeIfAbsent(name) { n ->
        Arena.ofConfined().use { registerName.invoke(it.allocateFrom(n)) as MemorySegment }
    }

    /** `objc_msgSend` typed as [descriptor]: receiver and selector come first. */
    fun send(descriptor: FunctionDescriptor): MethodHandle =
        sends.computeIfAbsent(descriptor) { Ffm.linker.downcallHandle(msgSend, it) }

    fun call(receiver: MemorySegment, selector: String): MemorySegment =
        send(ID).invoke(receiver, sel(selector)) as MemorySegment

    fun call(receiver: MemorySegment, selector: String, arg: MemorySegment): MemorySegment =
        send(ID_ID).invoke(receiver, sel(selector), arg) as MemorySegment

    /** An autoreleased `NSString`; only valid inside [autoreleasing]. */
    fun nsString(text: String): MemorySegment = Arena.ofConfined().use {
        call(cls("NSString"), "stringWithUTF8String:", it.allocateFrom(text))
    }

    fun string(nsString: MemorySegment): String? =
        if (nsString == MemorySegment.NULL) null
        else call(nsString, "UTF8String").reinterpret(Long.MAX_VALUE).getString(0)

    /**
     * Runs [action] inside an autorelease pool. Java threads have none, so without this every
     * autoreleased object (strings, settings, errors) would leak.
     */
    fun <T> autoreleasing(action: () -> T): T {
        val pool = poolPush.invoke() as MemorySegment
        try {
            return action()
        } finally {
            poolPop.invoke(pool)
        }
    }

    /**
     * Defines `NSObject` subclass [name] with [methods] (selector to IMP and its type encoding), or
     * returns the class when it already exists (a second call in the same process).
     */
    fun defineClass(name: String, methods: Map<String, Pair<MemorySegment, String>>): MemorySegment {
        val created = Arena.ofConfined().use { a ->
            allocateClassPair.invoke(cls("NSObject"), a.allocateFrom(name), 0L) as MemorySegment
        }
        if (created == MemorySegment.NULL) return cls(name)
        for ((selector, imp) in methods) {
            addMethod.invoke(created, sel(selector), imp.first, arena.allocateFrom(imp.second))
        }
        registerClassPair.invoke(created)
        return created
    }

    /** A C function pointer to static method [name] of [owner], typed as [descriptor]. Lives forever. */
    fun upcall(owner: Class<*>, name: String, descriptor: FunctionDescriptor): MemorySegment {
        val target = java.lang.invoke.MethodHandles.lookup()
            .findStatic(owner, name, descriptor.toMethodType())
        return Ffm.linker.upcallStub(target, descriptor, arena)
    }

    /**
     * A global block (`BLOCK_IS_GLOBAL`): `Block_copy` returns it unchanged and nothing ever frees it,
     * so one block per callback shape serves every call. [invoke] receives the block itself first.
     * [signature] is the block's type encoding, e.g. `v@?B@` for `^(BOOL, NSError *)`.
     */
    fun globalBlock(invoke: MemorySegment, signature: String): MemorySegment {
        // Block_descriptor_1 + signature: { unsigned long reserved; unsigned long size; const char *sig; }
        val descriptor = arena.allocate(24, 8)
        descriptor.set(JAVA_LONG, 0, 0L)
        descriptor.set(JAVA_LONG, 8, BLOCK_SIZE)
        descriptor.set(ADDRESS, 16, arena.allocateFrom(signature))
        // Block_literal: { void *isa; int flags; int reserved; void *invoke; void *descriptor; }
        val block = arena.allocate(BLOCK_SIZE, 8)
        block.set(ADDRESS, 0, globalBlockIsa)
        block.set(JAVA_INT, 8, BLOCK_IS_GLOBAL or BLOCK_HAS_SIGNATURE)
        block.set(JAVA_INT, 12, 0)
        block.set(ADDRESS, 16, invoke)
        block.set(ADDRESS, 24, descriptor)
        return block
    }

    /** Calls [block] (handed to us by the system) as `descriptor`; the block itself is passed first. */
    fun invokeBlock(block: MemorySegment, descriptor: FunctionDescriptor, vararg args: Any?) {
        val fn = block.reinterpret(BLOCK_SIZE).get(ADDRESS, 16)
        Ffm.linker.downcallHandle(fn, descriptor).invokeWithArguments(listOf(block) + args)
    }

    private const val BLOCK_SIZE = 32L
    private const val BLOCK_IS_GLOBAL = 1 shl 28
    private const val BLOCK_HAS_SIGNATURE = 1 shl 30
}
