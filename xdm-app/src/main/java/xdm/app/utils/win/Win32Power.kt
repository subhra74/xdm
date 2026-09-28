package xdm.app.utils.win

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * `SetThreadExecutionState`, which is how Windows applications say "do not go to sleep on me".
 *
 * The state belongs to the **calling thread** and Windows drops it when that thread exits, so the
 * caller has to own a thread for as long as the inhibitor should last (see `KeepAwake`). That is
 * also the safety net: if XDM dies, the thread dies with it and the machine sleeps normally again.
 */
internal object Win32Power {

    /** Keep the state in effect until it is changed again, rather than for one idle cycle. */
    const val ES_CONTINUOUS = 0x80000000.toInt()

    /** The system may not idle-sleep. Deliberately not ES_DISPLAY_REQUIRED: the screen may sleep. */
    const val ES_SYSTEM_REQUIRED = 0x00000001

    // EXECUTION_STATE SetThreadExecutionState(EXECUTION_STATE esFlags)
    private val handle by lazy {
        Win32.kernel("SetThreadExecutionState", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    }

    /** Returns the previous state, or 0 if the call failed. */
    fun setThreadExecutionState(flags: Int): Int = handle.invoke(flags) as Int
}
