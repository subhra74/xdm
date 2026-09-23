package xdm.integration

/**
 * Lets the browser extension learn about state changes (and about XDM exiting) without polling.
 *
 * The extension keeps one long-poll request parked here per install; [await] blocks that request's
 * thread until the state version moves past what the extension already has, until the request times
 * out, or until XDM shuts down. Because the poll holds a real socket open, a crashed or killed app
 * is noticed by the extension as soon as the OS tears the connection down, rather than on its next
 * alarm.
 *
 * Waiters are keyed by the extension's client id so a restarted background worker replaces its own
 * stale poll instead of stacking a second parked thread on top of it.
 */
object EventChannel {

    /**
     * How long a poll parks before returning empty-handed. Kept well under the ~30s idle timeout of
     * an MV3 service worker, which an in-flight request holds off.
     */
    const val POLL_TIMEOUT_MS = 20_000L

    /** Guards against a hostile page parking every handler thread; extras are turned away at once. */
    private const val MAX_WAITERS = 8

    private val lock = Object()

    /** Bumped on every change the extension cares about; also handed out in `ConfigDto.version`. */
    private var version = 1L

    /** clientId -> the token of that client's currently parked poll. */
    private val waiters = HashMap<String, Any>()

    private var shuttingDown = false

    enum class Outcome {
        /** State moved on: reply with the current config. */
        CHANGED,

        /** Nothing happened in time: reply empty and let the extension poll again. */
        TIMEOUT,

        /** The same client parked a newer poll: release this one immediately. */
        SUPERSEDED,

        /** Too many parked polls already. */
        BUSY,

        /** XDM is exiting: tell the extension so it doesn't have to infer it from the socket. */
        BYE
    }

    val currentVersion: Long
        get() = synchronized(lock) { version }

    /** Called whenever the detected-video list or config changes. */
    fun notifyChanged() {
        synchronized(lock) {
            version++
            lock.notifyAll()
        }
    }

    /** Releases every parked poll with [Outcome.BYE]; called from the shutdown hook. */
    fun shutdown() {
        synchronized(lock) {
            shuttingDown = true
            lock.notifyAll()
        }
    }

    fun await(clientId: String, sinceVersion: Long, timeoutMs: Long = POLL_TIMEOUT_MS): Outcome {
        synchronized(lock) {
            if (shuttingDown) return Outcome.BYE
            // An extension that is already behind gets an answer without parking at all.
            if (version > sinceVersion) return Outcome.CHANGED
            if (!waiters.containsKey(clientId) && waiters.size >= MAX_WAITERS) return Outcome.BUSY

            val token = Any()
            waiters[clientId] = token
            // Wake whatever this client had parked before, so its thread ends now rather than
            // lingering until its own timeout.
            lock.notifyAll()
            val deadline = System.currentTimeMillis() + timeoutMs
            try {
                while (true) {
                    if (shuttingDown) return Outcome.BYE
                    if (waiters[clientId] !== token) return Outcome.SUPERSEDED
                    if (version > sinceVersion) return Outcome.CHANGED
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) return Outcome.TIMEOUT
                    lock.wait(remaining)
                }
            } finally {
                // Only drop our own registration: a newer poll for this client owns the slot now.
                if (waiters[clientId] === token) waiters.remove(clientId)
            }
        }
    }
}
