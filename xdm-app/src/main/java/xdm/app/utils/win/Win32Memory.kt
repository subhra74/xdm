package xdm.app.utils.win

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * `SetProcessWorkingSetSize(GetCurrentProcess(), -1, -1)`: removes as many pages as possible from
 * this process's working set. Nothing is freed: the pages move to the standby/modified lists and
 * come back on a soft fault when touched again (a hard fault only if Windows reused them meanwhile).
 */
internal object Win32Memory {

    // HANDLE GetCurrentProcess(void) - a pseudo handle, never closed
    private val currentProcess by lazy {
        Win32.kernel("GetCurrentProcess", FunctionDescriptor.of(ADDRESS))
    }

    // BOOL SetProcessWorkingSetSize(HANDLE hProcess, SIZE_T dwMinimumWorkingSetSize, SIZE_T dwMaximumWorkingSetSize)
    private val setWorkingSetSize by lazy {
        Win32.kernel("SetProcessWorkingSetSize", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG))
    }

    /** Returns false if Windows refused. (SIZE_T)-1 for both sizes means "trim". */
    fun trimWorkingSet(): Boolean {
        val process = currentProcess.invoke() as MemorySegment
        return (setWorkingSetSize.invoke(process, -1L, -1L) as Int) != 0
    }
}
