package xdm.app.utils.win

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * `SHQueryUserNotificationState`: whether Windows would show a notification right now, or the user is
 * in a full-screen game, a presentation, or Focus Assist / Do Not Disturb.
 *
 * Windows only - callers must check the OS.
 */
internal object Win32Notifications {

    private const val QUNS_ACCEPTS_NOTIFICATIONS = 5
    private const val QUNS_APP = 7

    // HRESULT SHQueryUserNotificationState(QUERY_USER_NOTIFICATION_STATE *pquns)
    private val handle by lazy {
        Win32.shell("SHQueryUserNotificationState", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    }

    /** False when a notification would interrupt the user; true when it can't be told. */
    fun acceptsNotifications(): Boolean = runCatching {
        Arena.ofConfined().use { arena ->
            val state: MemorySegment = arena.allocate(JAVA_INT)
            val hr = handle.invoke(state) as Int
            // QUNS_APP is a Windows Store app in full screen (Windows 8 only): it allowed notifications.
            hr != 0 || state.get(JAVA_INT, 0).let { it == QUNS_ACCEPTS_NOTIFICATIONS || it == QUNS_APP }
        }
    }.getOrDefault(true)
}
